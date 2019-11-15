package com.squareup.duktape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.Assert;
import org.junit.Test;

public class DuktapeTests {
    private static boolean useQuickJS = true;
    static {
        try {
            // for non-android jvm
            System.load(new File("duktape-jni/build/lib/main/debug/libduktape-jni.dylib").getAbsolutePath());
        }
        catch (UnsatisfiedLinkError e) {
        }
    }

    // takes a long time. Duktape does not pass due to a const limit. quickjs works.
    // @Test
    public void testOctane() throws IOException {
        Duktape duktape = Duktape.create(false);
        File files[] = new File("/Volumes/Dev/Scrypted/duktape-android/tests/src/main/assets/octane").listFiles();
        Arrays.sort(files, (a, b) -> {
            return a.getAbsolutePath().compareTo(b.getAbsolutePath());            
        });
        for (File file: files) {
            String script = StreamUtility.readFile(file);
            duktape.evaluate(script, file.getAbsolutePath());
        }
        String script = StreamUtility.readFile("/Volumes/Dev/Scrypted/duktape-android/tests/src/main/assets/octane.js");
        duktape.evaluate(script);
        String ret = duktape.evaluateForJavaScriptObject("getResults").call().toString();
        System.out.println(ret);
        duktape.close();
    }

    @Test
    public void testQuickJSExceptionWithTemplateArgs() {
        Duktape duktape = Duktape.create(true);
        duktape.evaluate("(function(){function tcp(str) {return `_${str}._tcp`;}})", "script.js");
        duktape.close();
    }

    public class Console {
        Duktape duktape;
        PrintStream out;
        PrintStream err;
        public Console(Duktape duktape, PrintStream out, PrintStream err) {
            this.duktape = duktape;
            this.out = out;
            this.err = err;
        }

        String getLog(Object... objects) {
            StringBuilder b = new StringBuilder();
            for (Object o: objects) {
                if (o == null)
                    b.append("null");
                else
                    b.append(o.toString());
                b.append("fff\n");
            }
            return b.toString();
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


    private static class ResultHolder<T> {
        public T result;
    }

    @Test
    public void testGlobal() {
        Duktape duktape = Duktape.create(true);
        duktape.setGlobalProperty("hello", "world");
        duktape.setGlobalProperty("thing", new Object());
        duktape.close();
    }

    @Test
    public void testConsole() {
        Duktape duktape = Duktape.create(useQuickJS);
        duktape.setGlobalProperty("console", new Console(duktape, System.out, System.err));
        duktape.evaluate("console.log('hello.');");
        duktape.close();
    }

    @Test
    public void testRoundtrip() {
        Duktape duktape = Duktape.create(false);
        String script = "function(ret) { return ret; }";
        JavaScriptObject func = duktape.compileFunction(script, "?");

        // should all come back as numbers.
        List<Object> values = Arrays.asList(new Float(0), new Double(0), 0f, 0d);
        for (Object value: values) {
            Object ret = func.call(value);
            assertTrue(ret instanceof Number);
        }

        // should all come back as numbers.
        values = Arrays.asList(new Float(3.14), new Double(3.14), 3.14f, 3.14d);
        for (Object value: values) {
            Object ret = func.call(value);
            double d = ((Number)ret).doubleValue();
            assertTrue(d < 3.15 && d > 3.13);
        }

        // should all come back as ints.
        values = Arrays.asList(new Byte((byte)0), new Short((short)0), new Integer(0), (byte)0, (short)0, 0);
        for (Object value: values) {
            Object ret = func.call(value);
            assertTrue(ret instanceof Integer || ret instanceof Double);
        }

        // longs must be strings, since it loses precision in doubles.
        assertTrue(func.call(0L) instanceof String);
        duktape.close();
    }

    interface Callback {
        void callback();
    }

    @Test
    public void testMethodCall() {
        ResultHolder<Boolean> resultHolder = new ResultHolder<>();
        Callback cb = () -> resultHolder.result = true;

        Duktape duktape = Duktape.create(useQuickJS);
        String script = "function(cb) { cb.callback() }";
        JavaScriptObject func = duktape.compileFunction(script, "?");

        func.call(cb);

        assertTrue(resultHolder.result);
        duktape.close();
    }

    @Test
    public void testCallback() {
        ResultHolder<Boolean> resultHolder = new ResultHolder<>();
        Callback cb = () -> resultHolder.result = true;

        Duktape duktape = Duktape.create(useQuickJS);
        String script = "function(cb) { cb() }";
        JavaScriptObject func = duktape.compileFunction(script, "?");

        func.call(duktape.coerceJavaToJavaScript(Callback.class, cb));

        assertTrue(resultHolder.result);
        duktape.close();
    }
    

    interface RoundtripCallback {
        Object callback(Object o);
    }

    @Test
    public void testInterfaceReturn() {
        Duktape duktape = Duktape.create(useQuickJS);
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

        duktape.close();
    }

    @Test
    public void testInterface() {
        Duktape duktape = Duktape.create(useQuickJS);
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
            assertTrue(ret instanceof Double || ret instanceof Integer);
        }

        values = Arrays.asList(0L, "0");
        for (Object value: values) {
            Object ret = cb.callback(value);
            assertTrue(ret instanceof String);
        }
        duktape.close();
    }

