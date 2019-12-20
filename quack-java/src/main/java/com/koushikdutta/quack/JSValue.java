package com.koushikdutta.quack;

import java.nio.ByteBuffer;

public class JSValue {
    QuackContext quack;
    Object value;
    JSValue(QuackContext quack, Object value) {
        this.quack = quack;
        this.value = value;
    }

    public boolean isNumber() {
        return value instanceof Number;
    }

    public boolean isString() {
        return value instanceof String;
    }

    public boolean isJavaScriptObject() {
        return value instanceof JavaScriptObject;
    }

    public boolean isByteBuffer() {
        return value instanceof ByteBuffer;
    }

    public boolean isNullOrUndefined() {
        return value == null;
    }

    public <T> T as(Class<T> clazz) {
        return (T)quack.coerceJavaScriptToJava(clazz, value);
    }

    private QuackObject quackify() {
        if (value instanceof QuackObject)
            return (QuackObject)value;
        return new JavaObject(quack, value);
    }

    public JSValue get(Object key) {
        return new JSValue(quack, quackify().get(key));
    }

    public boolean set(Object key, Object value) {
        return quackify().set(key, value);
    }

    public boolean has(Object key) {
        return quackify().has(key);
    }

    public JSValue apply(Object thiz, Object... args) {
        return new JSValue(quack, quackify().callMethod(thiz, args));
    }

    public JSValue construct(Object... args) {
        return new JSValue(quack, quackify().construct(args));
    }

    @Override
    public String toString() {
        if (value == null)
            return "null";
        return value.toString();
    }
}
