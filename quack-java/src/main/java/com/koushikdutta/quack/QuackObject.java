package com.koushikdutta.quack;

public interface QuackObject {
    default Object get(Object key) {
        return null;
    }

    default boolean set(Object key, Object value) {
        return false;
    }

    default boolean has(Object key) {
        return get(key) != null;
    }

    /**
     * Call this object with the expectation that it is a function. The this argument
     * is provided.
     * @param thiz
     * @param args
     * @return
     */
    default Object callMethod(Object thiz, Object... args) {
        throw new UnsupportedOperationException();
    }

    /**
     * Construct this object with the expectation that it is a function. The this argument
     * is provided.
     * @param args
     * @return
     */
    default Object construct(Object... args) {
        throw new UnsupportedOperationException();
    }
}
