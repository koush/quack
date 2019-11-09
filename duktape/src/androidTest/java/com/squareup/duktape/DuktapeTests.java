package com.squareup.duktape;

import org.junit.Assert;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class DuktapeTests {
    private static class ResultHolder<T> {
        public T result;
    }
    
    static {
        try {
            // for non-android jvm
            System.load(new File("duktape-jni/build/lib/main/debug/libduktape-jni.dylib").getAbsolutePath());
        }
        catch (Exception e) {
        }
    }

    @Test
    public void testRoundtrip() {
        Duktape duktape = Duktape.create();
        String script = "function(ret) { return ret; }";
        JavaScriptObject func = duktape.compileFunction(script, "?");

        // should all come back as doubles.
        List<Object> values = Arrays.asList(new Float(0), new Double(0), 0f, 0d);
        for (Object value: values) {
            Object ret = func.call(value);
            assertTrue(ret instanceof Double);
        }

        // might come back as doubles or ints, depending on runtime.
        values = Arrays.asList(new Byte((byte)0), new Short((short)0), new Integer(0), (byte)0, (short)0, 0);
        for (Object value: values) {
            Object ret = func.call(value);
            assertTrue(ret instanceof Double || ret instanceof Integer);
        }

        // longs must be strings, since it loses precision in doubles.
        assertTrue(func.call(0L) instanceof String);
    }

    interface Callback {
        void callback();
    }

    @Test
    public void testMethodCall() {
        ResultHolder<Boolean> resultHolder = new ResultHolder<>();
        Callback cb = () -> resultHolder.result = true;

        Duktape duktape = Duktape.create();
        String script = "function(cb) { cb.callback() }";
        JavaScriptObject func = duktape.compileFunction(script, "?");

        func.call(cb);

        assertTrue(resultHolder.result);
    }

    @Test
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

    @Test
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
        List<Object> values = Arrays.asList((byte)0, (short)0, 0, 0f, 0d);
        for (Object value: values) {
            Object ret = cb.callback(value);
            assertTrue(ret instanceof Double);
        }

        values = Arrays.asList(0L, "0");
        for (Object value: values) {
            Object ret = cb.callback(value);
            assertTrue(ret instanceof String);
        }
    }

    interface InterfaceCallback {
        void callback(Callback o);
    }

    @Test
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
        List<Object> values = Arrays.asList((byte)0, (short)0, 0, 0f, 0d);
        for (Object value: values) {
            Object ret = iface.callback(value, cb);
            assertTrue(ret instanceof Double);
        }
        assertTrue(resultHolder.result == 5);
    }

    enum Foo {
        Bar,
        Baz,
    }

    @Test
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

    @Test
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


    @Test
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

    @Test
    public void testDuktapeException() {
        Duktape duktape = Duktape.create();
        String script = "function() {" +
                "function func1() {" +
                "throw new Error('duktape!')" +
                "}" +
                "function func2() {" +
                "func1();" +
                "}" +
                "function func3() {" +
                "func2();" +
                "}" +
                "func3();" +
                "}";
        try {
            JavaScriptObject func = duktape.compileFunction(script, "?");
            func.call();
        }
        catch (Exception e) {
            Assert.assertTrue(e.getMessage().contains("duktape!"));
            Assert.assertTrue(e.getStackTrace()[0].getMethodName().contains("func1"));
            Assert.assertTrue(e.getStackTrace()[1].getMethodName().contains("func2"));
            Assert.assertTrue(e.getStackTrace()[2].getMethodName().contains("func3"));
        }
    }


    public void testDuktapeExceptionFromJava() {
        Duktape duktape = Duktape.create();
        String script = "function(cb) {" +
                "function func1() {" +
                "cb.callback();" +
                "}" +
                "function func2() {" +
                "func1();" +
                "}" +
                "function func3() {" +
                "func2();" +
                "}" +
                "func3();" +
                "}";
        try {
            Callback cb = new Callback() {
                @Override
                public void callback() {
                    throw new IllegalArgumentException("java!");
                }
            };

            JavaScriptObject func = duktape.compileFunction(script, "?");
            func.call(cb);
        }
        catch (Exception e) {
            Assert.assertTrue(e.getMessage().contains("java!"));
            Assert.assertTrue(e.getStackTrace()[0].getMethodName().contains("callback"));
            Assert.assertTrue(e.getStackTrace()[4].getMethodName().contains("func1"));
            Assert.assertTrue(e.getStackTrace()[5].getMethodName().contains("func2"));
            Assert.assertTrue(e.getStackTrace()[6].getMethodName().contains("func3"));
        }
    }

    @Test
    public void testDuktapeExceptionMessageFromJava() {
        Duktape duktape = Duktape.create();
        String script = "function(cb, cb2) {" +
                "function func1() {" +
                "try {" +
                "cb.callback();" +
                "}" +
                "catch(e) {" +
                "cb2.callback(e.toString());" +
                "}" +
                "}" +
                "function func2() {" +
                "func1();" +
                "}" +
                "function func3() {" +
                "func2();" +
                "}" +
                "func3();" +
                "}";

        ResultHolder<String> resultHolder = new ResultHolder<>();

        Callback cb = new Callback() {
            @Override
            public void callback() {
                throw new IllegalArgumentException("java!");
            }
        };
        RoundtripCallback cb2 = new RoundtripCallback() {
            @Override
            public Object callback(Object o) {
                resultHolder.result = o.toString();
                return null;
            }
        };

        JavaScriptObject func = duktape.compileFunction(script, "?");
        func.call(cb, cb2);

        assertEquals(resultHolder.result, "EvalError: Java Exception java.lang.IllegalArgumentException: java!");
    }

    @Test
    public void testJavaStackInJavaScript() {
        Duktape duktape = Duktape.create();
        String script = "function(cb) {" +
                "function func1() {" +
                "cb.callback();" +
                "}" +
                "function func2() {" +
                "func1();" +
                "}" +
                "function func3() {" +
                "func2();" +
                "}" +
                "try {" +
                "func3();" +
                "}" +
                "catch(e) {" +
                "return e.stack;" +
                "}" +
                "}";
        Callback cb = new Callback() {
            @Override
            public void callback() {
                throw new IllegalArgumentException("java!");
            }
        };

        JavaScriptObject func = duktape.compileFunction(script, "?");
        String ret = func.call(cb).toString();
        String expected = "EvalError: Java Exception java!\n" +
                "    at [com.squareup.duktape.DuktapeTests$2.callback] (DuktapeTests.java:303)\n" +
                "    at [java.lang.reflect.Method.invoke] (Method.java:-2)\n" +
                "    at [com.squareup.duktape.JavaMethodObject.callMethod] (JavaMethodObject.java:106)\n" +
                "    at [com.squareup.duktape.Duktape.duktapeCallMethod] (Duktape.java:652)    at [anon] (/Volumes/Dev/Scrypted/duktape-android/duktape/src/main/jni/DuktapeContext.cpp:921) internal\n" +
                "    at [anon] () native strict preventsyield\n" +
                "    at [anon] (?:5)\n" +
                "    at func1 (?:1)\n" +
                "    at func2 (?:1)\n" +
                "    at func3 (?:1)\n" +
                "    at [anon] (?:1) preventsyield\n" +
                "    at [com.squareup.duktape.Duktape.call] (Duktape.java:-2)\n" +
                "    at [com.squareup.duktape.Duktape.call] (Duktape.java:601)\n" +
                "    at [com.squareup.duktape.JavaScriptObject.call] (JavaScriptObject.java:36)\n" +
                "    at [com.squareup.duktape.DuktapeTests.testJavaStackInJavaScript] (DuktapeTests.java:308)\n" +
                "    at [java.lang.reflect.Method.invoke] (Method.java:-2)\n" +
                "    at [junit.framework.TestCase.runTest] (TestCase.java:168)\n" +
                "    at [junit.framework.TestCase.runBare] (TestCase.java:134)\n" +
                "    at [junit.framework.TestResult$1.protect] (TestResult.java:115)\n" +
                "    at [junit.framework.TestResult.runProtected] (TestResult.java:133)\n" +
                "    at [junit.framework.TestResult.run] (TestResult.java:118)\n" +
                "    at [junit.framework.TestCase.run] (TestCase.java:124)\n" +
                "    at [android.test.AndroidTestRunner.runTest] (AndroidTestRunner.java:195)\n" +
                "    at [android.test.AndroidTestRunner.runTest] (AndroidTestRunner.java:181)\n" +
                "    at [android.test.InstrumentationTestRunner.onStart] (InstrumentationTestRunner.java:564)\n" +
                "    at [android.app.Instrumentation$InstrumentationThread.run] (Instrumentation.java:2185)";

        // returned stack trace should have same number of lines as above.
        // a weak assertion, but it's indicative of correctness because javascript adds 3 lines more than expected.
        Assert.assertEquals(ret.split("\n").length, expected.split("\n").length);
    }

    interface MarshallCallback {
        void callback(Object o, Boolean B, Short S, Integer I, Long L, Float F, Double D, boolean b, short s, int i, long l, float f, double d, String str);
    }

    @Test
    public void testJavaProxyInDuktapeThreadCrash() {
        Duktape duktape = Duktape.create();
        String script = "function(cb, o, B, S, I, L, F, D, b, s, i, l, f, d, str) {\n" +
                "function yielder() {\n" +
                "\tcb.callback(o, B, S, I, L, F, D, b, s, i, l, f, d, str);\n" +
                "}\n" +
                "\n" +
                "var t = new Duktape.Thread(yielder);\n" +
                "Duktape.Thread.resume(t, cb);\n" +
                "}\n";

        try {
            Object o = new Object();
            MarshallCallback cb = new MarshallCallback() {
                @Override
                public void callback(Object oo, Boolean B, Short S, Integer I, Long L, Float F, Double D, boolean b, short s, int i, long l, float f, double d, String str) {
                    assertTrue(oo == o);
                    assertTrue(b == true);
                    assertTrue(s == 2);
                    assertTrue(i == 2);
                    assertTrue(l == 2);
                    assertTrue(f == 2);
                    assertTrue(d == 2);
                    assertTrue(B == true);
                    assertTrue(S == 2);
                    assertTrue(I == 2);
                    assertTrue(L == 2);
                    assertTrue(F == 2);
                    assertTrue(D == 2);
                    assertEquals("test", str);
                    throw new IllegalArgumentException("java!");
                }
            };

            JavaScriptObject func = duktape.compileFunction(script, "?");
            func.call(cb, o, (Boolean)true, (Short)(short)2, (Integer)2, (Long)2L, (Float)2f, (Double)2d, true, (short)2, 2, 2L, 2f, 2d, "test");
        }
        catch (Exception e) {
            Assert.assertTrue(e.getMessage().contains("java!"));
        }
    }

    @Test
    public void testJson() {
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

        JavaScriptObject ret = (JavaScriptObject)cb.callback(new DuktapeJsonObject("{\"meaningOfLife\":42}"));
        assertEquals(42d, ret.get("meaningOfLife"));
    }
}
