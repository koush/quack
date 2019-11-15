package com.koushikdutta.quack;

@SuppressWarnings("rawtypes")
public interface QuackJavaObject extends QuackObject {
    Object getObject(Class clazz);
}
