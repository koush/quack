package com.squareup.duktape;

import java.util.HashMap;

public class Memoize<T> {
  public interface MemoizeMap<V> {
    boolean containsKey(Object key);
    V get(Object key);
    V put(Integer key, V value);
    void clear();
  }

  private static class MemoizeMapImpl<V> extends HashMap<Integer, V> implements MemoizeMap<V> {
  }

  public Memoize(MemoizeMap<T> map) {
    store = map;
  }
  public Memoize() {
    this(new MemoizeMapImpl<>());
  }
  int hash(Object... objects) {
    int ret = 0;
    for (Object o: objects) {
      ret ^= o == null ? 0 : o.hashCode();
    }
    ret ^= objects.length;
    return ret;
  }

  MemoizeMap<T> store;
  public T memoize(MemoizeFunc<T> func, Object... args) {
    int hash = hash(args);
    return memoize(func, hash);
  }

  void clear() {
    store.clear();
  }

  public T memoize(MemoizeFunc<T> func, Object arg0, Object[] args) {
    int hash = hash(args);
    hash ^= arg0 == null ? 0 : arg0.hashCode();
    return memoize(func, hash);
  }

  public T memoize(MemoizeFunc<T> func, Object arg0, Object[] args0, Object[] args1) {
    int hash = hash(args0);
    hash ^= hash(args1);
    hash ^= arg0 == null ? 0 : arg0.hashCode();
    return memoize(func, hash);
  }

  private T memoize(MemoizeFunc<T> func, int hash) {
    if (store.containsKey(hash)) {
      return store.get(hash);
    }
    T ret = func.process();
    store.put(hash, ret);
    return ret;
  }
}