    interface InterfaceCallback {
        void callback(Callback o);
    }

    @Test
    public void testCallbackInterface() {
        ResultHolder<Boolean> resultHolder = new ResultHolder<>();
        Callback cb = () -> resultHolder.result = true;

        Duktape duktape = Duktape.create(useQuickJS);
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
        duktape.close();
    }

    interface RoundtripInterfaceCallback {
        Object callback(Object val, RoundtripCallback o);
    }

    @Test
    public void testRoundtripInterfaceCallback() {
        ResultHolder<Integer> resultHolder = new ResultHolder<>();
        resultHolder.result = 0;
        RoundtripCallback cb = o -> {
            resultHolder.result++;
            assertTrue(o instanceof Double || o instanceof Integer);
            return o;
        };

        Duktape duktape = Duktape.create(useQuickJS);
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
            assertTrue(ret instanceof Double || ret instanceof Integer);
        }
        assertTrue(resultHolder.result == 5);
        duktape.close();
    }

    @Test
    public void testRoundtripInterfaceCallbackGC() {
        ResultHolder<Integer> resultHolder = new ResultHolder<>();
        resultHolder.result = 0;
        RoundtripCallback cb = o -> {
            resultHolder.result++;
            assertTrue(o instanceof Double || o instanceof Integer);
            return o;
        };

        Duktape duktape = Duktape.create(useQuickJS);
        String script = "function() {" +
                "function RoundtripCallback() {" +
                "}" +
                "RoundtripCallback.prototype.callback = function(o, cb) {" +
                "return cb(o);" +
                "};" +
                "return new RoundtripCallback();" +
                "}";
        JavaScriptObject func = duktape.compileFunction(script, "?");
        for (int i = 0; i < 100; i++) {
            RoundtripInterfaceCallback iface = ((JavaScriptObject)func.call()).proxyInterface(RoundtripInterfaceCallback.class);

            // should all come back as doubles.
            List<Object> values = Arrays.asList((byte)0);
            for (Object value: values) {
                Object ret = iface.callback(value, cb);
                assertTrue(ret instanceof Double || ret instanceof Integer);
            }
            System.gc();
        }
        duktape.close();
    }

    enum Foo {
        Bar,
        Baz,
    }

    @Test
    public void testEnumRoundtrip() {
        Duktape duktape = Duktape.create(useQuickJS);
        String script = "function(ret) { return ret; }";
        JavaScriptObject func = duktape.compileFunction(script, "?");

        // should all come back as strings.
        List<Object> values = Arrays.asList(Foo.values());
        for (Object value: values) {
            Object ret = func.call(value);
            assertTrue(ret instanceof String);
            assertTrue(duktape.coerceJavaScriptToJava(Foo.class, ret) instanceof Foo);
        }
        duktape.close();
    }

    interface EnumInterface {
        Foo callback(Foo foo);
    }

    @Test
    public void testEnumInterface() {
        Duktape duktape = Duktape.create(useQuickJS);
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
        duktape.close();
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

        Duktape duktape = Duktape.create(useQuickJS);
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
        duktape.close();
    }

