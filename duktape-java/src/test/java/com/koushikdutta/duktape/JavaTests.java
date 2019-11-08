package com.koushikdutta.duktape;

import java.io.File;

import com.squareup.duktape.Duktape;
import com.squareup.duktape.JavaScriptObject;

import org.junit.Test;


public class JavaTests {
    @Test
    public void testPoop() {
        System.load(new File("duktape-jni/build/lib/main/debug/libduktape-jni.dylib").getAbsolutePath());

        Duktape duktape = Duktape.create();
        JavaScriptObject jo = duktape.evaluateForJavaScriptObject("({ doThing: function() { return 5555 }})");
        System.out.println(jo);
        duktape.close();
        System.out.println("OK");
    }
}
