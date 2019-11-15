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
package com.koushikdutta.quack;

import java.io.Closeable;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/** A simple EMCAScript (Javascript) interpreter. */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class QuackContext implements Closeable {
  private final Map<Class, QuackCoercion> JavaScriptToJavaCoercions = new LinkedHashMap<>();
  private final Map<Class, QuackCoercion> JavaToJavascriptCoercions = new LinkedHashMap<>();
  final Map<Method, QuackMethodCoercion> JavaScriptToJavaMethodCoercions = new LinkedHashMap<>();
  final Map<Method, QuackMethodCoercion> JavaToJavascriptMethodCoercions = new LinkedHashMap<>();
  private QuackInvocationHandlerWrapper invocationHandlerWrapper;

  static {
    try {
      System.loadLibrary("quack");
    }
    catch (UnsatisfiedLinkError err) {
    }
  }

  static boolean isEmpty(String str) {
    return str == null || str.length() == 0;
  }

  static boolean isNumberClass(Class<?> c) {
    return c == byte.class || c == Byte.class || c == short.class || c == Short.class || c == int.class || c == Integer.class
            || c == long.class || c == Long.class || c == float.class || c == Float.class || c == double.class || c == Double.class;
  }

  public void setInvocationHandlerWrapper(QuackInvocationHandlerWrapper invocationHandlerWrapper) {
    this.invocationHandlerWrapper = invocationHandlerWrapper;
  }


  // trap for Object methods.
  private static InvocationHandler wrapObjectInvocationHandler(JavaScriptObject jo, InvocationHandler handler) {
    return (proxy, method, args) -> {
      if (method.getDeclaringClass() == Object.class)
        return method.invoke(jo, args);

      return handler.invoke(proxy, method, args);
    };
  }

  InvocationHandler getWrappedInvocationHandler(JavaScriptObject javaScriptObject, InvocationHandler handler) {
    // trap Object methods before allowing it to go to the JavaScriptObject or the JavaScriptObject single method lambda.
    // higher level wrappers may trap the Object methods themselves.
    handler = wrapObjectInvocationHandler(javaScriptObject, handler);

    if (invocationHandlerWrapper == null)
      return handler;
    InvocationHandler wrapped = invocationHandlerWrapper.wrapInvocationHandler(javaScriptObject, handler);
    if (wrapped != null)
      return wrapped;
    return handler;
  }

  /**
   * Register a function that coerces values JavaScript values into an object of type
   * {@code clazz} before being passed along to Java.
   */
  public synchronized <T> void putJavaScriptToJavaCoercion(Class<T> clazz, QuackCoercion<T, Object> coercion) {
    JavaScriptToJavaCoercions.put(clazz, coercion);
  }

  /**
   * Register a function that coerces Java values of type {@code clazz} into a JavaScript object
   * before being passed along to Duktape.
   * @param clazz
   * @param coercion
   * @param <F>
   */
  public synchronized <F> void putJavaToJavaScriptCoercion(Class<F> clazz, QuackCoercion<Object, F> coercion) {
    JavaToJavascriptCoercions.put(clazz, coercion);
  }

  /**
   * Coerce a Java value into an equivalent JavaScript object.
   */
  public Object coerceJavaToJavaScript(Object o) {
    if (o == null)
      return null;
    return coerceJavaToJavaScript(o.getClass(), o);
  }


  /**
   * Coerce Java args to Javascript object args.
   * @param args
   */
  public Object[] coerceJavaArgsToJavaScript(Object... args) {
    if (args != null) {
      for (int i = 0; i < args.length; i++) {
         args[i] = coerceJavaToJavaScript(args[i]);
      }
    }
    return args;
  }

  /**
   * Coerce a Java value into an equivalent JavaScript object.
   */
  public Object coerceJavaToJavaScript(Class clazz, Object o) {
    if (o == null)
      return null;
    Object ret = coerceJavaToJavaScript(JavaToJavascriptCoercions, o, clazz);
    if (ret != null)
      return ret;


    if (clazz.isInterface() && clazz.getMethods().length == 1) {
      // automatically coerce functional interfaces into functions
      Method method = clazz.getMethods()[0];
      return new QuackMethodObject() {
        @Override
        public Object callMethod(Object thiz, Object... args) {
          try {
            if (args != null) {
              Class[] parameters = method.getParameterTypes();
              for (int i = 0; i < args.length; i++) {
                args[i] = coerceJavaScriptToJava(parameters[i], args[i]);
              }
            }
            return coerceJavaToJavaScript(method.invoke(o, args));
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
    while (o instanceof QuackJavaObject) {
      Object coerced = ((QuackJavaObject)o).getObject(clazz);;
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

    Object ret = coerceJavaScriptToJava(JavaScriptToJavaCoercions, o, clazz);
    if (ret != null)
      return ret;

    // convert javascript objects proxy objects that implement interfaces
    if (clazz.isInterface() && o instanceof JavaScriptObject) {
      JavaScriptObject jo = (JavaScriptObject)o;

      // single method arguments are simply callbacks
      if (clazz.getMethods().length == 1) {
        return Proxy.newProxyInstance(clazz.getClassLoader(), new Class[]{clazz},
                getWrappedInvocationHandler(jo, (proxy, method, args) ->
                        coerceJavaScriptToJava(method.getReturnType(), jo.call(args))));
      }
      else {
        InvocationHandler handler = jo.createInvocationHandler();
        return Proxy.newProxyInstance(clazz.getClassLoader(), new Class[] { clazz }, handler);
      }
    }


    return o;
  }

  public interface JavaMethodReference<T> {
    void invoke(T thiz) throws Exception;
  }
  public interface JavaMethodReference0<T, A> {
    void invoke(T thiz, A arg0) throws Exception;
  }
  public interface JavaMethodReference1<T, A, B> {
    void invoke(T thiz, A arg0, B arg1) throws Exception;
  }
  public interface JavaMethodReference2<T, A, B, C> {
    void invoke(T thiz, A arg0, B arg1, C arg2) throws Exception;
  }
  public interface JavaMethodReference3<T, A, B, C, D> {
    void invoke(T thiz, A arg0, B arg1, C arg2, D arg3) throws Exception;
  }
  public interface JavaMethodReference4<T, A, B, C, D, E> {
    void invoke(T thiz, A arg0, B arg1, C arg2, D arg3, E arg4) throws Exception;
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

  static Memoize<Field> javaObjectFields = new Memoize<>();
  static Memoize<Boolean> javaObjectMethods = new Memoize<>();
  static Memoize<Method> javaObjectGetter = new Memoize<>();
  static Memoize<Method> javaObjectSetter = new Memoize<>();
  static Memoize<Method> javaObjectMethodCandidates = new Memoize<>();
  static Memoize<Constructor> javaObjectConstructorCandidates = new Memoize<>();
  static Memoize<Method> interfaceMethods = new Memoize<>();
  static Method getInterfaceMethod(Method method) {
    return interfaceMethods.memoize(() -> {
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

  public synchronized void putJavaScriptToJavaMethodCoercion(Method method, QuackMethodCoercion coercion) {
    JavaScriptToJavaMethodCoercions.put(method, coercion);
    interfaceMethods.clear();
  }

  public synchronized void putJavaToJavaScriptMethodCoercion(Method method, QuackMethodCoercion coercion) {
    JavaToJavascriptMethodCoercions.put(method, coercion);
    interfaceMethods.clear();
  }

  private static class MethodException extends Exception {
    /**
     *
     */
    private static final long serialVersionUID = -1432377890337490927L;
    Method method;
    MethodException(Method method) {
      this.method = method;
    }
  }

  private static Object throwInvokedMethod(Object proxy, Method method, Object[] args) throws MethodException {
    throw new MethodException(method);
  }

  private static <T> T createMethodInterceptProxy(Class<T> clazz) {
    return (T)Proxy.newProxyInstance(clazz.getClassLoader(), new Class[] { clazz }, QuackContext::throwInvokedMethod);
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

  private static Object coerceJavaToJavaScript(Map<Class, QuackCoercion> coerce, Object o, Class<?> clazz) {
    QuackCoercion coercion = coerce.get(clazz);
    if (coercion != null) {
      return coercion.coerce(clazz, o);
    }

    // check to see if there is a superclass converter (ie, Enum.class as a catch all).
    for (Map.Entry<Class, QuackCoercion> check: coerce.entrySet()) {
      if (check.getKey().isAssignableFrom(clazz))
        return check.getValue().coerce(clazz, o);
    }

    return null;
  }

  private static Object coerceJavaScriptToJava(Map<Class, QuackCoercion> coerce, Object o, Class<?> clazz) {
    QuackCoercion coercion = coerce.get(clazz);
    if (coercion != null) {
      return coercion.coerce(clazz, o);
    }

    // check to see if there exists a more specific superclass converter.
    for (Map.Entry<Class, QuackCoercion> check: coerce.entrySet()) {
      if (clazz.isAssignableFrom(check.getKey())) {
        throw new AssertionError("Superclass converter not implemented.");
      }
    }

    // check to see if there is a subclass converter (ie, Enum.class as a catch all).
    for (Map.Entry<Class, QuackCoercion> check: coerce.entrySet()) {
      if (check.getKey().isAssignableFrom(clazz))
        return check.getValue().coerce(clazz, o);
    }

    return null;
  }

  /**
   * Create a new interpreter instance. Calls to this method <strong>must</strong> matched with
   * calls to {@link #close()} on the returned instance to avoid leaking native memory.
   */
  public static QuackContext create(boolean useQuickJS) {
    QuackContext quack = new QuackContext();
    // context will hold a weak ref, so this doesn't matter if it fails.
    long context = createContext(quack, useQuickJS);
    if (context == 0) {
      throw new OutOfMemoryError("Cannot create Duktape instance");
    }
    quack.context = context;
    return quack;
  }

  public static QuackContext create() {
    return create(true);
  }

  private long context;

  private QuackContext() {
    // coercing javascript string into an enum for java
    JavaScriptToJavaCoercions.put(Enum.class, (QuackCoercion<Enum, Object>) (clazz, o) -> {
      if (o == null)
        return null;
      return Enum.valueOf(clazz, o.toString());
    });

    // coerce JavaScript Numbers. quack supports ints and doubles natively.
    JavaScriptToJavaCoercions.put(Byte.class, (clazz, o) -> o instanceof Number ? ((Number)o).byteValue() : o instanceof String ? Byte.parseByte(o.toString()) : o);
    JavaScriptToJavaCoercions.put(byte.class, (clazz, o) -> o instanceof Number ? ((Number)o).byteValue() : o instanceof String ? Byte.parseByte(o.toString()) : o);
    // bytes become ints
    putJavaToJavaScriptCoercion(byte.class, (clazz, o) -> o.intValue());
    putJavaToJavaScriptCoercion(Byte.class, (clazz, o) -> o.intValue());

    JavaScriptToJavaCoercions.put(Short.class, (clazz, o) -> o instanceof Number ? ((Number)o).shortValue() : o instanceof String ? Short.parseShort(o.toString()) : o);
    JavaScriptToJavaCoercions.put(short.class, (clazz, o) -> o instanceof Number ? ((Number)o).shortValue() : o instanceof String ? Short.parseShort(o.toString()) : o);
    // shorts become ints
    putJavaToJavaScriptCoercion(short.class, (clazz, o) -> o.intValue());
    putJavaToJavaScriptCoercion(Short.class, (clazz, o) -> o.intValue());

    JavaScriptToJavaCoercions.put(Integer.class, (clazz, o) -> o instanceof Number ? ((Number)o).intValue() : o instanceof String ? Integer.parseInt(o.toString()) : o);
    JavaScriptToJavaCoercions.put(int.class, (clazz, o) -> o instanceof Number ? ((Number)o).intValue() : o instanceof String ? Integer.parseInt(o.toString()) : o);

    JavaScriptToJavaCoercions.put(Long.class, (clazz, o) -> o instanceof Number ? ((Number)o).longValue() : o instanceof String ? Long.parseLong(o.toString()) : o);
    JavaScriptToJavaCoercions.put(long.class, (clazz, o) -> o instanceof Number ? ((Number)o).longValue() : o instanceof String ? Long.parseLong(o.toString()) : o);
    // by default longs become strings, precision loss going to double. that's no good.
    // coercions can be used to get numbers if necessary.
    putJavaToJavaScriptCoercion(long.class, (clazz, o) -> o.toString());
    putJavaToJavaScriptCoercion(Long.class, (clazz, o) -> o.toString());

    JavaScriptToJavaCoercions.put(Float.class, (clazz, o) -> o instanceof Number ? ((Number)o).floatValue() : o instanceof String ? Float.parseFloat(o.toString()) : o);
    JavaScriptToJavaCoercions.put(float.class, (clazz, o) -> o instanceof Number ? ((Number)o).floatValue() : o instanceof String ? Float.parseFloat(o.toString()) : o);
    // floats become doubles
    putJavaToJavaScriptCoercion(float.class, (clazz, o) -> o.doubleValue());
    putJavaToJavaScriptCoercion(Float.class, (clazz, o) -> o.doubleValue());

    JavaScriptToJavaCoercions.put(Double.class, (clazz, o) -> o instanceof Number ? ((Number)o).doubleValue() : o instanceof String ? Double.parseDouble(o.toString()) : o);
    JavaScriptToJavaCoercions.put(double.class, (clazz, o) -> o instanceof Number ? ((Number)o).doubleValue() : o instanceof String ? Double.parseDouble(o.toString()) : o);

    // coercing a java enum into javascript string
    putJavaToJavaScriptCoercion(Enum.class, (clazz, o) -> o.toString());

    // buffers are transferred one way
    putJavaToJavaScriptCoercion(ByteBuffer.class, (clazz, o) -> {
      // can send as is if sending whole buffers.
      if (o.isDirect() && o.remaining() == o.capacity())
        return o;
      ByteBuffer direct = ByteBuffer.allocateDirect(o.remaining());
      direct.put(o);
      direct.flip();
      return direct;
    });
  }

  private long totalElapsedScriptExecutionMs;

  /**
   * Profiling tool. Get the total time spent evaluating JavaScript. This iincludes
   * calls back out to Java.
   * @return
   */
  public long getTotalScriptExecutionTime() {
    return totalElapsedScriptExecutionMs;
  }

  /**
   * Profiling tool. Reset the total time spent evaluating JavaScript.
   */
  public void resetTotalScriptExecutionTime() {
    totalElapsedScriptExecutionMs = 0;
  }

  /**
   * Evaluate {@code script} and return a result. {@code fileName} will be used in error
   * reporting.
   *
   * @throws QuackException if there is an error evaluating the script.
   */
  public synchronized Object evaluate(String script, String fileName) {
    if (context == 0)
      return null;
    long start = System.nanoTime() / 1000000;
    try {
      return evaluate(context, script, fileName);
    }
    finally {
      totalElapsedScriptExecutionMs += System.nanoTime() / 1000000 - start;
    }
  }

  /**
   * Evaluate {@code script} and return a result.
   *
   * @throws QuackException if there is an error evaluating the script.
   */
  public synchronized Object evaluate(String script) {
    return evaluate(script, "?");
  }

  /**
   * Evaluate {@code script} and return the expected result of a specific type.
   * @param script
   * @param clazz
   * @param <T>
   * @return
   */
  public synchronized <T> T evaluate(String script, Class<T> clazz) {
      return (T)coerceJavaScriptToJava(clazz, evaluate(script));
  }

  /**
   * Evaluate {@code script} and return the expected result of a JavaScriptObject.
   * @param script
   * @return
   */
  public synchronized JavaScriptObject evaluateForJavaScriptObject(String script) {
    return evaluate(script, JavaScriptObject.class);
  }

  /**
   * Compile a JavaScript function and return of JavaScriptObject as the resulting function.
   *
   * @throws QuackException if there is an error evaluating the script.
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
    if (context == 0)
      return;
    setGlobalProperty(context, property, value);
  }

  /**
   * Notify any attached debugger to process pending any debugging requests. When
   * cooperateDebuger is invoked by the caller, the caller must ensure no calls into
   * the Duktape during that time.
   */
  public synchronized void cooperateDebugger() {
    if (context == 0)
      return;
    cooperateDebugger(context);
  }

  /**
   * Wait for a debugging connection on port 9091.
   */
  public void waitForDebugger(String connectionString) {
    if (context == 0)
      return;
    waitForDebugger(context, connectionString);
  }

  /**
   * Check if a debugger is currently attached.
   */
  public boolean isDebugging() {
    if (context == 0)
      return false;
    return isDebugging(context);
  }

  /**
   * Send an custom app notification to any connected debugging client.
   * @param args
   */
  public synchronized void debuggerAppNotify(Object... args) {
    if (context == 0)
      return;
    debuggerAppNotify(context, args);
  }

  synchronized Object getKeyObject(long object, Object key) {
    if (context == 0)
      return null;
    return getKeyObject(context, object, key);
  }
  synchronized Object getKeyString(long object, String key) {
    if (context == 0)
      return null;
    return getKeyString(context, object, key);
  }
  synchronized Object getKeyInteger(long object, int index) {
    if (context == 0)
      return null;
    return getKeyInteger(context, object, index);
  }
  synchronized boolean setKeyObject(long object, Object key, Object value) {
    if (context == 0)
      return false;
    return setKeyObject(context, object, key, value);
  }
  synchronized boolean setKeyString(long object, String key, Object value) {
    if (context == 0)
      return false;
    return setKeyString(context, object, key, value);
  }
  synchronized boolean setKeyInteger(long object, int index, Object value) {
    if (context == 0)
      return false;
    return setKeyInteger(context, object, index, value);
  }
  synchronized Object call(long object, Object... args) {
    if (context == 0)
      return null;
    long start = System.nanoTime() / 1000000;
    try {
      return call(context, object, args);
    }
    finally {
      totalElapsedScriptExecutionMs += System.nanoTime() / 1000000 - start;
      postInvocationLocked();
    }
  }
  synchronized Object callMethod(long object, Object thiz, Object... args) {
    if (context == 0)
      return null;
    long start = System.nanoTime() / 1000000;
    try {
      return callMethod(context, object, thiz, args);
    }
    finally {
      totalElapsedScriptExecutionMs += System.nanoTime() / 1000000 - start;
      postInvocationLocked();
    }
  }
  synchronized Object callProperty(long object, Object property, Object... args) {
    if (context == 0)
      return null;
    long start = System.nanoTime() / 1000000;
    try {
      return callProperty(context, object, property, args);
    }
    finally {
      totalElapsedScriptExecutionMs += System.nanoTime() / 1000000 - start;
      postInvocationLocked();
    }
  }
  synchronized String stringify(long object) {
      if (context == 0)
        return null;
      return stringify(context, object);
  }
  final ArrayList<Long> finalizationQueue = new ArrayList<>();
  void finalizeJavaScriptObject(long object) {
    if (context == 0)
      return;
    synchronized (finalizationQueue) {
      finalizationQueue.add(object);
    }
  }
  private void finalizeObjectsLocked() {
    ArrayList<Long> copy;
    synchronized (finalizationQueue) {
      if (finalizationQueue.isEmpty())
        return;
      copy = new ArrayList<>();
      copy.addAll(finalizationQueue);
      finalizationQueue.clear();
    }
    if (context == 0)
      return;
    for (Long object: finalizationQueue) {
      finalizeJavaScriptObject(object);
    }
  }
  private void postInvocationLocked() {
    finalizeObjectsLocked();
    runJobs(context);
  }

  public long getHeapSize() {
    if (context == 0)
      return 0;
    return getHeapSize(context);
  }

  private Object quackGet(QuackObject quackObject, Object key) {
    return quackObject.get(key);
  }

  private boolean quackHas(QuackObject quackObject, Object key) {
    return quackObject.has(key);
  }

  private boolean quackSet(QuackObject quackObject, Object key, Object value) {
    return quackObject.set(key, value);
  }

  private Object[] empty = new Object[0];
  private Object quackApply(QuackObject quackObject, Object thiz, Object... args) {
    return quackObject.callMethod(thiz, args == null ? empty : args);
  }
  
  private Object quackConstruct(QuackObject quackObject, Object... args) {
    return quackObject.construct(args == null ? empty : args);
  }

  private static native long getHeapSize(long context);

  private static native long createContext(QuackContext quackContext, boolean useQuickJS);
  private static native void destroyContext(long context);
  private static native Object evaluate(long context, String sourceCode, String fileName);
  private static native JavaScriptObject compileFunction(long context, String script, String fileName);

  private static native void cooperateDebugger(long context);
  private static native void waitForDebugger(long context, String connectionString);
  private static native boolean isDebugging(long context);
  private static native void debuggerAppNotify(long context, Object... args);
  private static native Object getKeyObject(long context, long object, Object key);
  private static native Object getKeyString(long context, long object, String key);
  private static native Object getKeyInteger(long context, long object, int index);
  private static native boolean setKeyObject(long context, long object, Object key, Object value);
  private static native boolean setKeyString(long context, long object, String key, Object value);
  private static native boolean setKeyInteger(long context, long object, int index, Object value);
  private static native Object call(long context, long object, Object... args);
  private static native Object callMethod(long context, long object, Object thiz, Object... args);
  private static native Object callProperty(long context, long object, Object property, Object... args);
  private static native void setGlobalProperty(long context, Object property, Object value);
  private static native String stringify(long context, long object);
  private static native void finalizeJavaScriptObject(long context, long object);
  private static native void runJobs(long context);
}
