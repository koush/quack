package com.koushikdutta.quack;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

@SuppressWarnings({"unchecked", "rawtypes"})
public class JavaScriptObject implements QuackObject, QuackJavaScriptObject {
    final public QuackContext quackContext;
    public final long context;
    final public long pointer;
    public JavaScriptObject(QuackContext quackContext, long context, long pointer) {
        this.quackContext = quackContext;
        this.context = context;
        this.pointer = pointer;
    }

    @Override
    public long getNativePointer() {
        return pointer;
    }

    @Override
    public long getNativeContext() {
        return context;
    }

    @Override
    public JavaScriptObject getJavaScriptObject() {
        return this;
    }

    @Override
    public JavaScriptObject construct(Object... args) {
        return constructCoerced(JavaScriptObject.class, args);
    }

    public <T> T constructCoerced(Class<T> clazz, Object... args) {
        quackContext.coerceJavaArgsToJavaScript(args);
        return (T)quackContext.coerceJavaScriptToJava(clazz, quackContext.callConstructor(pointer, args));
    }

    public String typeof() {
        return (String)quackContext.evaluateForJavaScriptObject("(function(f) { return typeof f; })").call(this);
    }

    public String stringify() {
        return quackContext.stringify(pointer);
    }

    public Object get(String key) {
        return quackContext.coerceJavaScriptToJava(null, quackContext.getKeyString(pointer, key));
    }

    public Object get(int index) {
        return quackContext.coerceJavaScriptToJava(null, quackContext.getKeyInteger(pointer, index));
    }

    public Object call(Object... args) {
        quackContext.coerceJavaArgsToJavaScript(args);
        return quackContext.coerceJavaScriptToJava(null, quackContext.call(pointer, args));
    }

    @Override
    public Object callMethod(Object thiz, Object... args) {
        quackContext.coerceJavaArgsToJavaScript(args);
        return quackContext.coerceJavaScriptToJava(null, quackContext.callMethod(pointer, quackContext.coerceJavaToJavaScript(thiz), args));
    }

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

        return quackContext.coerceJavaScriptToJava(null, quackContext.getKeyObject(pointer, quackContext.coerceJavaToJavaScript(key)));
    }

    public boolean set(String key, Object value) {
        return quackContext.setKeyString(pointer, key, value);
    }

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

    static Object[] coerceArgs(QuackContext quackContext, Method method, Object[] args) {
        if (args != null && args.length > 0) {
            Class[] types = method.getParameterTypes();

            if (args.length != types.length)
                throw new AssertionError("JavaScript.createInvocationHandler different args count?");

            int numParameters = types.length;
            if (method.isVarArgs())
                numParameters--;

            for (int i = 0; i < numParameters; i++) {
                args[i] = quackContext.coerceJavaToJavaScript(types[i], args[i]);
            }

            if (method.isVarArgs()) {
                Class varargType = method.getParameterTypes()[numParameters].getComponentType();
                ArrayList<Object> varargs = new ArrayList<>(Arrays.asList(args).subList(0, numParameters));
                Object varargArg = args[numParameters];
                for (int i = 0; i < Array.getLength(varargArg); i++) {
                    Object vararg = Array.get(varargArg, i);
                    varargs.add(quackContext.coerceJavaScriptToJava(varargType, vararg));
                }
                args = varargs.toArray();
            }
        }

        return args;
    }

    public InvocationHandler getWrappedInvocationHandler(InvocationHandler wrapped) {
        return quackContext.getWrappedInvocationHandler(this, (proxy, method, args) -> {
            if (method.getDeclaringClass() == QuackJavaScriptObject.class)
                return method.invoke(JavaScriptObject.this, args);

            return wrapped.invoke(proxy, method, args);
        });
    }

    public InvocationHandler createInvocationHandler() {
        InvocationHandler handler = (proxy, method, args) -> {
            Method interfaceMethod = QuackContext.getInterfaceMethod(method);
            QuackMethodCoercion methodCoercion = quackContext.JavaToJavascriptMethodCoercions.get(interfaceMethod);
            if (methodCoercion != null)
                return methodCoercion.invoke(interfaceMethod, this, args);

            QuackProperty property = method.getAnnotation(QuackProperty.class);
            if (property != null) {
                if (args == null || args.length == 0)
                    return quackContext.coerceJavaScriptToJava(method.getReturnType(), JavaScriptObject.this.get(property.name()));
                JavaScriptObject.this.set(property.name(), quackContext.coerceJavaScriptToJava(method.getParameterTypes()[0], args[0]));
                return null;
            }

            String methodName = method.getName();
            QuackMethodName annotation = method.getAnnotation(QuackMethodName.class);
            if (annotation != null)
                methodName = annotation.name();

            return quackContext.coerceJavaScriptToJava(method.getReturnType(), JavaScriptObject.this.callProperty(methodName, coerceArgs(quackContext, method, args)));
        };

        return getWrappedInvocationHandler(handler);
    }

    public <T> T proxyInterface(Class<T> clazz, Class... more) {
        ArrayList<Class> classes = new ArrayList<>();
        classes.add(QuackJavaScriptObject.class);
        classes.add(clazz);
        if (more != null)
            Collections.addAll(classes, more);

        return (T)Proxy.newProxyInstance(clazz.getClassLoader(), classes.toArray(new Class[0]), createInvocationHandler());
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        if (quackContext != null)
            quackContext.finalizeJavaScriptObject(pointer);
    }

    public JSValue asJSValue() {
        return new JSValue(quackContext, this);
    }
}
