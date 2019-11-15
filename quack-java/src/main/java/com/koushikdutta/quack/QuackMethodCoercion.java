package com.koushikdutta.quack;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Coerce the arguments and return values of a method invocation passing through Duktape.
 */
public interface QuackMethodCoercion {
    Object invoke(Method method, Object target, Object... args) throws InvocationTargetException, IllegalAccessException;
}
