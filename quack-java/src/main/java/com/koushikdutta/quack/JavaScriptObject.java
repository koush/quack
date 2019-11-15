package com.koushikdutta.quack;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;

@SuppressWarnings({"unchecked", "rawtypes"})
public class JavaScriptObject implements QuackObject {
    final public QuackContext quackContext;
    public final long context;
    final public long pointer;
    public JavaScriptObject(QuackContext quackContext, long context, long pointer) {
        this.quackContext = quackContext;
        this.context = context;
        this.pointer = pointer;
    }

    public String stringify() {
        return quackContext.stringify(pointer);
    }

    @Override
    public Object get(String key) {
        return quackContext.getKeyString(pointer, key);
    }

    @Override
    public Object get(int index) {
        return quackContext.getKeyInteger(pointer, index);
    }

    @Override
    public Object call(Object... args) {
        quackContext.coerceJavaArgsToJavaScript(args);
        return quackContext.coerceJavaScriptToJava(null, quackContext.call(pointer, args));
    }

    @Override
    public Object callMethod(Object thiz, Object... args) {
        quackContext.coerceJavaArgsToJavaScript(args);
        return quackContext.coerceJavaScriptToJava(null, quackContext.callMethod(pointer, thiz, args));
    }

    @Override
    public Object callProperty(Object property, Object... args) {
        quackContext.coerceJavaArgsToJavaScript(args);
        return quackContext.coerceJavaScriptToJava(null, quackContext.callProperty(pointer, property, args));
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

        return quackContext.getKeyObject(pointer, key);
    }

    @Override
    public boolean set(String key, Object value) {
        return quackContext.setKeyString(pointer, key, value);
    }

    @Override
    public boolean set(int index, Object value) {
        return quackContext.setKeyInteger(pointer, index, value);
    }

    @Override
    public boolean set(Object key, Object value) {
        if (key instanceof String) {
            return set((String)key, value);
        }

        if (key instanceof Number) {
            Number number = (Number)key;
            if (number.doubleValue() == number.intValue()) {
                return set(number.intValue(), value);
            }
        }

        return quackContext.setKeyObject(pointer, key, value);
    }

    @Override
    public String toString() {
        Object ret = callProperty("toString");
        if (ret == null)
            return null;
        return ret.toString();
    }

    public InvocationHandler createInvocationHandler() {
        InvocationHandler handler = (proxy, method, args) -> {
            Method interfaceMethod = QuackContext.getInterfaceMethod(method);
            QuackMethodCoercion methodCoercion = quackContext.JavaToJavascriptMethodCoercions.get(interfaceMethod);
            if (methodCoercion != null)
                return methodCoercion.invoke(interfaceMethod, this, args);

            String methodName = method.getName();
            QuackMethodName annotation = method.getAnnotation(QuackMethodName.class);
            if (annotation != null)
                methodName = annotation.name();

            if (args != null) {
                Class[] types = method.getParameterTypes();
                for (int i = 0; i < Math.min(args.length, types.length); i++) {
                    args[i] = quackContext.coerceJavaToJavaScript(types[i], args[i]);
                }
            }

            return quackContext.coerceJavaScriptToJava(method.getReturnType(), JavaScriptObject.this.callProperty(methodName, args));
        };

        return quackContext.getWrappedInvocationHandler(this, handler);
    }

    public <T> T proxyInterface(Class<T> clazz, Class... more) {
        ArrayList<Class> classes = new ArrayList<>();
        classes.add(clazz);
        if (more != null)
            Collections.addAll(classes, more);

        return (T)Proxy.newProxyInstance(clazz.getClassLoader(), classes.toArray(new Class[0]), createInvocationHandler());
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        quackContext.finalizeJavaScriptObject(pointer);
    }
}
