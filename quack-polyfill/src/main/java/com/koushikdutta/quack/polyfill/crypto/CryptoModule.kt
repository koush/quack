package com.koushikdutta.quack.polyfill.crypto

import com.koushikdutta.quack.JavaScriptObject
import com.koushikdutta.quack.QuackMethodObject
import com.koushikdutta.quack.QuackProperty
import com.koushikdutta.quack.polyfill.ArgParser
import com.koushikdutta.quack.polyfill.QuackEventLoop
import com.koushikdutta.quack.polyfill.callSafely
import com.koushikdutta.quack.polyfill.require.Modules
import com.koushikdutta.scratch.buffers.ByteBuffer
import java.lang.Exception
import java.lang.IllegalArgumentException
import java.security.MessageDigest
import kotlin.random.Random

class Hash(val cryptoModule: CryptoModule, val messageDigest: MessageDigest) {
    fun update(data: Any, inputEncoding: String?): Hash {
        val buffer: ByteBuffer
        if (data is ByteBuffer)
            buffer = data
        else if (data is String) {
            buffer = ByteBuffer.wrap(data.toString().toByteArray())
        }
        else {
            val jo = data as JavaScriptObject
            buffer = jo.get("buffer") as ByteBuffer
        }

        messageDigest.update(buffer)
        return this
    }

    fun digest(encoding: String?): Any {
        val bytes = messageDigest.digest()
        val buffer = ByteBuffer.wrap(bytes)
        if (encoding == null)
            return cryptoModule.bufferClass.callProperty("from", buffer)
        if (encoding == "hex")
            return bytes.joinToString("") { "%02x".format(it) }.toLowerCase()
        throw IllegalArgumentException("Unknown encoding $encoding")
    }
}

class CryptoModule(val quackLoop: QuackEventLoop, modules: Modules) {
    val bufferClass: JavaScriptObject = modules.require("buffer").get("Buffer") as JavaScriptObject

    fun createHash(name: String): Hash {
        return Hash(this, MessageDigest.getInstance(name))
    }

    @get:QuackProperty(name = "randomBytes")
    val randomBytes = object : QuackMethodObject {
        override fun callMethod(thiz: Any?, vararg args: Any?): Any? {
            val parser = ArgParser(quackLoop.quack, *args)
            val size = parser(Int::class.java)!!
            val callback = parser(JavaScriptObject::class.java)
            val bytes = Random.nextBytes(size)
            val buffer = bufferClass.callProperty("from", ByteBuffer.wrap(bytes)) as JavaScriptObject
            if (callback == null)
                return buffer
            quackLoop.loop.post {
                callback.callSafely(quackLoop, buffer)
            }
            return null
        }
    }

}