package jvmdbbroker.plugin.http

import java.io._
import java.util.concurrent._
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock;
import java.nio.ByteBuffer;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import scala.collection.mutable.ArrayBuffer

import org.jboss.netty.buffer._;
import org.jboss.netty.channel._;
import org.jboss.netty.handler.timeout._;
import org.jboss.netty.bootstrap._;
import org.jboss.netty.channel.group._;
import org.jboss.netty.channel.socket.nio._;
import org.jboss.netty.handler.codec.http._;
import org.jboss.netty.util._;
import org.jboss.netty.handler.stream._;

import jvmdbbroker.core._

// used by netty
trait HttpServer4Netty {
    def receive(req:HttpRequest,connId:String):Unit;
}

class NettyHttpServerHandler(val nettyHttpServer: NettyHttpServer,val sos: HttpServer4Netty)
extends IdleStateAwareChannelHandler with Logging  {

    val conns = new ConcurrentHashMap[String,Channel]()
    val new_conns = new AtomicInteger(0)
    val new_disconns = new AtomicInteger(0)

    var stopFlag = new AtomicBoolean()

    def close() = {stopFlag.set(true)}

    def stats() : Array[Int] = {
        val a = new_conns.getAndSet(0)
        val b = new_disconns.getAndSet(0)
        Array(a,b,conns.size)
    }

    def write(connId:String, response:HttpResponse, keepAlive:Boolean = false,reqResInfo:HttpSosRequestResponseInfo = null) : Boolean = {

        val ch = conns.get(connId)
        if( ch == null ) {
            log.error("connection not found, id={}",connId)
            return false;
        }

        if( ch.isOpen ) {
            val future = ch.write(response)
            if( !keepAlive ) 
                future.addListener(ChannelFutureListener.CLOSE)
            if( reqResInfo != null ) {
                future.addListener(new ChannelFutureListener() {
                    def operationComplete(future: ChannelFuture) {
                        reqResInfo.res.receivedTime = System.currentTimeMillis
                        Flow.router.asyncLogActor.receive(reqResInfo)
                    }
                });
            }
            return true
        }

        return false
    }

    def writeFile(connId:String, response:HttpResponse, keepAlive:Boolean, f: File,fileLength:Long,reqResInfo:HttpSosRequestResponseInfo = null) : Boolean = {

        val ch = conns.get(connId)
        if( ch == null ) {
            log.error("connection not found, id={}",connId)
            return false;
        }

        if( ch.isOpen ) {
            ch.write(response)
            val future = ch.write(new ChunkedFile(f))
            if( !keepAlive )
                future.addListener(ChannelFutureListener.CLOSE)
            if( reqResInfo != null ) {
                future.addListener(new ChannelFutureListener() {
                    def operationComplete(future: ChannelFuture) {
                        reqResInfo.res.receivedTime = System.currentTimeMillis
                        Flow.router.asyncLogActor.receive(reqResInfo)
                    }
                });
            }
            return true
        }

        return false
    }
    override def messageReceived(ctx:ChannelHandlerContext, e:MessageEvent): Unit = {

        val ch = e.getChannel
        if (stopFlag.get()) {
            ch.setReadable(false)
            return
        }

        val request = e.getMessage().asInstanceOf[HttpRequest]
        val connId = ctx.getAttachment().asInstanceOf[String];

        try {
            sos.receive(request, connId);
        } catch {
            case e:Exception =>
                log.error("httpserver decode error, connId="+connId,e);
        }
    }

    override def channelConnected(ctx:ChannelHandlerContext, e:ChannelStateEvent):Unit = {

        new_conns.incrementAndGet()

        val ch = e.getChannel
        val connId = parseIpPort(ch.getRemoteAddress.toString) + ":" + ch.getId
        ctx.setAttachment(connId);

        log.info("connection started, id={}",connId)

        conns.put(connId,ch)
        //sos.connected(connId)

        if (stopFlag.get()) {
            ch.close
        }
    }

    override def channelDisconnected(ctx:ChannelHandlerContext, e:ChannelStateEvent):Unit = {

        new_disconns.incrementAndGet()

        val connId = ctx.getAttachment().asInstanceOf[String];
        log.info("connection ended, id={}",connId)
        conns.remove(connId)
        //sos.disconnected(connId)
    }

    override def channelIdle(ctx:ChannelHandlerContext, e:IdleStateEvent) : Unit = {
        val connId = ctx.getAttachment().asInstanceOf[String];
        log.error("connection timeout, id={}",connId)
        e.getChannel.close()
    }

    override def exceptionCaught(ctx:ChannelHandlerContext, e: ExceptionEvent) :Unit = {
        val connId = ctx.getAttachment().asInstanceOf[String];
        log.error("connection exception, id={},msg={}",connId,e.toString)
        e.getChannel.close()
    }

    def parseIpPort(s:String):String = {

        val p = s.indexOf("/")

        if (p >= 0)
            s.substring(p + 1)
        else
            s
    }

}

object NettyHttpServer {
    val count = new AtomicInteger(1)
}

