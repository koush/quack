package com.squareup.duktape;

public interface DuktapeObject {
    Object get(String key);
    Object get(int index);
    Object get(Object key);
    Object call(Object... args);
    Object callProperty(Object thiz, Object... args);
}
