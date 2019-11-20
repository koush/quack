package com.koushikdutta.quack.polyfill;

public interface JsonDeserializer {
    <T> T deserialize(Class<T> clazz, String json);
}
