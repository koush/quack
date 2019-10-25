package com.koushikdutta.duktape

import com.squareup.duktape.Duktape
import org.junit.Test
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

interface TestInterface {
    suspend fun doThing(): Int
}

class Poops {
    suspend fun doThing(): Int {
        suspendCoroutine<Int> {  }
        return 0
    }
}

class DuktapeTests {
    @Test
    fun testThing() {
        System.load(File("../duktape-jni/build/lib/main/debug/libduktape-jni.dylib").absolutePath)

        val duktape = Duktape.create()
        val jo = duktape.evaluateForJavaScriptObject("({ doThing: function() { return 5555 }})")
        val ret = jo.proxyInterface(TestInterface::class.java)
        val fff : suspend() -> Unit = {
            val data = ret.doThing()
            println(data)
        }
        fff.startCoroutine(Continuation(EmptyCoroutineContext) {
            println(it)
        })
        println(ret)
        duktape.close()
        println("OK")
    }
}
