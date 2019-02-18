/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.duktape;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/** A simple EMCAScript (Javascript) interpreter. */
public final class Duktape implements Closeable {
  private final Map<Class, DuktapeCoercion> JavaScriptToJavaCoercions = new LinkedHashMap<>();
  private final Map<Class, DuktapeCoercion> JavaToJavascriptCoercions = new LinkedHashMap<>();
  final Map<Method, DuktapeMethodCoercion> JavaScriptToJavaMethodCoercions = new LinkedHashMap<>();
  final Map<Method, DuktapeMethodCoercion> JavaToJavascriptMethodCoercions = new LinkedHashMap<>();

  static {
    System.loadLibrary("duktape");
  }

  /**
   * Register a function that coerces values JavaScript values into an object of type
   * {@code clazz} before being passed along to Java.
   */
  public synchronized <T> void putJavaScriptToJavaCoercion(Class<T> clazz, DuktapeCoercion<T, Object> coercion) {
    JavaScriptToJavaCoercions.put(clazz, coercion);
  }

  /**
   * Register a function that coerces Java values of type {@code clazz} into a JavaScript object
   * before being passed along to Duktape.
   * @param clazz
   * @param coercion
   * @param <F>
   */
  public synchronized <F> void putJavaToJavascriptCoercion(Class<F> clazz, DuktapeCoercion<Object, F> coercion) {
    JavaToJavascriptCoercions.put(clazz, coercion);
  }

  /**
   * Coerce a Java value into an equivalent JavaScript object.
   */
  public Object coerceJavaToJavascript(Object o) {
    if (o == null)
      return null;
    return coerceJavaToJavascript(o.getClass(), o);
  }

  /**
   * Coerce a Java value into an equivalent JavaScript object.
   */
  public Object coerceJavaToJavascript(Class clazz, Object o) {
    if (o == null)
      return null;
    Object ret = coerce(JavaToJavascriptCoercions, o, clazz);
    if (ret != null)
      return ret;


    if (clazz.isInterface() && clazz.getMethods().length == 1) {
      // automatically coerce functional interfaces into functions
      Method method = clazz.getMethods()[0];
      return new DuktapeMethodObject() {
        @Override
        public Object callMethod(Object thiz, Object... args) {
          try {
            if (args != null) {
              Class[] parameters = method.getParameterTypes();
              for (int i = 0; i < args.length; i++) {
                args[i] = coerceJavaScriptToJava(parameters[i], args[i]);
              }
            }
            return coerceJavaToJavascript(method.invoke(o, args));
          }
          catch (Exception e) {
            throw new IllegalArgumentException(e);
          }
        }
      };
    }

    return o;
  }

