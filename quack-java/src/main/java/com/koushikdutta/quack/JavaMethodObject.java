package com.koushikdutta.quack;

//import android.util.Log;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;

@SuppressWarnings({"unchecked", "rawtypes"})
public class JavaMethodObject implements QuackMethodObject {
    String target;
    QuackContext quackContext;
    Object originalThis;
    public JavaMethodObject(QuackContext quackContext, Object originalThis, String method) {
        this.quackContext = quackContext;
        this.originalThis = originalThis;
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
    public Object callMethod(Object thiz, Object... args) {
        if (thiz == null || thiz instanceof JavaScriptObject)
            thiz = originalThis;
        if (thiz == null)
            throw new UnsupportedOperationException("can not call " + target);
        thiz = quackContext.coerceJavaScriptToJava(Object.class, thiz);

        Method[] thisMethods = getMethods(thiz);
        ArrayList<Class> argTypes = new ArrayList<>();
        for (Object arg: args) {
            if (arg == null)
                argTypes.add(null);
            else
                argTypes.add(arg.getClass());
        }
        Method best = QuackContext.javaObjectMethodCandidates.memoize(() -> {
            Method ret = null;
            int bestScore = Integer.MAX_VALUE;
            for (Method method: thisMethods) {
                if (!method.getName().equals(target)) {
                    QuackMethodName annotation = method.getAnnotation(QuackMethodName.class);
                    if (annotation == null || !annotation.name().equals(target))
                        continue;
                }
                // parameter count is most important
                int score = Math.abs(argTypes.size() - method.getParameterTypes().length) * 1000;
                // tiebreak by checking parameter types
                for (int i = 0; i < Math.min(method.getParameterTypes().length, argTypes.size()); i++) {
                    // check if the class is assignable or both parameters are numbers
                    Class<?> argType = argTypes.get(i);
                    Class<?> paramType = method.getParameterTypes()[i];
                    if (paramType == argType) {
                        score -= 4;
                    }
                    if (QuackContext.isNumberClass(paramType) && QuackContext.isNumberClass(argType)) {
                        score -= 3;
                    }
                    else if ((paramType == Long.class || paramType == long.class) && argType == String.class) {
                        score -= 2;
                    }
                    else if (argType == null || paramType.isAssignableFrom(argType)) {
                        score -= 1;
                    }
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
            Method interfaceMethod = QuackContext.getInterfaceMethod(best);
            QuackMethodCoercion methodCoercion = quackContext.JavaScriptToJavaMethodCoercions.get(interfaceMethod);
            if (methodCoercion != null)
                return methodCoercion.invoke(interfaceMethod, thiz, args);

            int numParameters = best.getParameterTypes().length;
            if (best.isVarArgs())
                numParameters--;
            ArrayList<Object> coerced = new ArrayList<>();
            int i = 0;
            for (; i < numParameters; i++) {
                if (i < args.length)
                    coerced.add(quackContext.coerceJavaScriptToJava(best.getParameterTypes()[i], args[i]));
                else
                    coerced.add(null);
            }
            if (best.isVarArgs()) {
                Class varargType = best.getParameterTypes()[numParameters].getComponentType();
                ArrayList<Object> varargs = new ArrayList<>();
                for (; i < args.length; i++) {
                    varargs.add(quackContext.coerceJavaScriptToJava(varargType, args[i]));
                }
                coerced.add(toArray(varargType, varargs));
            }
            else if (i < args.length) {
                System.err.println("dropping javascript to java arguments on the floor: " + (args.length - i) + " " + best.toString());
            }
//            System.out.println(best.getDeclaringClass().getSimpleName() + "." + best.getName());
            return quackContext.coerceJavaToJavaScript(best.invoke(thiz, coerced.toArray()));
        }
        catch (RuntimeException e) {
            throw e;
        }
        catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        catch (InvocationTargetException e) {
            if (e.getTargetException() instanceof RuntimeException)
                throw (RuntimeException)e.getTargetException();
            if (e.getTargetException() instanceof Error)
                throw (Error)e.getTargetException();
            throw new RuntimeException(e.getTargetException());
        }
    }

    static <T> T[] toArray(Class<T> varargType, ArrayList<T> varargs) {
        return varargs.toArray((T[])Array.newInstance(varargType, 0));
    }
}
