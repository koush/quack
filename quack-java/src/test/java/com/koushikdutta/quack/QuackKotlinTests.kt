package com.koushikdutta.quack

import org.junit.Assert
import org.junit.Test
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class QuackKotlinTests {
    companion object {
        init {
            // for non-android jvm
            try {
                System.load(File("quack-jni/build/lib/main/debug/libquack-jni.dylib").canonicalPath);
            }
            catch (e: Error) {
            }
            try {
                System.load(File("../quack-jni/build/lib/main/debug/libquack-jni.dylib").canonicalPath);
            }
            catch (e: Error) {
            }
        }
    }

    internal interface ArrayTypeInterface {
        fun foo(): Int
    }

    internal interface ArrayInterface {
        val numbers: Array<ArrayTypeInterface>
    }

    @Test
    fun testArray() {
        val quack = QuackContext.create()
        val iface = quack.evaluate("(function() { return [() => 2, () => 3, () => 4, () => 5] })", ArrayInterface::class.java)
        var total = 0
        for (i in iface.numbers) {
            total += i.foo()
        }
        Assert.assertEquals(total.toLong(), 14)
        quack.close()
    }

    @Test
    fun testPromise() {
        val quack = QuackContext.create();

        val script = "new Promise((resolve, reject) => { resolve('hello'); });"
        val promise = quack.evaluate(script, QuackPromise::class.java)
//        val promise = jo.proxyInterface(QuackPromise::class.java)

        var ret = "world"
        val suspendFun = suspend {
            ret = promise.await() as String
        }

        suspendFun.startCoroutine(Continuation(EmptyCoroutineContext) {
        })

        assert(ret == "hello")
    }
}