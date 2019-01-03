package com.squareup.duktape;

public interface DuktapeObject {
    Object get(String key);
    Object get(int index);
    Object get(Object key);

    void set(String key, Object value);
    void set(int index, Object value);
    void set(Object key, Object value);

    /**
     * Call this object with the expectation that it is a function. The this argument
     * is implicit to the runtime.
     * @param args
     * @return
     */
    Object call(Object... args);

    /**
     * Call this object with the expectation that it is a function. The this argument
     * is provided.
     * @param thiz
     * @param args
     * @return
     */
    Object callMethod(Object thiz, Object... args);

    /**
     * Call the property of this object with the expectation that it is a function.
     * The this argument is the the instance holding the property.
     * @param property
     * @param args
     * @return
     */
    Object callProperty(Object property, Object... args);
}
