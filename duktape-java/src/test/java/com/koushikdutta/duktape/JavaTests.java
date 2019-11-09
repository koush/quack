package com.koushikdutta.duktape;

import java.io.File;
import java.io.PrintStream;

import com.squareup.duktape.Duktape;
import com.squareup.duktape.DuktapeMethodName;
import com.squareup.duktape.JavaScriptObject;

import org.junit.Test;


public class JavaTests {
    static {
        System.load(new File("duktape-jni/build/lib/main/debug/libduktape-jni.dylib").getAbsolutePath());
    }

    @Test
    public void testGlobalProperty() throws InterruptedException {
        Duktape duktape = Duktape.create();
        Console console = new Console(duktape, System.out, System.err);
        duktape.setGlobalProperty("jconsole", console);
        JavaScriptObject jo = duktape.compileFunction("(function(){jconsole.log('asdasd')})", "test.js");
        jo.call();
    }

    @Test
    public void testRoundtrip() throws InterruptedException {
        Duktape duktape = Duktape.create();
        Console console = new Console(duktape, System.out, System.err);
        duktape.setGlobalProperty("jconsole", console);
        JavaScriptObject jo = duktape.compileFunction("(function(){return jconsole.log;})", "test.js");
        Object p = jo.call();
    }

    static public class Console {
        Duktape duktape;
        PrintStream out;
        PrintStream err;
        public Console(Duktape duktape, PrintStream out, PrintStream err) {
            this.duktape = duktape;
            this.out = out;
            this.err = err;
        }

        String getLog(Object... objects) {
            String[] strings = new String[objects.length];
            for (int i = 0; i < objects.length; i++) {
                if (objects[i] != null)
                    strings[i] = objects[i].toString();
            }

            String log = String.join(" ", strings);
            duktape.debuggerAppNotify(log);
            return log;
        }

        public void log(Object... objects) {
            out.println(getLog(objects));
        }
        public void error(Object... objects) {
            err.println(getLog(objects));
        }
        public void warn(Object... objects) {
            err.println(getLog(objects));
        }
        public void debug(Object... objects) {
            err.println(getLog(objects));
        }
        public void info(Object... objects) {
            err.println(getLog(objects));
        }

        @DuktapeMethodName(name = "assert")
        public void assert_(Object... objects) {
            err.println(getLog(objects));
        }
    }


    @Test
    public void testPoop() {
        Duktape duktape = Duktape.create();
        JavaScriptObject jo = duktape.evaluateForJavaScriptObject("({ doThing: function() { return 5555 }})");
        System.out.println(jo);
        duktape.close();
        System.out.println("OK");
    }

    @Test
    public void testFun() {
        Duktape duktape = Duktape.create();
        JavaScriptObject jo = duktape.compileFunction("(function(){})", "test.js");
        jo.call();
    }
}
