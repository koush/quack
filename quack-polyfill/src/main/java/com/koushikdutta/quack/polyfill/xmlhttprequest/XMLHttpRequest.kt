package com.koushikdutta.quack.polyfill.xmlhttprequest

import com.koushikdutta.quack.JavaScriptObject
import com.koushikdutta.quack.QuackContext
import com.koushikdutta.quack.QuackObject
import com.koushikdutta.quack.QuackProperty
import com.koushikdutta.scratch.async
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.event.AsyncServerRunnable
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.Headers
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.parser.readAllBuffer
import com.koushikdutta.scratch.uri.URI
import com.koushikdutta.scratch.uri.URLEncoder
import java.nio.ByteBuffer

private fun safeRun(runnable: AsyncServerRunnable) {
    try {
        runnable()
    }
    catch (e: Exception) {
        println(e);
    }
}

interface ErrorCallback {
    fun onError(error: JavaScriptObject)
}

class XMLHttpRequest(val context: QuackContext, val client: AsyncHttpClient) {
    @get:QuackProperty(name = "readyState")
    var readyState = 0
        private set

    @get:QuackProperty(name = "response")
    var response: Any? = null
        private set

    @get:QuackProperty(name = "responseText")
    var responseText: String? = null
        private set

    @get:QuackProperty(name = "responseType")
    @set:QuackProperty(name = "responseType")
    var responseType = "text"
        set(value) {
            if (value == "text" || value == "arraybuffer" || value == "json" || value == "moz-chunked-arraybuffer")
                field = value
        }

    @get:QuackProperty(name = "status")
    var status = 0
        private set

    @get:QuackProperty(name = "statusText")
    var statusText: String? = null
        private set

    @get:QuackProperty(name = "responseURL")
    var responseURL: String? = null
        private set

    @get:QuackProperty(name = "timeout")
    @set:QuackProperty(name = "timeout")
    var timeout = 0

    @get:QuackProperty(name = "onerror")
    @set:QuackProperty(name = "onerror")
    var onError: ErrorCallback? = null

    @get:QuackProperty(name = "onreadystatechange")
    @set:QuackProperty(name = "onreadystatechange")
    var onReadyStateChanged: Runnable? = null

    @get:QuackProperty(name = "onprogress")
    @set:QuackProperty(name = "onprogress")
    var onProgress: Runnable? = null

    private val headers = Headers()
    fun setRequestHeader(key: String, value: String) {
        headers.set(key, value)
    }

    fun send(requestData: Any?) {
        val request = AsyncHttpRequest(URI.create(url!!), method!!, headers = headers)
        client.eventLoop.async {
            try {
                val httpResponse = client.execute(request)

                status = httpResponse.code
                statusText = httpResponse.message
                responseURL = request.uri.toString()

                if (responseType == "moz-chunked-arraybuffer") {
                    val buffer: ByteBufferList = ByteBufferList()

                    readyState = 3
                    while (httpResponse.body != null && httpResponse.body!!(buffer)) {
                        notifyProgress(buffer.readByteBuffer())
                    }

                    response = ByteBuffer.allocate(0)
                    readyState = 4
                    notifyReadyStateChanged()
                }
                else {
                    val buffer: ByteBufferList
                    if (httpResponse.body != null) {
                        buffer = readAllBuffer(httpResponse.body!!)
                    }
                    else {
                        buffer = ByteBufferList()
                    }

                    if (responseType == "text")
                        responseText = buffer.readUtf8String()
                    else if (responseType == "json")
                        response = makeJson(buffer.readUtf8String())
                    else if (responseType == "arraybuffer")
                        response = buffer.readByteBuffer()

                    readyState = 4
                    notifyReadyStateChanged()
                }
            }
            catch (e: Exception) {
                readyState = 4
                notifyError(e)
            }
        }
    }

    fun getAllResponseHeaders(): String {
        val builder = StringBuilder()
        for (header in headers) {
            builder.append("${URLEncoder.encode(header.name)}: ${URLEncoder.encode(header.value)}\r\n")
        }
        builder.append("\r\n")
        return builder.toString()
    }

    private var method: String? = null
    private var url: String? = null

    fun abort() {

    }

    fun open(method: String, url: String, async: Boolean?, password: String?) {
        this.method = method;
        this.url = url
        readyState = 1
        notifyReadyStateChanged()
    }

    private fun notifyReadyStateChanged() = safeRun {
        onReadyStateChanged?.run()
    }


    private fun makeJson(json: String): JavaScriptObject {
        return context.evaluateForJavaScriptObject("(function(json) { return JSON.parse(json); })").callCoerced(JavaScriptObject::class.java, json)
    }
    private fun notifyError(exception: Exception) = safeRun {
        onError?.onError(context.newError(exception))
    }

    private fun notifyProgress(buffer: ByteBuffer) = safeRun {
        try {
            this.response = buffer
            onProgress?.run()
        }
        finally {
            this.response = null
        }
    }

    internal class Constructor(val context: QuackContext, val client: AsyncHttpClient) : QuackObject {
        override fun construct(vararg args: Any): Any {
            return XMLHttpRequest(context, client)
        }
    }
}