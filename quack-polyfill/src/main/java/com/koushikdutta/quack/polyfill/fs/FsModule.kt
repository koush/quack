package com.koushikdutta.quack.polyfill.fs

import com.koushikdutta.quack.JavaScriptObject
import com.koushikdutta.quack.QuackProperty
import com.koushikdutta.quack.polyfill.QuackEventLoop
import com.koushikdutta.quack.polyfill.jsonCoerce
import com.koushikdutta.quack.polyfill.require.Modules


class Stats {

}

class Constants {
    @get:QuackProperty(name = "READONLY")
    val READONLY = 0
    @get:QuackProperty(name = "READWRITE")
    val READWRITE = 1
}

class FsModule(quackLoop: QuackEventLoop, modules: Modules) {
    @get:QuackProperty(name = "constants")
    val constants = Constants()

    fun statSync(file: String, options: JavaScriptObject?): Stats? {
//        throw Exception()
        return Stats()
    }
}