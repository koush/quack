package com.squareup.duktape;

public class JavaFunction implements DuktapeObject {
    DuktapeFunction function;
    public JavaFunction(DuktapeFunction function) {
        this.function = function;
    }
    @Override
    public Object get(String key) {
        return get((Object)key);
    }

    @Override
    public Object get(int index) {
        return get((Object)index);
    }

    @Override
    public Object get(Object key) {
        throw new UnsupportedOperationException("can not get + " + key + " on a JavaMethodObject");
    }

    @Override
    public Object call(Object... args) {
        return invoke(null, args);
    }

    void coerceArgs(Class[] parameterTypes, Object... args) {
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                args[i] = Duktape.coerceToJava(args[i], parameterTypes != null && i < parameterTypes.length ? parameterTypes[i] : null);
            }
        }
    }

    @Override
    public Object invoke(Object thiz, Object... args) {
        thiz = Duktape.coerceToJava(thiz, null);
        coerceArgs(getParameterTypes(), args);
        return Duktape.coerceToJavascript(function.invoke(thiz, args));
    }
}