    @Test
    public void testDuktapeException() {
        Duktape duktape = Duktape.create(useQuickJS);
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
            JavaScriptObject func = duktape.compileFunction(script, "test.js");
            func.call();
            Assert.fail("failure expected");
        }
        catch (Exception e) {
            Assert.assertTrue(e.getMessage().contains("duktape!"));
            Assert.assertTrue(e.getStackTrace()[0].getMethodName().contains("func1"));
            Assert.assertTrue(e.getStackTrace()[1].getMethodName().contains("func2"));
            Assert.assertTrue(e.getStackTrace()[2].getMethodName().contains("func3"));
        }
        duktape.close();
    }

    @Test
    public void testDuktapeException2() {
        Duktape duktape = Duktape.create(useQuickJS);
        String script = "function() {\n" +
                "function func1() {\n" +
                "throw new Error('duktape!')\n" +
                "}\n" +
                "function func2() {\n" +
                "func1();\n" +
                "}" +
                "function func3() {\n" +
                "func2();\n" +
                "}" +
                "func3();\n" +
                "}\n";
        try {
            JavaScriptObject func = duktape.compileFunction(script, "test.js");
            func.call();
            Assert.fail("failure expected");
        }
        catch (Exception e) {
            Assert.assertTrue(e.getMessage().contains("duktape!"));
            Assert.assertTrue(e.getStackTrace()[0].getMethodName().contains("func1"));
            Assert.assertTrue(e.getStackTrace()[1].getMethodName().contains("func2"));
            Assert.assertTrue(e.getStackTrace()[2].getMethodName().contains("func3"));
        }
        duktape.close();
    }

    void findStack(String[] strs, String regex) {
        Pattern pattern = Pattern.compile(regex);
        for (String ele: strs) {
            if (pattern.matcher(ele.toString()).find())
                return;
        }
        Assert.fail("stack not found: " + regex);
    }
    void findStack(Exception e, String regex) {
        Pattern pattern = Pattern.compile(regex);
        for (StackTraceElement ele: e.getStackTrace()) {
            if (pattern.matcher(ele.toString()).find())
                return;
        }
        Assert.fail("stack not found: " + regex);
    }

    @Test
    public void testDuktapeExceptionFromJava() {
        Duktape duktape = Duktape.create(useQuickJS);
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
            Assert.fail("failure expected");
        }
        catch (Exception e) {
            Assert.assertTrue(e.getMessage().contains("java!"));
            findStack(e, "callback.*?DuktapeTests");
            findStack(e, "func1");
            findStack(e, "func2");
            findStack(e, "func3");
        }
        duktape.close();
    }

    @Test
    public void testDuktapeExceptionMessageFromJava() {
        Duktape duktape = Duktape.create(useQuickJS);
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

        assertTrue(resultHolder.result.contains("java.lang.IllegalArgumentException: java!"));
        duktape.close();
    }

    @Test
    public void testJavaStackInJavaScript() {
        Duktape duktape = Duktape.create(useQuickJS);
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

        String splits[] = ret.split("\n");

        Assert.assertTrue(ret.contains("java!"));
        findStack(splits, "callback.*?DuktapeTests");
        findStack(splits, "func1");
        findStack(splits, "func2");
        findStack(splits, "func3");
        duktape.close();
    }

    interface MarshallCallback {
        void callback(Object o, Boolean B, Short S, Integer I, Long L, Float F, Double D, boolean b, short s, int i, long l, float f, double d, String str);
    }

    @Test
    public void testJavaProxyInDuktapeThreadCrash() {
        Duktape duktape = Duktape.create(false);
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
        duktape.close();
    }

    @Test
    public void testJson() {
        Duktape duktape = Duktape.create(useQuickJS);
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
        Object property = ret.get("meaningOfLife");
        assertTrue(property.equals(42d) || property.equals(42));
        duktape.close();
    }

    @Test
    public void testBufferIn() {
        Duktape duktape = Duktape.create(useQuickJS);

        String script = "function testBuffer(buf) {\n" +
                "\tif (buf.constructor.name !== 'Uint8Array') throw new Error('unexpected type ' + buf.constructor.name);\n" +
                // "\tvar u = new Uint8Array(buf);\n" +
                "\tvar u = buf\n" +
                "\tfor (var i = 0; i < 10; i++) {\n" +
                "\t\tif (u[i] != i)\n" +
                "\t\t\tthrow new Error('expected ' + i);\n" +
                "\t}\n" +
                "\treturn 'done'\n" +
                "}";

        ByteBuffer b = ByteBuffer.allocate(10);
        for (int i = 0; i < 10; i++) {
            b.put(i, (byte)i);
        }
        assertEquals("done", duktape.compileFunction(script, "?").call(b));

        duktape.close();
    }
    @Test
    public void testBufferOut() {
        Duktape duktape = Duktape.create(useQuickJS);

        String script = "function testBuffer(buf) {\n" +
                "\tvar u = new Uint8Array(10);\n" +
                "\tfor (var i = 0; i < 10; i++) {\n" +
                "\t\tu[i] = i;\n" +
                "\t}\n" +
                "\treturn u;\n" +
                "}";

        ByteBuffer b = (ByteBuffer)duktape.coerceJavaScriptToJava(ByteBuffer.class, duktape.compileFunction(script, "?").call());
        for (int i = 0; i < 10; i++) {
            assertEquals(i, b.get(i));
        }

        duktape.close();
    }

    @Test
    public void testSystemOut() {
        Duktape duktape = Duktape.create(useQuickJS);
        duktape.setGlobalProperty("System", System.class);
        duktape.evaluate("System.out.println('hello world');");
        duktape.close();
    }

    public static class RandomObject {
        public RandomObject() {
        }
        public int foo;
        public void setFoo(int foo) {
            this.foo = foo;
        }

        public static int bar;
        public static void setBar(int bar) {
            RandomObject.bar = bar;
        }
    }

    @Test
    public void testNewObject() {
        Duktape duktape = Duktape.create(useQuickJS);
        duktape.setGlobalProperty("RandomObject", RandomObject.class);
        RandomObject ret = duktape.evaluate("var r = new RandomObject(); RandomObject.setBar(5); r.setFoo(3); r;", RandomObject.class);
        assertTrue(ret.foo == 3);
        assertTrue(RandomObject.bar == 5);
        duktape.close();
    }
}
