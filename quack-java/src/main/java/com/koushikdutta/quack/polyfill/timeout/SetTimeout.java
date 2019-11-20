package  com.koushikdutta.quack.polyfill.timeout;

import com.koushikdutta.quack.JavaScriptObject;

interface SetTimeout {
    long invoke(JavaScriptObject cb, int milliseconds, Object... params);
}

