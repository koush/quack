package com.koushikdutta.quack.polyfill.timeout;

import com.koushikdutta.quack.JavaScriptObject;

interface SetImmediate {
    long invoke(JavaScriptObject cb, Object... params);
}

