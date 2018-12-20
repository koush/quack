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

import java.io.Closeable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/** A simple EMCAScript (Javascript) interpreter. */
public final class Duktape implements Closeable {
  private static final Map<Class, JavaCoercion> JavaCoercions = new LinkedHashMap<>();
  static {
    System.loadLibrary("duktape");

    setJavaCoercion(String.class, new JavaCoercion<String>() {
      @Override
      public String coerce(Object o) {
        return o.toString();
      }
    });

    setJavaCoercion(boolean.class, new JavaCoercion<Boolean>() {
      @Override
      public Boolean coerce(Object o) {
        return null;
      }
    });
  }

  public interface JavaCoercion<T> {
    T coerce(Object o);
  }

  public static synchronized <T> void setJavaCoercion(Class<T> clazz, JavaCoercion<T> coercion) {
    JavaCoercions.put(clazz, coercion);
  }

  public static Object coerce(Object o, Class<?> clazz) {
    if (clazz.isInstance(o))
      return o;
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
    for (Map.Entry<Class, JavaCoercion> check: JavaCoercions.entrySet()) {
      if (clazz.isAssignableFrom(check.getKey()))
        return check.getValue().coerce(o);
    }

    return null;
  }

  /**
   * Create a new interpreter instance. Calls to this method <strong>must</strong> matched with
   * calls to {@link #close()} on the returned instance to avoid leaking native memory.
   */
  public static Duktape create() {
    long context = createContext();
    if (context == 0) {
      throw new OutOfMemoryError("Cannot create Duktape instance");
    }
    Duktape duktape = new Duktape(context);
    duktape.evaluate(
            "var __proxyHandler = {\n" +
                    "\thas: function(f, key){ return key == '__java_this' || !!get(key); },\n" +
                    "\tget: function(f, prop, receiver) { return f.target.__duktape_get(f.target, prop, receiver); },\n" +
                    "\tapply: function(f, thisArg, argumentsList) { return f.target.__duktape_apply(f.target, thisArg, argumentsList); },\n" +
                    "};\n" +
                    "function __makeProxy(obj) {\n" +
                    "\tfunction f() {}; f.target = obj;\n" +
                    "\treturn new Proxy(f, __proxyHandler);\n" +
                    "};\n"
    );
    return duktape;
  }

  private long context;

  private Duktape(long context) {
    this.context = context;
  }

  /**
   * Evaluate {@code script} and return a result. {@code fileName} will be used in error
   * reporting. Note that the result must be one of the supported Java types or the call will
   * return null.
   *
   * @throws DuktapeException if there is an error evaluating the script.
   */
  public synchronized Object evaluate(String script, String fileName) {
    return evaluate(context, script, fileName);
  }
  /**
   * Evaluate {@code script} and return a result. Note that the result must be one of the
   * supported Java types or the call will return null.
   *
   * @throws DuktapeException if there is an error evaluating the script.
   */
  public synchronized Object evaluate(String script) {
    return evaluate(context, script, "?");
  }

  public synchronized Object compile(String script, String fileName) {
    return compile(context, script, fileName);
  }

  /**
   * Provides {@code object} to JavaScript as a global object called {@code name}. {@code type}
   * defines the interface implemented by {@code object} that will be accessible to JavaScript.
   * {@code type} must be an interface that does not extend any other interfaces, and cannot define
   * any overloaded methods.
   * <p>Methods of the interface may return {@code void} or any of the following supported argument
   * types: {@code boolean}, {@link Boolean}, {@code int}, {@link Integer}, {@code double},
   * {@link Double}, {@link String}.
   */
  public synchronized <T> void set(String name, Class<T> type, T object) {
    if (!type.isInterface()) {
      throw new UnsupportedOperationException("Only interfaces can be bound. Received: " + type);
    }
    if (type.getInterfaces().length > 0) {
      throw new UnsupportedOperationException(type + " must not extend other interfaces");
    }
    if (!type.isInstance(object)) {
      throw new IllegalArgumentException(object.getClass() + " is not an instance of " + type);
    }
    LinkedHashMap<String, Method> methods = new LinkedHashMap<>();
    for (Method method : type.getMethods()) {
      if (methods.put(method.getName(), method) != null) {
        throw new UnsupportedOperationException(method.getName() + " is overloaded in " + type);
      }
    }
    set(context, name, object, methods.values().toArray());
  }

  /**
   * Attaches to a global JavaScript object called {@code name} that implements {@code type}.
   * {@code type} defines the interface implemented in JavaScript that will be accessible to Java.
   * {@code type} must be an interface that does not extend any other interfaces, and cannot define
   * any overloaded methods.
   * <p>Methods of the interface may return {@code void} or any of the following supported argument
   * types: {@code boolean}, {@link Boolean}, {@code int}, {@link Integer}, {@code double},
   * {@link Double}, {@link String}.
   */
  public synchronized <T> T get(final String name, final Class<T> type) {
    if (!type.isInterface()) {
      throw new UnsupportedOperationException("Only interfaces can be proxied. Received: " + type);
    }
    if (type.getInterfaces().length > 0) {
      throw new UnsupportedOperationException(type + " must not extend other interfaces");
    }
    LinkedHashMap<String, Method> methods = new LinkedHashMap<>();
    for (Method method : type.getMethods()) {
      if (methods.put(method.getName(), method) != null) {
        throw new UnsupportedOperationException(method.getName() + " is overloaded in " + type);
      }
    }

    final long instance = get(context, name, methods.values().toArray());
    final Duktape duktape = this;

    Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{ type },
        new InvocationHandler() {
          @Override
          public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // If the method is a method from Object then defer to normal invocation.
            if (method.getDeclaringClass() == Object.class) {
              return method.invoke(this, args);
            }
            synchronized (duktape) {
              return call(duktape.context, instance, method, args);
            }
          }

          @Override
          public String toString() {
            return String.format("DuktapeProxy{name=%s, type=%s}", name, type.getName());
          }
        });
    return (T) proxy;
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
    if (context != 0) {
      Logger.getLogger(getClass().getName()).warning("Duktape instance leaked!");
    }
  }

  public void setGlobalProperty(Object property, Object value) {
    setGlobalProperty(context, property, value);
  }

  public void waitForDebugger() {
    waitForDebugger(context);
  }

  public void attachDebugger(int fd) {
    attachDebugger(context, fd);
  }

  private static native long createContext();
  private static native void destroyContext(long context);
  private static native Object evaluate(long context, String sourceCode, String fileName);
  private static native Object compile(long context, String sourceCode, String fileName);
  private static native void set(long context, String name, Object object, Object[] methods);
  private static native long get(long context, String name, Object[] methods);
  private static native Object call(long context, long instance, Object method, Object[] args);

  static native void waitForDebugger(long context);
  static native void attachDebugger(long context, int fd);
  static native Object getKeyObject(long context, long object, Object key);
  static native Object getKeyString(long context, long object, String key);
  static native Object getKeyInteger(long context, long object, int index);
  static native Object callSelf(long context, long object, Object... args);
  static native Object callProperty(long context, long object, Object property, Object... args);
  static native void setGlobalProperty(long context, Object property, Object value);
}
