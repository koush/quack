package com.squareup.duktape;

public interface DuktapeJavaObject extends DuktapeObject {
    Object getObject(Class clazz);
}
