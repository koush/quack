package  com.koushikdutta.quack.polyfill.job;

import com.koushikdutta.quack.JavaScriptObject;

@FunctionalInterface
public interface SetTimeout {
    int execute(JavaScriptObject cb, int milliseconds, Object... params);
}

