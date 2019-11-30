package com.koushikdutta.quack.polyfill

import com.koushikdutta.quack.JavaScriptObject
import com.koushikdutta.quack.QuackContext
import com.koushikdutta.quack.QuackMethodObject
import com.koushikdutta.quack.QuackProperty
import java.lang.StringBuilder

internal fun <Super, T> mixinExtend(context: QuackContext,
                           superClassObject: JavaScriptObject,
                           superClazz: Class<Super>,
                           mixin: Class<T>,
                           className: String,
                           superArgumentsFactory: ((arguments: Array<out Any?>) -> Any?)? = null,
                           factory: (superInstance: Super, arguments: Array<out Any?>) -> T): JavaScriptObject {
    val builder = StringBuilder()
    builder.appendln("(function(factory, superArgumentsFactory, SuperClass) {")

    builder.appendln("class $className extends SuperClass {")
    builder.appendln("    constructor() {")
    if (superArgumentsFactory == null)
        builder.appendln("        super();")
    else
        builder.appendln("        super(...superArgumentsFactory(arguments));")
    builder.appendln("        this._internal = factory.apply(this, arguments);")
    builder.appendln("    }")


    for (method in mixin.methods) {
        var methodName = method.name
        val quackProperty: QuackProperty? = method.getAnnotation(QuackProperty::class.java)
        if (quackProperty != null) {
            if (method.parameterCount != 0) {
                methodName = "set ${quackProperty.name}"
                builder.appendln("${methodName}(value) {")
                builder.appendln("    return this._internal.${method.name}.apply(this._internal, [value]);")
                builder.appendln("}")
                continue
            }
            methodName = "get ${quackProperty.name}"
        }
        builder.appendln("${methodName}() {")
        builder.appendln("    return this._internal.${method.name}.apply(this._internal, arguments);")
        builder.appendln("}")
    }

    builder.appendln("}")

    builder.appendln("return $className;")
    builder.appendln("})")

    val factoryShim = object : QuackMethodObject {
        override fun callMethod(thiz: Any?, vararg args: Any?): Any {
            return factory(context.coerceJavaScriptToJava(superClazz, thiz) as Super, args) as Any
        }
    }

    val argumentsShim: QuackMethodObject?
    if (superArgumentsFactory == null) {
        argumentsShim = null
    }
    else {
        argumentsShim = object : QuackMethodObject {
            override fun callMethod(thiz: Any?, vararg args: Any?): Any? {
                return superArgumentsFactory!!(args)
            }
        }
    }

    return context.evaluateForJavaScriptObject(builder.toString()).call(factoryShim, argumentsShim, superClassObject) as JavaScriptObject
}

internal fun <T> JavaScriptObject.getMixin(clazz: Class<T>): T {
    return quackContext.coerceJavaScriptToJava(clazz, get("_internal")) as T
}