package com.squareup.duktape;

public interface DuktapeObject extends DuktapeFunction {
    Object get(String key);
    Object get(int index);
    Object get(Object key);
    Object call(Object... args);
}
