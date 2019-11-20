package com.koushikdutta.quack.polyfill;

public interface JsonSerializer {
    <T> String serialize(Class<T> clazz, T object);
}
