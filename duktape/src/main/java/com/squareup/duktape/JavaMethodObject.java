package com.squareup.duktape;

import android.util.Log;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;

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
        Method[] methods = thiz.getClass().getMethods();
        if (!(thiz instanceof Class))
            return methods;

        ArrayList<Method> arr = new ArrayList<>();
        Collections.addAll(arr, methods);
        Collections.addAll(arr, ((Class)thiz).getMethods());
        return arr.toArray(new Method[0]);
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

        Method[] thisMethods = getMethods(thiz);
        ArrayList<Class> argTypes = new ArrayList<>();
        for (Object arg: args) {
            if (arg == null)
                argTypes.add(null);
            else
                argTypes.add(arg.getClass());
        }
        Method best = Duktape.javaObjectMethodCandidates.memoize(() -> {
            Method ret = null;
            int bestScore = Integer.MAX_VALUE;
            for (Method method: thisMethods) {
                if (!method.getName().equals(target)) {
                    DuktapeMethodName annotation = method.getAnnotation(DuktapeMethodName.class);
                    if (annotation == null || !annotation.name().equals(target))
                        continue;
                }
                // parameter count is most important
                int score = Math.abs(argTypes.size() - method.getParameterTypes().length) * 100;
                // tiebreak by checking parameter types
                for (int i = 0; i < Math.min(method.getParameterTypes().length, argTypes.size()); i++) {
                    if (argTypes.get(i) == null || method.getParameterTypes()[i].isAssignableFrom(argTypes.get(i)))
                        score--;
                }
                if (score < bestScore) {
                    bestScore = score;
                    ret = method;
                }
            }
            return ret;
        }, target, thisMethods, argTypes.toArray());

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
                coerced.add(toArray(varargType, varargs));
            }
            else if (i < args.length) {
                Log.w("Duktape", "dropping javascript to java arguments on the floor: " + (args.length - i));
            }
            return duktape.coerceJavaToJavaScript(best.invoke(thiz, coerced.toArray()));
        }
        catch (IllegalAccessException e) {
            throw new IllegalArgumentException(best.toString(), e);
        }
        catch (InvocationTargetException e) {
            if (e.getTargetException() instanceof RuntimeException)
                throw (RuntimeException)e.getTargetException();
            throw new IllegalArgumentException(best.toString(), e);
        }
    }

    static private <T> T[] toArray(Class<T> varargType, ArrayList<T> varargs) {
        return varargs.toArray((T[])Array.newInstance(varargType, 0));
    }
}
