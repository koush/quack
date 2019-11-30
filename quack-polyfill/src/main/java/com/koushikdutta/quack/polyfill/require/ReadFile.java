package com.koushikdutta.quack.polyfill.require;

@FunctionalInterface
public interface ReadFile {
    String readFile(String baseFilename);
}
