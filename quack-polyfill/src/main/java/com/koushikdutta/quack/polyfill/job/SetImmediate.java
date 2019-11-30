package com.koushikdutta.quack.polyfill.job;

import com.koushikdutta.quack.JavaScriptObject;

@FunctionalInterface
public interface SetImmediate {
    int execute(JavaScriptObject cb, Object... params);
}

