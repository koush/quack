package com.koushikdutta.quack;

/**
 * Coerce a value passing through Duktape to the desired output class.
 */
@SuppressWarnings("rawtypes")
public interface QuackCoercion<T, F> {
  T coerce(Class clazz, F o);
}
