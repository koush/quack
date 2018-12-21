package com.squareup.duktape;

import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;

public class JavaMethodObject extends JavaObject<String> {
    public JavaMethodObject(String method) {
        super(method);
    }

    @Override
    public Object callProperty(Object thiz, Object... args) {
        if (thiz == null)
            throw new UnsupportedOperationException("can not call " + target);
        thiz = ((JavaObject)thiz).target;
        int bestScore = Integer.MAX_VALUE;
        Method best = null;
        for (Method method: thiz.getClass().getMethods()) {
            if (method.getName().equals(target)) {
                int score = Math.abs(args.length - method.getParameterTypes().length);
                if (score < bestScore) {
                    bestScore = score;
                    best = method;
                }
            }
        }

        if (best == null)
            throw new UnsupportedOperationException("can not call " + target);
        try {
            int numParameters = best.getParameterTypes().length;
            if (best.isVarArgs())
                numParameters--;
            ArrayList<Object> coerced = new ArrayList<>();
            int i = 0;
            for (; i < numParameters; i++) {
                if (i < args.length)
                    coerced.add(Duktape.coerceToJava(args[i], best.getParameterTypes()[i]));
                else
                    coerced.add(null);
            }
            if (best.isVarArgs()) {
                Class varargType = best.getParameterTypes()[numParameters].getComponentType();
                ArrayList<Object> varargs = new ArrayList<>();
                for (; i < args.length; i++) {
                    varargs.add(Duktape.coerceToJava(args[i], varargType));
                }
                coerced.add(varargs.toArray());
            }
            else if (i < args.length) {
                Log.w("Duktape", "dropping javascript to java arguments on the floor: " + (args.length - i));
            }
            return best.invoke(thiz, coerced.toArray());
        }
        catch (IllegalAccessException e) {
            throw new IllegalArgumentException(e);
        }
        catch (InvocationTargetException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public Object call(Object... args) {
        throw new UnsupportedOperationException("can not call " + target);
    }
}
