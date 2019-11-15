package com.koushikdutta.quack;

public interface QuackReadonlyObject extends QuackObject {
    default boolean set(String key, Object value) {
        return set((Object)key, value);
    }

    default boolean set(int index, Object value) {
        return set((Object)index, value);
    }

    default boolean set(Object key, Object value) {
        throw new UnsupportedOperationException("can not set property on a JavaObject");
    }
}
