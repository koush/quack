package com.koushikdutta.quack.polyfill.require

import com.koushikdutta.quack.JavaScriptObject

interface Require {
    operator fun invoke(moduleName: String): JavaScriptObject
}