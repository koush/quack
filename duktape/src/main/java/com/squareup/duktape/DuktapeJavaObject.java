package com.squareup.duktape;

@SuppressWarnings("rawtypes")
public interface DuktapeJavaObject extends DuktapeObject {
    Object getObject(Class clazz);
}
