package com.squareup.duktape;

public interface DuktapeFunction {
    default Class getReturnType() {
        return null;
    }
    default Class[] getParameterTypes() {
        return null;
    }

    Object invoke(Object thiz, Object... args);
}
