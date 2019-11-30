package com.koushikdutta.quack.polyfill.require

import com.koushikdutta.quack.QuackContext

interface Modules {
    val require: Require
    operator fun set(name: String, module: Any)
    operator fun get(name: String): Any
}

fun QuackContext.installModules(readFile: ReadFile, evalScript: EvalScript): Modules {
    val modules = evalScript.evalScript(readFile.readFile("require.js"), "require.js")
    val requireFactory = coerceJavaScriptToJava(RequireFactory::class.java, modules) as RequireFactory
    val require = requireFactory.create(readFile, evalScript, globalObject)

    return object : Modules {
        override val require = require

        override fun set(name: String, module: Any) {
            evaluateForJavaScriptObject("(function(require, exports){require.cache['$name'] = { exports: exports }; })").call(require, module)
        }

        override fun get(name: String): Any {
            return coerceJavaScriptToJava(null, evaluateForJavaScriptObject("(function(require) { return require.cache['$name'].exports })").call(require))
        }
    }
}