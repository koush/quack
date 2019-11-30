package com.koushikdutta.quack.polyfill

import com.koushikdutta.quack.JavaScriptObject
import com.koushikdutta.quack.QuackContext
import com.koushikdutta.quack.polyfill.crypto.CryptoModule
import com.koushikdutta.quack.polyfill.dgram.DgramModule
import com.koushikdutta.quack.polyfill.dns.DnsModule
import com.koushikdutta.quack.polyfill.fs.FsModule
import com.koushikdutta.quack.polyfill.net.NetModule
import com.koushikdutta.quack.polyfill.require.Modules
import com.koushikdutta.quack.polyfill.job.installJobScheduler
import com.koushikdutta.quack.polyfill.tls.TlsModule
import com.koushikdutta.quack.polyfill.xmlhttprequest.XMLHttpRequest
import com.koushikdutta.scratch.buffers.ByteBuffer
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.http.client.AsyncHttpClient

class QuackEventLoop(val loop: AsyncEventLoop, val quack: QuackContext) {
    constructor() : this(AsyncEventLoop(), QuackContext.create())

    init {
        installJobScheduler()

        quack.putJavaScriptToJavaCoercion(ByteBuffer::class.java) { clazz, o ->
            val jo = o as JavaScriptObject
            jo.toByteBuffer()
        }
    }

    fun installDefaultModules(modules: Modules): Modules {
        modules["dgram"] = DgramModule(this, modules)
        modules["net"] = NetModule(this, modules)
        modules["tls"] = TlsModule(this, modules)
        modules["fs"] = FsModule(this, modules)
        modules["crypto"] = CryptoModule(this, modules)
        modules["dns"] = DnsModule(this, modules)
        val client = AsyncHttpClient(loop)
        quack.globalObject.set("XMLHttpRequest", XMLHttpRequest.Constructor(quack, client))
        return modules
    }
}

fun JavaScriptObject.toByteBuffer(): ByteBuffer {
    val byteOffset = get("byteOffset") as Int
    val length = get("length") as Int
    val buffer = get("buffer") as ByteBuffer
    buffer.position(byteOffset)
    buffer.limit(byteOffset + length)
    return buffer
}

