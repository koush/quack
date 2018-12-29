package com.squareup.duktape;

public interface DuktapeObject {
    Object get(String key);
    Object get(int index);
    Object get(Object key);

    void set(String key, Object value);
    void set(int index, Object value);
    void set(Object key, Object value);

    Object call(Object... args);
    Object invoke(Object property, Object... args);
}