  /**
   * Coerce a JavaScript value into an equivalent Java object.
   */
  public Object coerceJavaScriptToJava(Class<?> clazz, Object o) {
    if (o == null)
      return null;
    while (o instanceof DuktapeJavaObject) {
      Object coerced = ((DuktapeJavaObject)o).getObject(clazz);;
      if (o == coerced)
        break;
      o = coerced;
    }
    if (clazz == null)
      return o;
    if (clazz.isInstance(o))
      return o;

    // unbox needs no coercion.
    if (clazz == boolean.class && o instanceof Boolean)
      return o;
    if (clazz == byte.class && o instanceof Byte)
      return o;
    if (clazz == short.class && o instanceof Short)
      return o;
    if (clazz == int.class && o instanceof Integer)
      return o;
    if (clazz == long.class && o instanceof Long)
      return o;
    if (clazz == float.class && o instanceof Float)
      return o;
    if (clazz == double.class && o instanceof Double)
      return o;

    // javascript only uses doubles.
    if ((clazz == byte.class || clazz == Byte.class) && o instanceof Double)
      return ((Double)o).byteValue();
    if ((clazz == short.class || clazz == Short.class) && o instanceof Double)
      return ((Double)o).shortValue();
    if ((clazz == int.class || clazz == Integer.class) && o instanceof Double)
      return ((Double)o).intValue();
    if ((clazz == float.class || clazz == Float.class) && o instanceof Double)
      return ((Double)o).floatValue();
    if ((clazz == long.class || clazz == Long.class) && o instanceof Double)
      return ((Double)o).longValue();

    if (clazz.isArray() && o instanceof JavaScriptObject) {
      JavaScriptObject jo = (JavaScriptObject)o;
      int length = ((Number)jo.get("length")).intValue();
      Class componentType = clazz.getComponentType();
      Object ret = Array.newInstance(componentType, length);
      for (int i = 0; i < length; i++) {
        Array.set(ret, i, coerceJavaScriptToJava(componentType, jo.get(i)));
      }
      return ret;
    }

    Object ret = coerce(JavaScriptToJavaCoercions, o, clazz);
    if (ret != null)
      return ret;

    // convert javascript objects proxy objects that implement interfaces
    if (clazz.isInterface() && o instanceof JavaScriptObject) {
      JavaScriptObject jo = (JavaScriptObject)o;

      if (clazz.getMethods().length == 1) {
        return Proxy.newProxyInstance(clazz.getClassLoader(), new Class[]{clazz}, (proxy, method, args) -> jo.call(args));
      }
      else {
        InvocationHandler handler = jo.createInvocationHandler();
        return Proxy.newProxyInstance(clazz.getClassLoader(), new Class[] { clazz }, handler);
      }
    }

    if (clazz == JSONObject.class && o instanceof JavaScriptObject) {
      try {
        return new JSONObject(((JavaScriptObject)o).stringify());
      }
      catch (JSONException e) {
        throw new IllegalArgumentException(e);
      }
    }

    return o;
  }

  public interface JavaMethodReference<T> {
    void invoke(T thiz);
  }
  public interface JavaMethodReference0<T, A> {
    void invoke(T thiz, A arg0);
  }
  public interface JavaMethodReference1<T, A, B> {
    void invoke(T thiz, A arg0, B arg1);
  }
  public interface JavaMethodReference2<T, A, B, C> {
    void invoke(T thiz, A arg0, B arg1, C arg2);
  }
  public interface JavaMethodReference3<T, A, B, C, D> {
    void invoke(T thiz, A arg0, B arg1, C arg2, D arg3);
  }
  public interface JavaMethodReference4<T, A, B, C, D, E> {
    void invoke(T thiz, A arg0, B arg1, C arg2, D arg3, E arg4);
  }

  public static <T> Method getInterfaceMethod(Class<T> clazz, JavaMethodReference<T> ref) {
    return invokeMethodReferenceProxy(clazz, ref);
  }
  public static <T, A> Method getInterfaceMethod(Class<T> clazz, JavaMethodReference0<T, A> ref) {
    return invokeMethodReferenceProxy(clazz, ref);
  }
  public static <T, A, B> Method getInterfaceMethod(Class<T> clazz, JavaMethodReference1<T, A, B> ref) {
    return invokeMethodReferenceProxy(clazz, ref);
  }
  public static <T, A, B, C> Method getInterfaceMethod(Class<T> clazz, JavaMethodReference2<T, A, B, C> ref) {
    return invokeMethodReferenceProxy(clazz, ref);
  }
  public static <T, A, B, C, D> Method getInterfaceMethod(Class<T> clazz, JavaMethodReference3<T, A, B, C, D> ref) {
    return invokeMethodReferenceProxy(clazz, ref);
  }
  public static <T, A, B, C, D, E> Method getInterfaceMethod(Class<T> clazz, JavaMethodReference4<T, A, B, C, D, E> ref) {
    return invokeMethodReferenceProxy(clazz, ref);
  }

  static Memoize<Method> interfaceMethods = new Memoize<>();
  static Method getInterfaceMethod(Method method) {
    return interfaceMethods.memoize((MemoizeFunc<Method>) () -> {
      if (method.getDeclaringClass().isInterface())
        return method;

      Class c = method.getDeclaringClass();
      for (Class iface: c.getInterfaces()) {
        for (Method m: iface.getDeclaredMethods()) {
          if (m.getParameterTypes().length != method.getParameterTypes().length)
            continue;
          if (!m.getName().equals(method.getName()))
            continue;
          if (!m.getReturnType().isAssignableFrom(method.getReturnType()))
            continue;

          boolean paramMatch = true;
          for (int i = 0; i < method.getParameterTypes().length; i++) {
            if (!m.getParameterTypes()[i].isAssignableFrom(method.getParameterTypes()[i])) {
              paramMatch = false;
              break;
            }
          }

          if (paramMatch)
            return m;
        }
      }

      return null;
    }, method);
  }

