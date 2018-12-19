package com.squareup.duktape;

public class JavaScriptObject implements DuktapeObject {
    final public long context;
    final public long pointer;
    public JavaScriptObject(long context, long pointer) {
        this.context = context;
        this.pointer = pointer;
    }

    @Override
    public Object get(String key) {
        return Duktape.getKeyString(context, pointer, key);
    }

    @Override
    public Object get(int index) {
        return Duktape.getKeyInteger(context, pointer, index);
    }

    @Override
    public Object call(Object... args) {
        return Duktape.callSelf(context, pointer, args);
    }

    @Override
    public Object callProperty(Object thiz, Object... args) {
        return Duktape.callProperty(context, pointer, thiz, args);
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

        return Duktape.getKeyObject(context, pointer, key);
    }
}
