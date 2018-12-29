package com.squareup.duktape;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

public final class JavaObject implements DuktapeJavaObject {
    private final Object target;

    public JavaObject(Object target) {
        this.target = target;
    }

    @Override
    public Object getObject() {
        return target;
    }

    @Override
    public Object get(String key) {
        Object ret = getMap(key);
        if (ret != null)
            return ret;

        Class clazz = target.getClass();

        // length is not a field of the array class. it's a language property.
        // Nor can arrays be cast to Array.
        if (clazz.isArray() && "length".equals(key))
            return Array.getLength(target);

        // try to get fields
        for (Field field : clazz.getFields()) {
            if (field.getName().equalsIgnoreCase(key)) {
                try {
                    return field.get(target);
                }
                catch (IllegalAccessException e) {
                    throw new IllegalArgumentException(e);
                }
            }
        }

        // try to get methods
        for (Method method : clazz.getMethods()) {
            if (method.getName().equalsIgnoreCase(key))
                return new JavaMethodObject(key);
        }

        return null;
    }

    @Override
    public Object get(int index) {
        if (target.getClass().isArray())
            return Array.get(target, index);
        if (target instanceof List)
            return ((List)target).get(index);
        return null;
    }

    private Object getMap(Object key) {
        if (target instanceof Map)
            return ((Map)target).get(key);
        return null;
    }

    // duktape entry point
    @Override
    public Object get(Object key) {
        if (key instanceof String)
            return get((String)key);

        if (key instanceof Number) {
            Number number = (Number)key;
            if (number.doubleValue() == number.intValue())
                return get(number.intValue());
        }

        return getMap(key);
    }

    private void noSet() {
        throw new UnsupportedOperationException("can not set value on this JavaObject");
    }

    @Override
    public void set(int index, Object value) {
        if (target instanceof Array) {
            Array.set(target, index, value);
            return;
        }
        if (target instanceof List) {
            ((List)target).set(index, value);
            return;
        }

        noSet();
    }


    private void putMap(Object key, Object value) {
        if (target instanceof Map) {
            ((Map)target).put(key, value);
            return;
        }
        noSet();
    }

    @Override
    public void set(String key, Object value) {
        for (Field field: target.getClass().getFields()) {
            if (field.getName().equals(key)) {
                try {
                    field.set(target, Duktape.coerceJavaScriptToJava(value, field.getType()));
                }
                catch (IllegalAccessException e) {
                    throw new IllegalArgumentException(e);
                }
                return;
            }
        }

        putMap(key, value);
    }

    // duktape entry point
    @Override
    public void set(Object key, Object value) {
        if (key instanceof Number) {
            Number number = (Number)key;
            if (number.doubleValue() == number.intValue()) {
                set(number.intValue(), value);
                return;
            }
        }

        if (key instanceof String) {
            set((String)key, value);
            return;
        }

        putMap(key, value);
    }

    @Override
    public Object call(Object... args) {
        throw new UnsupportedOperationException("can not call " + target);
    }

    @Override
    public Object invoke(Object property, Object... args) {
        if (property == null)
            return call(args);
        if (property instanceof String) {
            property = get((String)property);
            if (property instanceof DuktapeObject)
                return ((DuktapeObject)property).call(args);
        }
        throw new UnsupportedOperationException("can not call " + target);
    }
}
