package com.koushikdutta.quack.polyfill

import com.koushikdutta.quack.JavaScriptObject
import com.koushikdutta.quack.QuackContext

interface EventListener {
    operator fun invoke(vararg args: Any)
}

interface EventEmitter {
    fun emit(eventName: String, vararg args: Any?)
    fun on(eventName: String, listener: EventListener)
    fun on(eventName: String, listener: JavaScriptObject)
    fun once(eventName: String, listener: EventListener)
    fun once(eventName: String, listener: JavaScriptObject)
}

fun EventEmitter.on(eventName: String, listener: (args: Array<out Any>) -> Unit) {
    this.on(eventName, object : EventListener {
        override fun invoke(vararg args: Any) {
            listener(args)
        }
    })
}


fun EventEmitter.emitError(quackLoop: QuackEventLoop, throwable: Throwable, cb: JavaScriptObject? = null) {
    try {
        val err = quackLoop.quack.newError(throwable)
        if (cb != null)
            cb.call(err)
        else
            emit("error", err)
    }
    catch (unhandled: Throwable) {
        quackLoop.quack.unhandled(unhandled)
    }
}

fun QuackContext.unhandled(unhandled: Throwable) {
    unhandled.printStackTrace()
    println("unhandled error $unhandled")
}

fun JavaScriptObject.callSafely(quackLoop: QuackEventLoop, vararg arguments: Any?) {
    try {
        call(*arguments)
    }
    catch (unhandled: Throwable) {
        quackLoop.quack.unhandled(unhandled)
    }
}

fun EventEmitter.emitSafely(quackLoop: QuackEventLoop, event: String, vararg arguments: Any?) {
    try {
        emit(event, *arguments)
    }
    catch (unhandled: Throwable) {
        quackLoop.quack.unhandled(unhandled)
    }
}