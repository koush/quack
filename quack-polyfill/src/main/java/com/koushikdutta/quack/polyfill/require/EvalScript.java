package com.koushikdutta.quack.polyfill.require;

import com.koushikdutta.quack.JavaScriptObject;

@FunctionalInterface
public interface EvalScript {
    JavaScriptObject evalScript(String script, String filename);
}
