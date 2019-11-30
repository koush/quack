package com.koushikdutta.quack.polyfill

import com.koushikdutta.quack.JavaScriptObject
import com.koushikdutta.quack.QuackContext
import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.ByteBuffer
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.event.AsyncEventLoop
import java.lang.Exception


interface Stream: EventEmitter {
    fun pause()
    fun resume()
    fun read(): ByteBuffer?
}

fun Stream.createAsyncRead(ctx: QuackContext): AsyncRead {
    val yielder = Cooperator()
    var more = true
    on("readable") {
        yielder.resume()
    }

    on("end") {
        more = false
        yielder.resume()
    }

    on("error") {
        more = false
        yielder.resume()
    }

    return read@{
        val buffer = read()
        if (buffer == null) {
            yielder.yield()
            return@read more
        }
        it.add(buffer)
        true
    }
}

interface ReadableStream : EventEmitter {
    fun push(buffer: ByteBuffer): Boolean
    fun destroy(error: JavaScriptObject?)
}

interface WritableStream : EventEmitter

interface DuplexStream: ReadableStream, WritableStream

interface Destroyable {
    fun _destroy(err: Any?, callback: JavaScriptObject?)
}

interface Readable: Destroyable {
    fun _read(len: Int?)
}

interface Writable: Destroyable {
    fun _write(chunk: JavaScriptObject, encoding: String?, callback: JavaScriptObject?)
    fun _final(callback: JavaScriptObject?)
}

interface Duplex : Readable, Writable

interface BaseReadable : Readable {
    val quackLoop: QuackEventLoop
    val stream: ReadableStream
    suspend fun getAsyncRead(): AsyncRead
    var pauser: Cooperator?

    override fun _read(len: Int?) {
        quackLoop.loop.async {
            try {
                // prevent the read loop from being started twice.
                // subsequent calls to read will just resume data pumping.
                if (pauser != null) {
                    pauser!!.resume()
                    return@async
                }
                pauser = Cooperator()
                val buffer = ByteBufferList()
                while (getAsyncRead()(buffer)) {
                    if (buffer.isEmpty)
                        continue
                    if (!stream.push(buffer.readByteBuffer()))
                        pauser!!.yield()
                }
            }
            catch (e: Exception) {
                stream.destroy(quackLoop.quack.newError(e))
            }
            stream.emitSafely(quackLoop, "end")
            stream.emitSafely(quackLoop, "close")
        }
    }
}

interface BaseWritable : Writable {
    val quackLoop: QuackEventLoop
    var finalCallback: JavaScriptObject?

    suspend fun getAsyncWrite(): AsyncWrite

    override fun _write(chunk: JavaScriptObject, encoding: String?, callback: JavaScriptObject?) {
        quackLoop.loop.async {
            try {
                val buffer = ByteBufferList(chunk.get("buffer") as ByteBuffer)
                while (buffer.hasRemaining()) {
                    getAsyncWrite()(buffer)
                }
                callback?.callSafely(quackLoop, null)
                finalCallback?.callSafely(quackLoop, null)
            }
            catch (e: Exception) {
                val err = quackLoop.quack.newError(e)
                callback?.callSafely(quackLoop, err)
                finalCallback?.callSafely(quackLoop, err)
            }
        }
    }

    override fun _final(callback: JavaScriptObject?) {
        finalCallback = callback
    }
}

interface BaseDuplex : Duplex, BaseReadable, BaseWritable
