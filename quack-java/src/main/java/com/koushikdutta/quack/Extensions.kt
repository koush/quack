package com.koushikdutta.quack

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

suspend fun QuackPromise.await(): Any {
    return suspendCoroutine<Any> { resume ->
        this.then {
            resume.resume(it)
        }.caught {
            try {
                if (it !is JavaScriptObject)
                    throw QuackException("JavaScript Error type not thrown")
                val jo: JavaScriptObject = it
                jo.quackContext.evaluateForJavaScriptObject("(function(t) { throw t; })").call(it);
            }
            catch (e: Throwable) {
                resume.resumeWithException(e)
            }
        }
    }
}
