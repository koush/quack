package com.koushikdutta.quack;

/**
 * Will be coerced into a JSON object when received by the JavaScript runtime. The string must be valid JSON.
 */
public final class QuackJsonObject {
    final public String json;
    public QuackJsonObject(String json) {
        this.json = json;
    }
}
