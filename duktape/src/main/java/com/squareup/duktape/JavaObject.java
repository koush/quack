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

        try {
            // try to get fields
            for (Field field : clazz.getFields()) {
                if (field.getName().equalsIgnoreCase(key))
                    return field.get(target);
            }
        }
        catch (Exception e) {
        }

        try {
            // try to get methods
            for (Method method : clazz.getMethods()) {
                if (method.getName().equalsIgnoreCase(key))
                    return new JavaMethodObject(key);
            }
        }
        catch (Exception e) {
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

    @Override
    public Object call(Object... args) {
        throw new UnsupportedOperationException("can not call " + target);
    }

    @Override
    public Object invoke(Object thiz, Object... args) {
        if (thiz == null)
            return call(args);
        if (thiz instanceof String) {
            thiz = get((String)thiz);
            if (thiz instanceof DuktapeObject)
                return ((DuktapeObject)thiz).call(args);
        }
        throw new UnsupportedOperationException("can not call " + target);
    }
}