  public synchronized void putJavaScriptToJavaMethodCoercion(Method method, DuktapeMethodCoercion coercion) {
    JavaScriptToJavaMethodCoercions.put(method, coercion);
    interfaceMethods.clear();
  }

  private static class MethodException extends Exception {
    Method method;
    MethodException(Method method) {
      this.method = method;
    }
  }

  private static Object throwInvokedMethod(Object proxy, Method method, Object[] args) throws MethodException {
    throw new MethodException(method);
  }

  private static <T> T createMethodInterceptProxy(Class<T> clazz) {
    return (T)Proxy.newProxyInstance(clazz.getClassLoader(), new Class[] { clazz }, Duktape::throwInvokedMethod);
  }

  private static <T> Method invokeMethodReferenceProxy(Class<T> clazz, Object ref) {
    try {
      if (ref.getClass().getDeclaredMethods().length != 1)
        throw new Exception("expecting lambda with 1 method: getInterfaceMethod(Foo.class, Foo::bar)");
      Method method = ref.getClass().getDeclaredMethods()[0];
      Object[] args = new Object[method.getParameterTypes().length];
      // first arg is "this" for the lambda
      args[0] = createMethodInterceptProxy(clazz);
      method.invoke(ref, args);
    }
    catch (Exception e) {
      if (e instanceof InvocationTargetException) {
        InvocationTargetException invocationTargetException = (InvocationTargetException)e;
        if (invocationTargetException.getTargetException() instanceof UndeclaredThrowableException) {
          UndeclaredThrowableException undeclaredThrowableException = (UndeclaredThrowableException)invocationTargetException.getTargetException();
          if (undeclaredThrowableException.getUndeclaredThrowable() instanceof MethodException) {
            return ((MethodException)undeclaredThrowableException.getUndeclaredThrowable()).method;
          }
        }
        else if (invocationTargetException.getTargetException() instanceof NullPointerException) {
          throw new IllegalArgumentException("lambdas with primitive arguments must be invoked with default values: getInterfaceMethod(Foo.class, thiz -> thiz.setInt(0))");
        }
      }
      throw new IllegalArgumentException(e);
    }
    throw new IllegalArgumentException("interface method was not called by lambda.");
  }

  private static Object coerce(Map<Class, DuktapeCoercion> coerce, Object o, Class<?> clazz) {
    DuktapeCoercion coercion = coerce.get(clazz);
    if (coercion != null) {
      return coercion.coerce(clazz, o);
    }

    // check to see if there exists a superclass converter.
    for (Map.Entry<Class, DuktapeCoercion> check: coerce.entrySet()) {
      if (clazz.isAssignableFrom(check.getKey()))
        return check.getValue().coerce(clazz, o);
    }

    // check to see if there is a subclass converter (ie, Enum.class as a catch all).
    for (Map.Entry<Class, DuktapeCoercion> check: coerce.entrySet()) {
      if (check.getKey().isAssignableFrom(clazz))
        return check.getValue().coerce(clazz, o);
    }

    return null;
  }

  /**
   * Create a new interpreter instance. Calls to this method <strong>must</strong> matched with
   * calls to {@link #close()} on the returned instance to avoid leaking native memory.
   */
  public static Duktape create() {
    Duktape duktape = new Duktape();
    // context will hold a weak ref, so this doesn't matter if it fails.
    long context = createContext(duktape);
    if (context == 0) {
      throw new OutOfMemoryError("Cannot create Duktape instance");
    }
    duktape.context = context;
    duktape.evaluate(
            "var __proxyHandler = {\n" +
                    "\thas: function(f, prop) { return prop == '__java_this' || f.target.__duktape_has(f.target, prop); },\n" +
                    "\tget: function(f, prop, receiver) { return f.target.__duktape_get(f.target, prop, receiver); },\n" +
                    "\tset: function(f, prop, value, receiver) { return f.target.__duktape_set(f.target, prop, value, receiver); },\n" +
                    "\tapply: function(f, thisArg, argumentsList) { return f.target.__duktape_apply(f.target, thisArg, argumentsList); },\n" +
                    "};\n" +
                    "function __makeProxy(obj) {\n" +
                    "\tfunction f() {};\n" +
                    "\tf.target = obj;\n" +
                    "\treturn new Proxy(f, __proxyHandler);\n" +
                    "};\n"
    );
    return duktape;
  }

