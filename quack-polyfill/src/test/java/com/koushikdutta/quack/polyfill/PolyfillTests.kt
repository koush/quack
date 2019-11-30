package com.koushikdutta.quack.polyfill

import com.koushikdutta.quack.JavaScriptObject
import com.koushikdutta.quack.QuackContext
import com.koushikdutta.quack.polyfill.net.NetModule
import com.koushikdutta.quack.polyfill.require.EvalScript
import com.koushikdutta.quack.polyfill.require.Modules
import com.koushikdutta.quack.polyfill.require.ReadFile
import com.koushikdutta.quack.polyfill.require.installModules
import com.koushikdutta.quack.polyfill.xmlhttprequest.XMLHttpRequest
import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.OK
import com.koushikdutta.scratch.http.body.Utf8StringBody
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import org.junit.Test
import java.io.File
import java.io.PrintStream
import kotlin.random.Random


class PolyfillTests {
    companion object {
        init {
            // for non-android jvm
            try {
                System.load(File("quack-jni/build/lib/main/debug/libquack-jni.dylib").getCanonicalPath());
            }
            catch (e: Error) {
                try {
                    System.load(File("../quack-jni/build/lib/main/debug/libquack-jni.dylib").getCanonicalPath());
                }
                catch (e: Error) {
                    throw AssertionError("jni load failed")
                }
            }
        }

        private fun QuackContext.loadModules(): Modules {
            val baseDir = File(".", "src/js")

            return installModules(ReadFile {
                val file = baseDir.resolve(it)
                if (!file.exists() || !file.isFile)
                    null
                else
                    StreamUtility.readFile(file)
            }, EvalScript { script, filename ->
                val file = baseDir.resolve(filename).canonicalPath
//                println("evaluating $file")
                evaluate(script, file) as JavaScriptObject
            })
        }
    }


    interface XCallback {
        fun onX(x: XMLHttpRequest);
    }

//    @Test
//    fun testUdp() {
//        val quackLoop = QuackEventLoop()
//        quackLoop.installJobScheduler()
//        quackLoop.quack.waitForDebugger("localhost:6666")
//
//        val modules = quackLoop.quack.loadModules()
//        modules.installCoreModule("dgram", DgramModule(quackLoop, modules))
//        val udpFile =  "/Volumes/Dev/quackfill/quack-polyfill/node/udp.js"
//        modules.require(udpFile)
//
//        quackLoop.loop.run()
//    }
    class Console(var quack: QuackContext, var out: PrintStream, var err: PrintStream) {
        fun getLog(vararg objects: Any?): String {
            val b = StringBuilder()
            for (o in objects) {
                if (o == null) b.append("null") else b.append(o.toString())
            }
            return b.toString()
        }

        fun log(vararg objects: Any?) {
            out.println(getLog(*objects))
        }

        fun error(vararg objects: Any?) {
            err.println(getLog(*objects))
        }

        fun warn(vararg objects: Any?) {
            err.println(getLog(*objects))
        }

        fun debug(vararg objects: Any?) {
            err.println(getLog(*objects))
        }

        fun info(vararg objects: Any?) {
            err.println(getLog(*objects))
        }

