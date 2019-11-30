package com.koushikdutta.quack.polyfill.job

import com.koushikdutta.quack.JavaScriptObject
import com.koushikdutta.quack.polyfill.QuackEventLoop
import com.koushikdutta.scratch.event.Cancellable
import java.util.*

fun QuackEventLoop.installJobScheduler() {
    val loop = loop
    val ctx = quack
    val global = ctx.globalObject
    run {
        val timeouts = Hashtable<Any, Cancellable>()
        global["setTimeout"] = ctx.coerceJavaToJavaScript(SetTimeout::class.java, object : SetTimeout {
            var timeoutCount = 0
            override fun execute(cb: JavaScriptObject?, milliseconds: Int, vararg params: Any): Int {
                if (cb == null) return -1
                val myTimeout = timeoutCount++
                val canceller = loop.postDelayed(milliseconds.toLong()) {
                    timeouts.remove(myTimeout)
                    cb.call(*params)
                }
                timeouts[myTimeout] = canceller
                return myTimeout
            }
        })
        global.set("clearTimeout", ctx.coerceJavaToJavaScript(ClearTimeout::class.java, ClearTimeout { timeout -> ClearTimeout.clear(timeout, timeouts) }))
    }
    run {
        val timeouts = Hashtable<Any, Cancellable>()
        global["setImmediate"] = ctx.coerceJavaToJavaScript(SetImmediate::class.java, object : SetImmediate {
            var timeoutCount = 0
            override fun execute(cb: JavaScriptObject?, vararg params: Any): Int {
                if (cb == null) return -1
                val myTimeout = timeoutCount++
                val canceller = loop.postImmediate {
                    timeouts.remove(myTimeout)
                    cb.call(*params)
                }
                timeouts[myTimeout] = canceller
                return myTimeout
            }
        })
        global.set("clearImmediate", ctx.coerceJavaToJavaScript(ClearTimeout::class.java, ClearTimeout { timeout -> ClearTimeout.clear(timeout, timeouts) }))
    }
    run {
        val timeouts = Hashtable<Any, Cancellable>()
        global["setInterval"] = ctx.coerceJavaToJavaScript(SetTimeout::class.java, object : SetTimeout {
            var timeoutCount = 0
            override fun execute(cb: JavaScriptObject?, milliseconds: Int, vararg params: Any): Int {
                if (cb == null) return -1
                val myTimeout = timeoutCount++
                val schedule: Runnable = object : Runnable {
                    override fun run() {
                        val canceller = loop.postDelayed(milliseconds.toLong()) {
                            // reschedule first because the interval may cancel itself after.
                            run()
                            cb.call(*params)
                        }
                        timeouts[myTimeout] = canceller
                    }
                }
                schedule.run()
                return myTimeout
            }
        })
        global.set("clearInterval", ctx.coerceJavaToJavaScript(ClearTimeout::class.java, ClearTimeout { timeout -> ClearTimeout.clear(timeout, timeouts) }))
    }
    ctx.setJobExecutor { runnable: Runnable ->
        loop.post {
            runnable.run()
        }
    }
}
