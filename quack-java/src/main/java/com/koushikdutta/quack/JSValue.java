package com.koushikdutta.quack;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class JSValue implements QuackJavaObject {
    QuackContext quack;
    Object value;
    JSValue(QuackContext quack, Object value) {
        this.quack = quack;
        this.value = value;
    }

    @Override
    public Object getObject() {
        return value;
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

    public <T> Iterable<T> asIterable(Class<T> clazz) {
        JSValue iteratorSymbol = quack.evaluateForJavaScriptObject("Symbol").asJSValue().get("iterator");
        JSValue iteratorFunc = get(iteratorSymbol);
        JSValue iterator = iteratorFunc.apply(this);
        JSValue iteratorNext = iterator.get("next");
        return () -> new Iterator<T>() {
            JSValue current;

            private void maybeNext() {
                if (current != null)
                    return;
                current = iteratorNext.apply(iterator);
            }

            @Override
            public boolean hasNext() {
                maybeNext();
                return !(Boolean)current.get("done").getObject();
            }

            @Override
            public T next() {
                maybeNext();
                if ((Boolean)current.get("done").getObject())
                    throw new NoSuchElementException("end of iterator");
                T ret = current.get("value").as(clazz);
                current = null;
                return ret;
            }
        };
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
