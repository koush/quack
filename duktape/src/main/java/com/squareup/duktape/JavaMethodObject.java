package com.squareup.duktape;

import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;

public class JavaMethodObject implements DuktapeMethodObject {
    String target;
    Duktape duktape;
    public JavaMethodObject(Duktape duktape, String method) {
        this.duktape = duktape;
        this.target = method;
    }

    protected Object getThis(Object thiz, Method method) {
        return thiz;
    }

    protected Method[] getMethods(Object thiz) {
        return thiz.getClass().getMethods();
    }

    @Override
    public Object callProperty(Object property, Object... args) {
        throw new UnsupportedOperationException("can not call property of a JavaMethodObject");
    }

    @Override
    public Object call(Object... args) {
        throw new UnsupportedOperationException("can not call JavaMethodObject with no 'this'");
    }

    @Override
    public Object callMethod(Object thiz, Object... args) {
        if (thiz == null)
            throw new UnsupportedOperationException("can not call " + target);
        thiz = duktape.coerceJavaScriptToJava(Object.class, thiz);
        int bestScore = Integer.MAX_VALUE;
        Method best = null;
        for (Method method: getMethods(thiz)) {
            if (method.getName().equals(target)) {
                // parameter count is most important
                int score = Math.abs(args.length - method.getParameterTypes().length) * 100;
                // tiebreak by checking parameter types
                for (int i = 0; i < Math.min(method.getParameterTypes().length, args.length); i++) {
                    if (method.getParameterTypes()[i].isInstance(args[i]))
                        score--;
                }
                if (score < bestScore) {
                    bestScore = score;
                    best = method;
                }
            }
        }

        if (best == null)
            throw new UnsupportedOperationException("can not call " + target);

        thiz = getThis(thiz, best);

        try {
            Method interfaceMethod = Duktape.getInterfaceMethod(best);
            DuktapeMethodCoercion methodCoercion = duktape.JavaScriptToJavaMethodCoercions.get(interfaceMethod);
            if (methodCoercion != null)
                return methodCoercion.invoke(interfaceMethod, thiz, args);

            int numParameters = best.getParameterTypes().length;
            if (best.isVarArgs())
                numParameters--;
            ArrayList<Object> coerced = new ArrayList<>();
            int i = 0;
            for (; i < numParameters; i++) {
                if (i < args.length)
                    coerced.add(duktape.coerceJavaScriptToJava(best.getParameterTypes()[i], args[i]));
                else
                    coerced.add(null);
            }
            if (best.isVarArgs()) {
                Class varargType = best.getParameterTypes()[numParameters].getComponentType();
                ArrayList<Object> varargs = new ArrayList<>();
                for (; i < args.length; i++) {
                    varargs.add(duktape.coerceJavaScriptToJava(varargType, args[i]));
                }
                coerced.add(varargs.toArray());
            }
            else if (i < args.length) {
                Log.w("Duktape", "dropping javascript to java arguments on the floor: " + (args.length - i));
            }
            return duktape.coerceJavaToJavascript(best.invoke(thiz, coerced.toArray()));
        }
        catch (IllegalAccessException e) {
            throw new IllegalArgumentException(e);
        }
        catch (InvocationTargetException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
