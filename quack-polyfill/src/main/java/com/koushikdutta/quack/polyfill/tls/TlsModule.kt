package com.koushikdutta.quack.polyfill.tls

import com.koushikdutta.quack.JavaScriptObject
import com.koushikdutta.quack.QuackProperty
import com.koushikdutta.quack.polyfill.*
import com.koushikdutta.quack.polyfill.ArgParser
import com.koushikdutta.quack.polyfill.dgram.UdpAddress
import com.koushikdutta.quack.polyfill.jsonCoerce
import com.koushikdutta.quack.polyfill.mixinExtend
import com.koushikdutta.quack.polyfill.net.*
import com.koushikdutta.quack.polyfill.require.Modules
import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.AsyncWrite
import com.koushikdutta.scratch.event.AsyncNetworkSocket
import com.koushikdutta.scratch.tls.AsyncTlsSocket
import com.koushikdutta.scratch.tls.connectTls

interface TlsSocket: Socket

class TlsSocketImpl(quackLoop: QuackEventLoop, stream: DuplexStream, options: CreateSocketOptions?) : SocketImpl(quackLoop, stream, options), TlsSocket {
    var tlsSocket: AsyncTlsSocket? = null
    override suspend fun connectInternal(connectHost: String, port: Int): AsyncNetworkSocket {
        tlsSocket = quackLoop.loop.connectTls(connectHost, port)
        return tlsSocket!!.socket as AsyncNetworkSocket
    }

    override suspend fun getAsyncRead(): AsyncRead {
        super.getAsyncRead()
        return tlsSocket!!::read
    }

    override suspend fun getAsyncWrite(): AsyncWrite {
        super.getAsyncWrite()
        return tlsSocket!!::write
    }
}

class TlsModule(val quackLoop: QuackEventLoop, val modules: Modules) {
    @get:QuackProperty(name = "TLSSocket")
    val socketClass: JavaScriptObject

    val duplexClass: JavaScriptObject
    val ctx = quackLoop.quack
    init {
        duplexClass = modules.require("stream").get("Duplex") as JavaScriptObject

        socketClass = mixinExtend(ctx, duplexClass, DuplexStream::class.java, TlsSocket::class.java, "TLSSocket") { stream, arguments ->
            val parser = ArgParser(quackLoop.quack, *arguments)
            val options = parser(CreateSocketOptions::class.java)

            TlsSocketImpl(quackLoop, stream, options)
        }
    }

    private fun newSocket(options: JavaScriptObject?): JavaScriptObject {
        return socketClass.construct(options)
    }

    fun connect(vararg arguments: Any?): JavaScriptObject {
        val parser = ArgParser(quackLoop.quack, *arguments)
        val options = parser(JavaScriptObject::class.java)
        if (options != null) {
            val socket = newSocket(options)
            val mixin = socket.getMixin(TlsSocketImpl::class.java)
            mixin.connect(ctx.coerceJavaScriptToJava(ConnectSocketOptions::class.java, options) as ConnectSocketOptions, parser(JavaScriptObject::class.java))
            return socket
        }

        val port = parser(Int::class.java)!!
        val host = parser(String::class.java)
        val socket = newSocket(null)
        val mixin = socket.getMixin(TlsSocketImpl::class.java)
        mixin.connect(port, host, parser(JavaScriptObject::class.java))
        return socket
    }
}