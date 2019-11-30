package com.koushikdutta.quack.polyfill.require;

import com.koushikdutta.quack.JavaScriptObject;

@FunctionalInterface
public interface RequireFactory {
    Require create(ReadFile readFile, EvalScript evalScript, JavaScriptObject global);
}
