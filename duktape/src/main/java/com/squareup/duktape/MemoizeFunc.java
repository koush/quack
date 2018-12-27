package com.squareup.duktape;

public interface MemoizeFunc<T> {
  T process();
}