        fun assert(vararg objects: Any?) {
            err.println(getLog(*objects))
        }
    }

//    @Test
//    fun testTorrentServe() {
//        val quackLoop = QuackEventLoop()
//        quackLoop.installJobScheduler()
////        quackLoop.quack.waitForDebugger("localhost:6666")
//        quackLoop.quack.globalObject.set("console", Console(quackLoop.quack, System.out, System.err))
//
//        val modules = quackLoop.quack.loadModules()
//        modules.installCoreModule("dgram", DgramModule(quackLoop, modules))
//        modules.installCoreModule("net", NetModule(quackLoop, modules))
//        modules.installCoreModule("tls", TlsModule(quackLoop, modules))
//        modules.installCoreModule("fs", FsModule(quackLoop, modules))
//        modules.installCoreModule("crypto", CryptoModule(quackLoop, modules))
//        modules.installCoreModule("dns", DnsModule(quackLoop, modules))
//        val client = AsyncHttpClient(quackLoop.loop)
//        quackLoop.quack.globalObject.set("XMLHttpRequest", XMLHttpRequest.Constructor(quackLoop.quack, client))
//
//        val chunkStore = modules.require("memory-chunk-store")
//        val webtorrentOptions = quackLoop.quack.evaluateForJavaScriptObject("({})")
//        webtorrentOptions.set("store", chunkStore)
//
//        quackLoop.quack.putJavaToJsonCoersion(TorrentReadStreamOptions::class.java)
//
//        val router = AsyncHttpRouter()
//        val server = AsyncHttpServer(router::handle)
//
//        quackLoop.loop.async {
//            val serverSocket = listen()
//            server.listen(serverSocket)
//            println("http://localhost:${serverSocket.localPort}/")
//
//            val phantomMenance = "magnet:?xt=urn:btih:f105dd901e63e3319c2b259b055fbb6e08a65ab5&dn=Star+Wars%3A+Episode+I+-+The+Phantom+Menace+%281999%29+1080p+BrRip+x26&tr=udp%3A%2F%2Ftracker.leechers-paradise.org%3A6969&tr=udp%3A%2F%2Fzer0day.ch%3A1337&tr=udp%3A%2F%2Fopen.demonii.com%3A1337&tr=udp%3A%2F%2Ftracker.coppersurfer.tk%3A6969&tr=udp%3A%2F%2Fexodus.desync.com%3A6969"
//            val sintel = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel&tr=udp%3A%2F%2Fexplodie.org%3A6969&tr=udp%3A%2F%2Ftracker.coppersurfer.tk%3A6969&tr=udp%3A%2F%2Ftracker.empire-js.us%3A1337&tr=udp%3A%2F%2Ftracker.leechers-paradise.org%3A6969&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337&tr=wss%3A%2F%2Ftracker.btorrent.xyz&tr=wss%3A%2F%2Ftracker.fastcast.nz&tr=wss%3A%2F%2Ftracker.openwebtorrent.com&ws=https%3A%2F%2Fwebtorrent.io%2Ftorrents%2F&xs=https%3A%2F%2Fwebtorrent.io%2Ftorrents%2Fsintel.torrent"
//            val webTorrent = modules.require("webtorrent").constructCoerced(WebTorrent::class.java)
//            webTorrent.add(sintel,
//                    webtorrentOptions) { torrent ->
//                val jo = torrent.javaScriptObject
//                val pieces = jo.get("pieces") as JavaScriptObject
//                val numPieces = pieces.get("length") as Int
////                torrent.deselect(0, numPieces - 1, false)
////                torrent.pause()
//                (jo.get("files") as JavaScriptObject).forOf(TorrentFile::class.java) {
//                    println(it.name)
////                    it.deselect()
//
//                    println("http://localhost:${serverSocket.localPort}/${it.name}")
//                    router.get("/${it.name}") { request, match ->
////                        it.select()
////                        torrent.resume()
//                        val range = request.headers.get("range")
//                        val totalLength = it.length.toLong()
//                        var start = 0L
//                        var end: Long = totalLength - 1L
//                        var code = 200
//
//                        val headers = Headers()
//                        if (range != null) {
//                            var parts = range.split("=").toTypedArray()
//                            // Requested range not satisfiable
//                            if (parts.size != 2 || "bytes" != parts[0])
//                                return@get AsyncHttpResponse(ResponseLine(416, "Not Satisfiable", "HTTP/1.1"))
//
//                            parts = parts[1].split("-").toTypedArray()
//                            try {
//                                if (parts.size > 2) throw IllegalArgumentException()
//                                if (!parts[0].isEmpty()) start = parts[0].toLong()
//                                end = if (parts.size == 2 && !parts[1].isEmpty()) parts[1].toLong() else totalLength - 1
//                                code = 206
//                                headers.set("Content-Range", "bytes $start-$end/$totalLength")
//                            } catch (e: java.lang.Exception) {
//                                return@get AsyncHttpResponse(ResponseLine(416, "Not Satisfiable", "HTTP/1.1"))
//                            }
//                        }
//
//
//                        val options = TorrentReadStreamOptions()
//                        options.start = start.toInt()
//                        options.end = end.toInt()
//                        headers.set("Content-Length", it.length.toString())
//                        headers.set("Accept-Ranges", "bytes")
//
//                        val stream = it.createReadStream(options)
//                        if (code == 200)
//                            AsyncHttpResponse.OK(body = BinaryBody(stream.createAsyncRead(quackLoop.quack)), headers = headers)
//                        else
//                            AsyncHttpResponse(body = BinaryBody(stream.createAsyncRead(quackLoop.quack)), headers = headers, responseLine = ResponseLine(code, "Partial Content", "HTTP/1.1"))
//                    }
//                    println("done")
//                }
//            }
//
//        }
//
////        val torrentFile =  "/Volumes/Dev/quackfill/quack-polyfill/node/wt.js"
////        modules.require(torrentFile)
//
//        quackLoop.loop.run()
//    }


