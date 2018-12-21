package com.squareup.duktape;

public class JavaScriptObject implements DuktapeObject {
    final public Duktape duktape;
    final public long pointer;
    public JavaScriptObject(Duktape duktape, long pointer) {
        this.duktape = duktape;
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
        return duktape.callSelf(pointer, args);
    }

    @Override
    public Object invoke(Object thiz, Object... args) {
        coerceArgs(args);
        return duktape.callProperty(pointer, thiz, args);
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
}
