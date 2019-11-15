package com.koushikdutta.quack;

import java.lang.reflect.InvocationHandler;

public interface QuackInvocationHandlerWrapper {
    InvocationHandler wrapInvocationHandler(JavaScriptObject javaScriptObject, InvocationHandler handler);
}
