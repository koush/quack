package com.squareup.duktape;

public interface DuktapeObject {
    default Object get(String key) {
        return null;
    };
    default Object get(int index) {
        return null;
    }
    default Object get(Object key) {
        return null;
    }

    default boolean set(String key, Object value) {
        return false;
    }
    default boolean set(int index, Object value) {
        return false;
    }
    default boolean set(Object key, Object value) {
        return false;
    }

    /**
     * Call this object with the expectation that it is a function. The this argument
     * is implicit to the runtime.
     * @param args
     * @return
     */
    default Object call(Object... args) {
        throw new UnsupportedOperationException();
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
     * Call the property of this object with the expectation that it is a function.
     * The this argument is the the instance holding the property.
     * @param property
     * @param args
     * @return
     */
    default Object callProperty(Object property, Object... args) {
        throw new UnsupportedOperationException();
    }
}