class NettyHttpServer(val sos: HttpServer4Netty,
    val port:Int,
    val host:String = "*",
    val idleTimeoutMillis: Int = 45000,
    val maxContentLength: Int = 5000000,
    val maxInitialLineLength:Int = 16000,
    val maxHeaderSize:Int = 16000,
    val maxChunkSize:Int = 16000
    ) extends Logging with Dumpable {

    /*
    Creates a new instance with the default maxInitialLineLength (4096}, maxHeaderSize (8192), and maxChunkSize (8192).
    HttpRequestDecoder(int maxInitialLineLength, int maxHeaderSize, int maxChunkSize)
    */

    var nettyHttpServerHandler : NettyHttpServerHandler = _
    var allChannels:ChannelGroup= _
    var channelFactory:ChannelFactory= _
    var timer: Timer = _

    val bossThreadFactory = new NamedThreadFactory("httpserverboss"+NettyHttpServer.count.getAndIncrement())
    val workThreadFactory = new NamedThreadFactory("httpserverwork"+NettyHttpServer.count.getAndIncrement())
    val timerThreadFactory = new NamedThreadFactory("httpservertimer"+NettyHttpServer.count.getAndIncrement())

    var bossExecutor:ThreadPoolExecutor = _
    var workerExecutor:ThreadPoolExecutor = _

    def stats() : Array[Int] = {
        nettyHttpServerHandler.stats
    }

    def dump() {
        if( nettyHttpServerHandler == null ) return

        val buff = new StringBuilder

        buff.append("nettyHttpServerHandler.conns.size=").append(nettyHttpServerHandler.conns.size).append(",")
        buff.append("bossExecutor.getPoolSize=").append(bossExecutor.getPoolSize).append(",")
        buff.append("bossExecutor.getQueue.size=").append(bossExecutor.getQueue.size).append(",")
        buff.append("workerExecutor.getPoolSize=").append(workerExecutor.getPoolSize).append(",")
        buff.append("workerExecutor.getQueue.size=").append(workerExecutor.getQueue.size).append(",")

        log.info(buff.toString)
    }

    def start() : Unit = {

        nettyHttpServerHandler = new NettyHttpServerHandler(this,sos)

        // without this line, the thread name of netty will not be changed
        ThreadRenamingRunnable.setThreadNameDeterminer(ThreadNameDeterminer.CURRENT); // or PROPOSED

        timer = new HashedWheelTimer(timerThreadFactory,1,TimeUnit.SECONDS)

        allChannels = new DefaultChannelGroup("netty-httpserver-scala")

        bossExecutor = Executors.newCachedThreadPool(bossThreadFactory).asInstanceOf[ThreadPoolExecutor]
        workerExecutor = Executors.newCachedThreadPool(workThreadFactory).asInstanceOf[ThreadPoolExecutor]
        channelFactory = new NioServerSocketChannelFactory(bossExecutor ,workerExecutor )

        val bootstrap = new ServerBootstrap(channelFactory)
        bootstrap.setPipelineFactory(new NettyHttpServerPipelineFactory())
        bootstrap.setOption("child.tcpNoDelay", true)
        // bootstrap.setOption("child.keepAlive", true)
        bootstrap.setOption("reuseAddress", true)
        bootstrap.setOption("child.receiveBufferSize", 65536)

        val addr =
            if (host == null || "*" == host) {
                new InetSocketAddress(port)
            } else {
                new InetSocketAddress(host, port)
            }

        val channel = bootstrap.bind(addr)
        allChannels.add(channel)

        val s = "netty httpserver started on host(" + host + ") port(" + port + ")"
        log.info(s)
    }

    def write(connId:String, response:HttpResponse, keepAlive:Boolean = true,reqResInfo:HttpSosRequestResponseInfo = null) : Boolean = {
        nettyHttpServerHandler.write(connId,response,keepAlive,reqResInfo)
    }

    def writeFile(connId:String, response:HttpResponse, keepAlive:Boolean, f: File,fileLength:Long,reqResInfo:HttpSosRequestResponseInfo = null) : Boolean = {
        nettyHttpServerHandler.writeFile(connId,response,keepAlive,f,fileLength,reqResInfo)
    }

    def closeReadChannel() {
        if( nettyHttpServerHandler != null ) {
            nettyHttpServerHandler.close()
        }
        log.info("nettyHttpServerHandler read channel stopped")
    }

    def close() : Unit = {

        if (channelFactory != null) {

            log.info("Stopping netty httpserver")

            timer.stop()
            timer = null

            val chs = nettyHttpServerHandler.conns.values.iterator
            while(chs.hasNext()) {
                allChannels.add(chs.next())
            }
            val future = allChannels.close()
            future.awaitUninterruptibly()
            allChannels = null

            channelFactory.releaseExternalResources()
            channelFactory = null

            log.info("netty httpserver stopped")
        }
    }

    class NettyHttpServerPipelineFactory extends Object with ChannelPipelineFactory {

        def getPipeline() : ChannelPipeline =  {
            val pipeline = Channels.pipeline()
            pipeline.addLast("timeout", new IdleStateHandler(timer, 0, 0, idleTimeoutMillis / 1000))
            pipeline.addLast("decoder", new HttpRequestDecoder(maxInitialLineLength,maxHeaderSize,maxChunkSize))
            pipeline.addLast("aggregator", new HttpChunkAggregator(maxContentLength))
            pipeline.addLast("encoder", new HttpResponseEncoder())
            pipeline.addLast("chunkedWriter", new ChunkedWriteHandler())
            pipeline.addLast("deflater", new HttpContentCompressor())
            pipeline.addLast("handler", nettyHttpServerHandler)
            pipeline;
        }
    }

}


