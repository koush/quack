package com.squareup.duktape;

import java.lang.reflect.InvocationHandler;

public interface DuktapeInvocationHandlerWrapper {
    InvocationHandler wrapInvocationHandler(JavaScriptObject javaScriptObject, InvocationHandler handler);
}
