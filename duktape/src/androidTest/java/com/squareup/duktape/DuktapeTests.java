package com.squareup.duktape;

import junit.framework.TestCase;

import java.util.Arrays;
import java.util.List;

public class DuktapeTests extends TestCase  {
    private static class ResultHolder<T> {
        public T result;
    }

    public void testRoundtrip() {
        Duktape duktape = Duktape.create();
        String script = "function(ret) { return ret; }";
        JavaScriptObject func = duktape.compileFunction(script, "?");

        // should all come back as doubles.
        List<Object> values = Arrays.asList((byte)0, (short)0, 0, 0l, 0f, 0d);
        for (Object value: values) {
            Object ret = func.call(value);
            assertTrue(ret instanceof Double);
        }
    }

    interface Callback {
        void callback();
    }

    public void testMethodCall() {
        ResultHolder<Boolean> resultHolder = new ResultHolder<>();
        Callback cb = () -> resultHolder.result = true;

        Duktape duktape = Duktape.create();
        String script = "function(cb) { cb.callback() }";
        JavaScriptObject func = duktape.compileFunction(script, "?");

        func.call(cb);

        assertTrue(resultHolder.result);
    }

    public void testCallback() {
        ResultHolder<Boolean> resultHolder = new ResultHolder<>();
        Callback cb = () -> resultHolder.result = true;

        Duktape duktape = Duktape.create();
        String script = "function(cb) { cb() }";
        JavaScriptObject func = duktape.compileFunction(script, "?");

        func.call(duktape.coerceJavaToJavaScript(Callback.class, cb));

        assertTrue(resultHolder.result);
    }

    interface RoundtripCallback {
        Object callback(Object o);
    }

    public void testInterface() {
        Duktape duktape = Duktape.create();
        String script = "function() {" +
                "function RoundtripCallback() {" +
                "}" +
                "RoundtripCallback.prototype.callback = function(o) {" +
                "return o;" +
                "};" +
                "return new RoundtripCallback();" +
                "}";
        JavaScriptObject func = duktape.compileFunction(script, "?");
        RoundtripCallback cb = ((JavaScriptObject)func.call()).proxyInterface(RoundtripCallback.class);

        // should all come back as doubles.
        List<Object> values = Arrays.asList((byte)0, (short)0, 0, 0l, 0f, 0d);
        for (Object value: values) {
            Object ret = cb.callback(value);
            assertTrue(ret instanceof Double);
        }
    }

    interface InterfaceCallback {
        void callback(Callback o);
    }

    public void testCallbackInterface() {
        ResultHolder<Boolean> resultHolder = new ResultHolder<>();
        Callback cb = () -> resultHolder.result = true;

        Duktape duktape = Duktape.create();
        String script = "function() {" +
                "function RoundtripCallback() {" +
                "}" +
                "RoundtripCallback.prototype.callback = function(cb) {" +
                "cb();" +
                "};" +
                "return new RoundtripCallback();" +
                "}";
        JavaScriptObject func = duktape.compileFunction(script, "?");
        InterfaceCallback iface = ((JavaScriptObject)func.call()).proxyInterface(InterfaceCallback.class);
        iface.callback(cb);

        assertTrue(resultHolder.result);
    }

    interface RoundtripInterfaceCallback {
        Object callback(Object val, RoundtripCallback o);
    }

    public void testRoundtripInterfaceCallback() {
        ResultHolder<Integer> resultHolder = new ResultHolder<>();
        resultHolder.result = 0;
        RoundtripCallback cb = o -> {
            resultHolder.result++;
            assertTrue(o instanceof Double);
            return o;
        };

        Duktape duktape = Duktape.create();
        String script = "function() {" +
                "function RoundtripCallback() {" +
                "}" +
                "RoundtripCallback.prototype.callback = function(o, cb) {" +
                "return cb(o);" +
                "};" +
                "return new RoundtripCallback();" +
                "}";
        JavaScriptObject func = duktape.compileFunction(script, "?");
        RoundtripInterfaceCallback iface = ((JavaScriptObject)func.call()).proxyInterface(RoundtripInterfaceCallback.class);

        // should all come back as doubles.
        List<Object> values = Arrays.asList((byte)0, (short)0, 0, 0l, 0f, 0d);
        for (Object value: values) {
            Object ret = iface.callback(value, cb);
            assertTrue(ret instanceof Double);
        }
        assertTrue(resultHolder.result == 6);
    }

    enum Foo {
        Bar,
        Baz,
    }

    public void testEnumRoundtrip() {
        Duktape duktape = Duktape.create();
        String script = "function(ret) { return ret; }";
        JavaScriptObject func = duktape.compileFunction(script, "?");

        // should all come back as strings.
        List<Object> values = Arrays.asList(Foo.values());
        for (Object value: values) {
            Object ret = func.call(value);
            assertTrue(ret instanceof String);
            assertTrue(duktape.coerceJavaScriptToJava(Foo.class, ret) instanceof Foo);
        }
    }

    interface EnumInterface {
        Foo callback(Foo foo);
    }

    public void testEnumInterface() {
        Duktape duktape = Duktape.create();
        String script = "function() {" +
                "function RoundtripCallback() {" +
                "}" +
                "RoundtripCallback.prototype.callback = function(o) {" +
                "return o;" +
                "};" +
                "return new RoundtripCallback();" +
                "}";
        JavaScriptObject func = duktape.compileFunction(script, "?");
        EnumInterface cb = ((JavaScriptObject)func.call()).proxyInterface(EnumInterface.class);

        // should all come back as Foos.
        List<Foo> values = Arrays.asList(Foo.values());
        for (Foo value: values) {
            Object ret = cb.callback(value);
            assertNotNull(ret);
        }
    }

    interface RoundtripEnumInterfaceCallback {
        Foo callback(Foo val, EnumInterface o);
    }


    public void testRoundtripEnumInterfaceCallback() {
        ResultHolder<Integer> resultHolder = new ResultHolder<>();
        resultHolder.result = 0;
        EnumInterface cb = o -> {
            resultHolder.result++;
            return o;
        };

        Duktape duktape = Duktape.create();
        String script = "function() {" +
                "function RoundtripCallback() {" +
                "}" +
                "RoundtripCallback.prototype.callback = function(o, cb) {" +
                "return cb(o);" +
                "};" +
                "return new RoundtripCallback();" +
                "}";
        JavaScriptObject func = duktape.compileFunction(script, "?");
        RoundtripEnumInterfaceCallback iface = ((JavaScriptObject)func.call()).proxyInterface(RoundtripEnumInterfaceCallback.class);

        // should all come back as Foos.
        List<Foo> values = Arrays.asList(Foo.values());
        for (Foo value: values) {
            Foo ret = iface.callback(value, cb);
            assertNotNull(ret);
        }
        assertTrue(resultHolder.result == 2);
    }
}