    @Test
    fun testSocket() {
        val quackLoop = QuackEventLoop()
        val modules = quackLoop.installDefaultModules(quackLoop.quack.loadModules())

        var data = ""
        quackLoop.loop.async {
            val serverSocket = listen()
            serverSocket.acceptAsync {
                makeJunkRead().copy(::write)
                close()
            }

            try {
                val socket = (modules["net"] as NetModule).createConnection(serverSocket.localPort).proxyInterface(Stream::class.java)
                data = socket.createAsyncRead(quackLoop.quack).digest()
                quackLoop.loop.stop()
            }
            catch (throwable: Throwable) {
                println(throwable)
            }
        }

        quackLoop.loop.run()
        assert(data == "1a1640ee9890e4539525aa8cdcb5d8f8")
    }

    @Test
    fun testHttp() {
        val quackLoop = QuackEventLoop()
        val modules = quackLoop.installDefaultModules(quackLoop.quack.loadModules())
        val server = AsyncHttpServer {
            AsyncHttpResponse.OK(body = Utf8StringBody("hello world"))
        }

        var data = ""
        quackLoop.loop.async {
            val serverSocket = listen()
            val port = serverSocket.localPort
            server.listen(serverSocket)

            val cb: XCallback = object : XCallback {
                override fun onX(x: XMLHttpRequest) {
                    data = x.responseText!!
                    if (x.readyState == 4)
                        quackLoop.loop.stop()
                }
            }

            val scriptString = "(function(cb) { var x = new XMLHttpRequest(); x.open('GET', 'http://localhost:$port'); x.onreadystatechange = () => cb(x); x.send(); })"
            quackLoop.quack.evaluateForJavaScriptObject(scriptString).call(quackLoop.quack.coerceJavaToJavaScript(XCallback::class.java, cb));

        }

        quackLoop.loop.postDelayed(5000) {
            throw AssertionError("timeout")
        }
        quackLoop.loop.run()

        assert(data == "hello world")
    }

    @Test
    fun testStreams() {
        val quackLoop = QuackEventLoop()
        val modules = quackLoop.installDefaultModules(quackLoop.quack.loadModules())

        val readableClass = modules.require("stream").get("Readable") as JavaScriptObject

        val junkRead = makeJunkRead()
        val customStreamClass = mixinExtend(quackLoop.quack, readableClass, ReadableStream::class.java, Readable::class.java, "CustomStream") { stream, arguments ->
            object : BaseReadable {
                override val quackLoop = quackLoop
                override val stream = stream
                override var pauser: Cooperator? = null
                override suspend fun getAsyncRead(): AsyncRead = junkRead

                override fun _destroy(err: Any?, callback: JavaScriptObject?) {
                }
            }
        }

        val stream = customStreamClass.constructCoerced(Stream::class.java)

        var digest = ""
        quackLoop.loop.async {
            digest = stream.createAsyncRead(quackLoop.quack).digest()
            quackLoop.loop.stop()
        }

        quackLoop.loop.run()
        assert(digest == "1a1640ee9890e4539525aa8cdcb5d8f8")
    }

    fun makeJunkRead(): AsyncRead {
        var readCount = 0
        val random = Random(55555)
        return read@{
            if (readCount > 50)
                return@read false

            readCount++

            it.putBytes(random.nextBytes(10000))

            true
        }
    }

    @Test
    fun testEvents() {
        val quackLoop = QuackEventLoop()
        val modules = quackLoop.installDefaultModules(quackLoop.quack.loadModules())
        val events = modules.require("events")
        val eventEmitter = events.constructCoerced(EventEmitter::class.java)
        var eventData = ""
        eventEmitter.on("test") {
            eventData = it[0].toString()
        }
        eventEmitter.emit("test", "hello world")

        assert(eventData == "hello world")
    }

    @Test
    fun testSetTimeout() {
        val quackLoop = QuackEventLoop()
        val jo = quackLoop.quack.evaluateForJavaScriptObject("(function(loop) { setTimeout(() => loop.stop(), 0) })")
        jo.call(quackLoop.loop);
        quackLoop.loop.run();
    }

    @Test
    fun testSetImmediate() {
        val quackLoop = QuackEventLoop()
        val jo = quackLoop.quack.evaluateForJavaScriptObject("(function(loop) { setImmediate(() => loop.stop()) })")
        jo.call(quackLoop.loop);
        quackLoop.loop.run();
    }
}
