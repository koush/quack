package com.squareup.duktape;

public interface DuktapeReadonlyObject extends DuktapeObject {
    default void set(String key, Object value) {
        set((Object)key, value);
    }

    default void set(int index, Object value) {
        set((Object)index, value);
    }

    default void set(Object key, Object value) {
        throw new UnsupportedOperationException("can not set property on a JavaObject");
    }
}