  private long context;

  private Duktape() {
    // coercing javascript string into an enum for java
    JavaScriptToJavaCoercions.put(Enum.class, (DuktapeCoercion<Enum, Object>) (clazz, o) -> {
      if (o == null)
        return null;
      return Enum.valueOf(clazz, o.toString());
    });

    // coerce JavaScript Numbers. duktape supports ints and doubles natively.
    JavaScriptToJavaCoercions.put(Byte.class, (clazz, o) -> o instanceof Number ? ((Number)o).byteValue() : o);
    JavaScriptToJavaCoercions.put(byte.class, (clazz, o) -> o instanceof Number ? ((Number)o).byteValue() : o);
    JavaToJavascriptCoercions.put(byte.class, (DuktapeCoercion<Integer, Byte>) (clazz, o) -> o.intValue());
    JavaToJavascriptCoercions.put(Byte.class, (DuktapeCoercion<Integer, Byte>) (clazz, o) -> o.intValue());

    JavaScriptToJavaCoercions.put(Short.class, (clazz, o) -> o instanceof Number ? ((Number)o).shortValue() : o);
    JavaScriptToJavaCoercions.put(short.class, (clazz, o) -> o instanceof Number ? ((Number)o).shortValue() : o);
    JavaToJavascriptCoercions.put(short.class, (DuktapeCoercion<Integer, Short>) (clazz, o) -> o.intValue());
    JavaToJavascriptCoercions.put(Short.class, (DuktapeCoercion<Integer, Short>) (clazz, o) -> o.intValue());

    JavaScriptToJavaCoercions.put(Integer.class, (clazz, o) -> o instanceof Number ? ((Number)o).intValue() : o);
    JavaScriptToJavaCoercions.put(int.class, (clazz, o) -> o instanceof Number ? ((Number)o).intValue() : o);

    JavaScriptToJavaCoercions.put(Long.class, (clazz, o) -> o instanceof Number ? ((Number)o).longValue() : o);
    JavaScriptToJavaCoercions.put(long.class, (clazz, o) -> o instanceof Number ? ((Number)o).longValue() : o);

    // coercing a java enum into javascript string
    JavaToJavascriptCoercions.put(Enum.class, (DuktapeCoercion<Object, Enum>) (clazz, o) -> o.toString());
  }

  /**
   * Evaluate {@code script} and return a result. {@code fileName} will be used in error
   * reporting.
   *
   * @throws DuktapeException if there is an error evaluating the script.
   */
  public synchronized <T> T evaluate(String script, String fileName) {
    return evaluate(context, script, fileName);
  }

  /**
   * Evaluate {@code script} and return a result.
   *
   * @throws DuktapeException if there is an error evaluating the script.
   */
  public synchronized <T> T evaluate(String script) {
    return evaluate(script, "?");
  }

  /**
   * Evaluate {@code script} and return a result. {@code fileName} will be used in error
   * reporting. {@code fileName} will be used in error reporting.
   *
   * @throws DuktapeException if there is an error evaluating the script.
   */
  public synchronized JavaScriptObject evaluateForJavaScriptObject(String script, String fileName) {
    return evaluate(context, script, fileName);
  }

  /**
   * Evaluate {@code script} and return a result. The result must be a JavaScript object
   * or a ClassCastException will be thrown.
   *
   * @throws DuktapeException if there is an error evaluating the script.
   */
  public synchronized JavaScriptObject evaluateForJavaScriptObject(String script) {
    return evaluateForJavaScriptObject(script, "?");
  }

