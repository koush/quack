package com.squareup.duktape;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static com.squareup.duktape.Duktape.isEmpty;

public final class JavaObject implements DuktapeJavaObject {
    private final Object target;
    private final Duktape duktape;

    public JavaObject(Duktape duktape, Object target) {
        this.duktape = duktape;
        this.target = target;
    }
    @Override
    public Object getObject(Class clazz) {
        return target;
    }

    public static Method getGetterMethod(String key, Method[] methods) {
        return Duktape.javaObjectGetter.memoize(() -> {
            for (Method method : methods) {
                // name match, no args, and a return type
                if (method.getParameterTypes().length != 0)
                    continue;
                if (method.getReturnType() == void.class || method.getReturnType() == Void.class)
                    continue;
                DuktapeProperty duktapeProperty = method.getAnnotation(DuktapeProperty.class);
                if (duktapeProperty == null)
                    continue;
                String propName = duktapeProperty.name();
                if (isEmpty(propName))
                    propName = method.getName();
                if (propName.equals(key))
                    return method;
            }
            return null;
        }, key, methods);
    }

    public static Method getSetterMethod(String key, Method[] methods) {
        return Duktape.javaObjectSetter.memoize(() -> {
            for (Method method : methods) {
                // name match, no args, and a return type
                if (method.getParameterTypes().length != 1)
                    continue;
                if (method.getReturnType() != void.class && method.getReturnType() != Void.class)
                    continue;
                DuktapeProperty duktapeProperty = method.getAnnotation(DuktapeProperty.class);
                if (duktapeProperty == null)
                    continue;
                String propName = duktapeProperty.name();
                if (isEmpty(propName))
                    propName = method.getName();
                if (propName.equals(key))
                    return method;
            }
            return null;
        }, key, methods);
    }

    private static boolean hasMethod(Class clazz, String key, boolean requiresStatic) {
        // try to get methods
        for (Method method : clazz.getMethods()) {
            if (requiresStatic && !Modifier.isStatic(method.getModifiers()))
                continue;
            if (method.getName().equals(key))
                return true;
            DuktapeMethodName annotation = method.getAnnotation(DuktapeMethodName.class);
            if (annotation != null && annotation.name().equals(key))
                return true;
        }
        return false;
    }

    @Override
    public Object get(String key) {
        Object ret = getMap(key);
        if (ret != null)
            return ret;

        Class clazz = target.getClass();
        if (!Proxy.isProxyClass(clazz)) {
            // length is not a field of the array class. it's a language property.
            // Nor can arrays be cast to Array.
            if (clazz.isArray() && "length".equals(key))
                return Array.getLength(target);

            Field f = Duktape.javaObjectFields.memoize(() -> {
                // try to get fields
                for (Field field : clazz.getFields()) {
                    if (field.getName().equals(key))
                        return field;
                }
                return null;
            }, key, clazz.getFields());

            if (f != null) {
                try {
                    return duktape.coerceJavaToJavaScript(f.get(target));
                } catch (IllegalAccessException e) {
                    throw new IllegalArgumentException(e);
                }
            }
        }

        Method g = getGetterMethod(key, clazz.getMethods());
        if (g != null) {
            try {
                return duktape.coerceJavaToJavaScript(g.invoke(target));
            }
            catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
        }

        Boolean m = Duktape.javaObjectMethods.memoize(() -> {
            if (hasMethod(clazz, key, false))
                return true;
            if (target instanceof Class)
                return hasMethod((Class)target, key, true);
            return false;
        }, key, clazz.getMethods());

        if (m)
            return new JavaMethodObject(duktape, key);

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
            return duktape.coerceJavaToJavaScript(((Map)target).get(key));
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
    public boolean set(int index, Object value) {
        if (target instanceof Array) {
            Array.set(target, index, value);
            return true;
        }
        if (target instanceof List) {
            ((List)target).set(index, value);
            return true;
        }

        noSet();
        return false;
    }

    private boolean putMap(Object key, Object value) {
        if (target instanceof Map) {
            ((Map)target).put(key, value);
            return true;
        }
        noSet();
        return false;
    }

    @Override
    public boolean set(String key, Object value) {
        Class clazz = target.getClass();

        for (Field field: clazz.getFields()) {
            if (field.getName().equals(key)) {
                try {
                    field.set(target, duktape.coerceJavaScriptToJava(field.getType(), value));
                }
                catch (IllegalAccessException e) {
                    throw new IllegalArgumentException(e);
                }
                return true;
            }
        }

        Method s = getSetterMethod(key, clazz.getMethods());
        if (s != null) {
            try {
                duktape.coerceJavaToJavaScript(s.invoke(target, duktape.coerceJavaScriptToJava(s.getParameterTypes()[0], value)));
            }
            catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
            return true;
        }

        return putMap(key, value);
    }

    // duktape entry point
    @Override
    public boolean set(Object key, Object value) {
        if (key instanceof Number) {
            Number number = (Number)key;
            if (number.doubleValue() == number.intValue()) {
                return set(number.intValue(), value);
            }
        }

        if (key instanceof String) {
            return set((String)key, value);
        }

        return putMap(key, value);
    }

    @Override
    public Object call(Object... args) {
        throw new UnsupportedOperationException("can not call " + target);
    }

    @Override
    public Object callMethod(Object thiz, Object... args) {
        throw new UnsupportedOperationException("can not call " + target);
    }

    @Override
    public Object callProperty(Object property, Object... args) {
        if (property == null)
            throw new NullPointerException();
        property = get(property);
        if (property instanceof DuktapeObject)
            return ((DuktapeObject)property).callMethod(this, args);
        throw new UnsupportedOperationException("can not call " + target);
    }
}
