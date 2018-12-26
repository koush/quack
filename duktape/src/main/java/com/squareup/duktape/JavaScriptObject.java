package com.squareup.duktape;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;

public class JavaScriptObject implements DuktapeObject {
    final public Duktape duktape;
    public final long context;
    final public long pointer;
    public JavaScriptObject(Duktape duktape, long context, long pointer) {
        this.duktape = duktape;
        this.context = context;
        this.pointer = pointer;
    }

    @Override
    public Object get(String key) {
        return duktape.getKeyString(pointer, key);
    }

    @Override
    public Object get(int index) {
        return duktape.getKeyInteger(pointer, index);
    }

    private void coerceArgs(Object... args) {
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                args[i] = Duktape.coerceToJavascript(args[i]);
            }
        }
    }

    @Override
    public Object call(Object... args) {
        coerceArgs(args);
        return Duktape.coerceToJava(duktape.callSelf(pointer, args), null);
    }

    @Override
    public Object invoke(Object property, Object... args) {
        coerceArgs(args);
        return Duktape.coerceToJava(duktape.callProperty(pointer, property, args), null);
    }

    @Override
    public Object get(Object key) {
        if (key instanceof String)
            return get((String)key);

        if (key instanceof Number) {
            Number number = (Number)key;
            if (((Integer)number.intValue()).equals(number))
                return get(number.intValue());
        }

        return duktape.getKeyObject(pointer, key);
    }

    @Override
    public String toString() {
        Object ret = invoke("toString");
        if (ret == null)
            return null;
        return ret.toString();
    }

    public InvocationHandler createInvocationHandler() {
        return (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class)
                return method.invoke(JavaScriptObject.this, args);
            return Duktape.coerceToJava(JavaScriptObject.this.invoke(method.getName(), args), method.getReturnType());
        };
    }

    public <T> T proxyInterface(Class<T> clazz, Class... more) {
        ArrayList<Class> classes = new ArrayList<>();
        classes.add(clazz);
        if (more != null)
            Collections.addAll(classes, more);

        return (T)Proxy.newProxyInstance(clazz.getClassLoader(), classes.toArray(new Class[0]), createInvocationHandler());
    }
}
