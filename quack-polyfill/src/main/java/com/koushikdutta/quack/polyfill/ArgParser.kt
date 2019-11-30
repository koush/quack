package com.koushikdutta.quack.polyfill

import com.koushikdutta.quack.JavaScriptObject
import com.koushikdutta.quack.QuackContext


private val types = mutableMapOf<Class<*>, String>(
        Pair(Int::class.java, "number"),
        Pair(String::class.java, "string")
)


private fun Any.`typeof`(): String {
    if (this is Int)
        return "number"
    if (this is String)
        return "string"

    if (this !is JavaScriptObject)
        throw AssertionError("Unknown typeof")

    val jo = this as JavaScriptObject
    return jo.`typeof`()
}

internal class ArgParser(val quack: QuackContext, vararg val arguments: Any?) {
    var index = 0
    operator fun <T> invoke(type: String): T? {
        if (index >= arguments.size)
            return null

        val arg = arguments[index]
        if (arg == null)
            return null

        if (arg.`typeof`() != type)
            return null

        index++
        return arg as T
    }

    operator fun <T> invoke(clazz: Class<T>): T? {
        if (index >= arguments.size)
            return null

        var arg = arguments[index]
        if (arg == null) {
            index++
            return null
        }

        val coerced = quack.coerceJavaScriptToJavaOrNull(clazz, arg)
        if (coerced == null)
            return null

        index++
        return coerced as T
    }
}