  /**
   * Compile a JavaScript function and return of JavaScriptObject as the resulting function.
   *
   * @throws DuktapeException if there is an error evaluating the script.
   */
  public synchronized JavaScriptObject compileFunction(String script, String fileName) {
    return compileFunction(context, script, fileName);
  }

  /**
   * Release the native resources associated with this object. You <strong>must</strong> call this
   * method for each instance to avoid leaking native memory.
   */
  @Override public synchronized void close() {
    if (context != 0) {
      long contextToClose = context;
      context = 0;
      destroyContext(contextToClose);
    }
  }

  @Override protected synchronized void finalize() throws Throwable {
    // this isn't THAT bad, as JavaScriptObjects may be passed around without concern for the
    // Duktape collection.
    if (context != 0) {
      Logger.getLogger(getClass().getName()).warning("Duktape instance leaked!");
    }
    // definitely close it though.
    close();
  }

  public synchronized void setGlobalProperty(Object property, Object value) {
    setGlobalProperty(context, property, value);
  }

  /**
   * Notify any attached debugger to process pending any debugging requests. When
   * cooperateDebuger is invoked by the caller, the caller must ensure no calls into
   * the Duktape during that time.
   */
  public synchronized void cooperateDebugger() {
    cooperateDebugger(context);
  }

  /**
   * Wait for a debugging connection on port 9091.
   */
  public void waitForDebugger() {
    waitForDebugger(context);
  }

  /**
   * Check if a debugger is currently attached.
   */
  public boolean isDebugging() {
    return isDebugging(context);
  }

  /**
   * Send an custom app notification to any connected debugging client.
   * @param args
   */
  public synchronized void debuggerAppNotify(Object... args) {
    debuggerAppNotify(context, args);
  }

  synchronized Object getKeyObject(long object, Object key) {
    return getKeyObject(context, object, key);
  }
  synchronized Object getKeyString(long object, String key) {
    return getKeyString(context, object, key);
  }
  synchronized Object getKeyInteger(long object, int index) {
    return getKeyInteger(context, object, index);
  }
  synchronized void setKeyObject(long object, Object key, Object value) {
    setKeyObject(context, object, key, value);
  }
  synchronized void setKeyString(long object, String key, Object value) {
    setKeyString(context, object, key, value);
  }
  synchronized void setKeyInteger(long object, int index, Object value) {
    setKeyInteger(context, object, index, value);
  }
  synchronized Object call(long object, Object... args) {
    return call(context, object, args);
  }
  synchronized Object callMethod(long object, Object thiz, Object... args) {
    return callMethod(context, object, thiz, args);
  }
  synchronized Object callProperty(long object, Object property, Object... args) {
    return callProperty(context, object, property, args);
  }
  synchronized String stringify(long object) {
      return stringify(context, object);
  }
  synchronized void finalizeJavaScriptObject(long object) {
    finalizeJavaScriptObject(context, object);
  }

  private static native long createContext(Duktape duktape);
  private static native void destroyContext(long context);
  private static native <T> T evaluate(long context, String sourceCode, String fileName);
  private static native JavaScriptObject compileFunction(long context, String sourceCode, String fileName);

  private static native void cooperateDebugger(long context);
  private static native void waitForDebugger(long context);
  private static native boolean isDebugging(long context);
  private static native void debuggerAppNotify(long context, Object... args);
  private static native Object getKeyObject(long context, long object, Object key);
  private static native Object getKeyString(long context, long object, String key);
  private static native Object getKeyInteger(long context, long object, int index);
  private static native void setKeyObject(long context, long object, Object key, Object value);
  private static native void setKeyString(long context, long object, String key, Object value);
  private static native void setKeyInteger(long context, long object, int index, Object value);
  private static native Object call(long context, long object, Object... args);
  private static native Object callMethod(long context, long object, Object thiz, Object... args);
  private static native Object callProperty(long context, long object, Object property, Object... args);
  private static native void setGlobalProperty(long context, Object property, Object value);
  private static native String stringify(long context, long object);
  private static native void finalizeJavaScriptObject(long context, long object);
}
