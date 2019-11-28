package com.koushikdutta.quack;

public interface QuackJavaScriptObject {
    long getNativePointer();
    long getNativeContext();
    JavaScriptObject getJavaScriptObject();
}
