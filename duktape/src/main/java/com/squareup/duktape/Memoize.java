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
    return ret;
  }

  MemoizeMap<T> store = new MemoizeMapImpl<>();
  public T memoize(MemoizeFunc<T> func, Object... args) {
    int hash = hash(args);
    if (store.containsKey(hash)) {
      return store.get(hash);
    }
    T ret = func.process();
    store.put(hash, ret);
    return ret;
  }

  void clear() {
    store.clear();
  }
}
