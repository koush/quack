package com.koushikdutta.quack;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class QuackTests {
    private static boolean useQuickJS = true;
    static {
        // for non-android jvm
        try {
            System.load(new File("quack-jni/build/lib/main/debug/libquack-jni.dylib").getCanonicalPath());
        }
        catch (IOException e) {
        }
        catch (UnsatisfiedLinkError e) {
        }
        try {
            System.load(new File("../quack-jni/build/lib/main/debug/libquack-jni.dylib").getCanonicalPath());
        }
        catch (IOException e) {
        }
        catch (UnsatisfiedLinkError e) {
        }
    }

    // takes a long time. Duktape does not pass due to a const limit. quickjs works.
    // @Test
    public void testOctane() throws IOException {
        QuackContext quack = QuackContext.create(false);
        File files[] = new File("/Volumes/Dev/Scrypted/quack.android/tests/src/main/assets/octane").listFiles();
        Arrays.sort(files, (a, b) -> a.getAbsolutePath().compareTo(b.getAbsolutePath()));
        for (File file: files) {
            String script = StreamUtility.readFile(file);
            quack.evaluate(script, file.getAbsolutePath());
        }
        String script = StreamUtility.readFile("/Volumes/Dev/Scrypted/quack.android/tests/src/main/assets/octane.js");
        quack.evaluate(script);
        String ret = quack.evaluateForJavaScriptObject("getResults").call().toString();
        System.out.println(ret);
        quack.close();
    }

    @Test
    public void testQuickJSExceptionWithTemplateArgs() {
        QuackContext quack = QuackContext.create(true);
        quack.evaluate("(function(){function tcp(str) {return `_${str}._tcp`;}})", "script.js");
        quack.close();
    }

    public class Console {
        QuackContext quack;
        PrintStream out;
        PrintStream err;
        public Console(QuackContext quack, PrintStream out, PrintStream err) {
            this.quack = quack;
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

        @QuackMethodName(name = "assert")
        public void assert_(Object... objects) {
            err.println(getLog(objects));
        }
    }


    private static class ResultHolder<T> {
        public T result;
    }

    @Test
    public void testGlobal() {
        QuackContext quack = QuackContext.create(true);
        JavaScriptObject global = quack.getGlobalObject();
        global.set("hello", "world");
        global.set("thing", new Object());
        quack.close();
    }

    @Test
    public void testConsole() {
        QuackContext quack = QuackContext.create(useQuickJS);
        JavaScriptObject global = quack.getGlobalObject();
        global.set("console", new Console(quack, System.out, System.err));
        quack.evaluate("console.log('hello.');");
        quack.close();
    }

    @Test
    public void testRoundtrip() {
        QuackContext quack = QuackContext.create(false);
        String script = "function(ret) { return ret; }";
        JavaScriptObject func = quack.compileFunction(script, "?");

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
        quack.close();
    }

    interface Callback {
        void callback();
    }

    @Test
    public void testMethodCall() {
        ResultHolder<Boolean> resultHolder = new ResultHolder<>();
        Callback cb = () -> resultHolder.result = true;

        QuackContext quack = QuackContext.create(useQuickJS);
        String script = "function(cb) { cb.callback() }";
        JavaScriptObject func = quack.compileFunction(script, "?");

        func.call(cb);

        assertTrue(resultHolder.result);
        quack.close();
    }

    @Test
    public void testCallback() {
        ResultHolder<Boolean> resultHolder = new ResultHolder<>();
        Callback cb = () -> resultHolder.result = true;

        QuackContext quack = QuackContext.create(useQuickJS);
        String script = "function(cb) { cb() }";
        JavaScriptObject func = quack.compileFunction(script, "?");

        func.call(quack.coerceJavaToJavaScript(Callback.class, cb));

        assertTrue(resultHolder.result);
        quack.close();
    }
    

    interface RoundtripCallback {
        Object callback(Object o);
    }

    @Test
    public void testInterfaceReturn() {
        QuackContext quack = QuackContext.create(useQuickJS);
        String script = "function() {" +
                "function RoundtripCallback() {" +
                "}" +
                "RoundtripCallback.prototype.callback = function(o) {" +
                "return o;" +
                "};" +
                "return new RoundtripCallback();" +
                "}";
        JavaScriptObject func = quack.compileFunction(script, "?");
        RoundtripCallback cb = ((JavaScriptObject)func.call()).proxyInterface(RoundtripCallback.class);

        quack.close();
    }

    @Test
    public void testInterface() {
        QuackContext quack = QuackContext.create(useQuickJS);
        String script = "function() {" +
                "function RoundtripCallback() {" +
                "}" +
                "RoundtripCallback.prototype.callback = function(o) {" +
                "return o;" +
                "};" +
                "return new RoundtripCallback();" +
                "}";
        JavaScriptObject func = quack.compileFunction(script, "?");
        RoundtripCallback cb = ((JavaScriptObject)func.call()).proxyInterface(RoundtripCallback.class);

        // should all come back as doubles.
        List<Object> values = Arrays.asList((byte)0, (short)0, 0, 0f, 0d, 0L);
        for (Object value: values) {
            Object ret = cb.callback(value);
            assertTrue(ret instanceof Double || ret instanceof Integer);
        }

        values = Arrays.asList("0");
        for (Object value: values) {
            Object ret = cb.callback(value);
            assertTrue(ret instanceof String);
        }
        quack.close();
    }

    interface InterfaceCallback {
        void callback(Callback o);
    }

    @Test
    public void testCallbackInterface() {
        ResultHolder<Boolean> resultHolder = new ResultHolder<>();
        Callback cb = () -> resultHolder.result = true;

        QuackContext quack = QuackContext.create(useQuickJS);
        String script = "function() {" +
                "function RoundtripCallback() {" +
                "}" +
                "RoundtripCallback.prototype.callback = function(cb) {" +
                "cb();" +
                "};" +
                "return new RoundtripCallback();" +
                "}";
        JavaScriptObject func = quack.compileFunction(script, "?");
        InterfaceCallback iface = ((JavaScriptObject)func.call()).proxyInterface(InterfaceCallback.class);
        iface.callback(cb);

        assertTrue(resultHolder.result);
        quack.close();
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

        QuackContext quack = QuackContext.create(useQuickJS);
        String script = "function() {" +
                "function RoundtripCallback() {" +
                "}" +
                "RoundtripCallback.prototype.callback = function(o, cb) {" +
                "return cb(o);" +
                "};" +
                "return new RoundtripCallback();" +
                "}";
        JavaScriptObject func = quack.compileFunction(script, "?");
        RoundtripInterfaceCallback iface = ((JavaScriptObject)func.call()).proxyInterface(RoundtripInterfaceCallback.class);

        // should all come back as doubles.
        List<Object> values = Arrays.asList((byte)0, (short)0, 0, 0f, 0d);
        for (Object value: values) {
            Object ret = iface.callback(value, cb);
            assertTrue(ret instanceof Double || ret instanceof Integer);
        }
        assertTrue(resultHolder.result == 5);
        quack.close();
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

        QuackContext quack = QuackContext.create(useQuickJS);
        String script = "function() {" +
                "function RoundtripCallback() {" +
                "}" +
                "RoundtripCallback.prototype.callback = function(o, cb) {" +
                "return cb(o);" +
                "};" +
                "return new RoundtripCallback();" +
                "}";
        JavaScriptObject func = quack.compileFunction(script, "?");
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
        quack.close();
    }

    enum Foo {
        Bar,
        Baz,
    }

    @Test
    public void testEnumRoundtrip() {
        QuackContext quack = QuackContext.create(useQuickJS);
        String script = "function(ret) { return ret; }";
        JavaScriptObject func = quack.compileFunction(script, "?");

        // should all come back as strings.
        Object[] values = Foo.values();
        for (Object value: values) {
            Object ret = func.call(value);
            assertTrue(ret instanceof String);
            assertTrue(quack.coerceJavaScriptToJava(Foo.class, ret) instanceof Foo);
        }
        quack.close();
    }

    interface EnumInterface {
        Foo callback(Foo foo);
    }

    @Test
    public void testEnumInterface() {
        QuackContext quack = QuackContext.create(useQuickJS);
        String script = "function() {" +
                "function RoundtripCallback() {" +
                "}" +
                "RoundtripCallback.prototype.callback = function(o) {" +
                "return o;" +
                "};" +
                "return new RoundtripCallback();" +
                "}";
        JavaScriptObject func = quack.compileFunction(script, "?");
        EnumInterface cb = ((JavaScriptObject)func.call()).proxyInterface(EnumInterface.class);

        // should all come back as Foos.
        List<Foo> values = Arrays.asList(Foo.values());
        for (Foo value: values) {
            Object ret = cb.callback(value);
            assertNotNull(ret);
        }
        quack.close();
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

        QuackContext quack = QuackContext.create(useQuickJS);
        String script = "function() {" +
                "function RoundtripCallback() {" +
                "}" +
                "RoundtripCallback.prototype.callback = function(o, cb) {" +
                "return cb(o);" +
                "};" +
                "return new RoundtripCallback();" +
                "}";
        JavaScriptObject func = quack.compileFunction(script, "?");
        RoundtripEnumInterfaceCallback iface = ((JavaScriptObject)func.call()).proxyInterface(RoundtripEnumInterfaceCallback.class);

        // should all come back as Foos.
        List<Foo> values = Arrays.asList(Foo.values());
        for (Foo value: values) {
            Foo ret = iface.callback(value, cb);
            assertNotNull(ret);
        }
        assertTrue(resultHolder.result == 2);
        quack.close();
    }

    @Test
    public void testDuktapeException() {
        QuackContext quack = QuackContext.create(useQuickJS);
        String script = "function() {" +
                "function func1() {" +
                "throw new Error('quack.')" +
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
            JavaScriptObject func = quack.compileFunction(script, "test.js");
            func.call();
            Assert.fail("failure expected");
        }
        catch (Exception e) {
            Assert.assertTrue(e.getMessage().contains("quack."));
            Assert.assertTrue(e.getStackTrace()[0].getMethodName().contains("func1"));
            Assert.assertTrue(e.getStackTrace()[1].getMethodName().contains("func2"));
            Assert.assertTrue(e.getStackTrace()[2].getMethodName().contains("func3"));
        }
        quack.close();
    }

    @Test
    public void testDuktapeException2() {
        QuackContext quack = QuackContext.create(useQuickJS);
        String script = "function() {\n" +
                "function func1() {\n" +
                "throw new Error('quack.')\n" +
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
            JavaScriptObject func = quack.compileFunction(script, "test.js");
            func.call();
            Assert.fail("failure expected");
        }
        catch (Exception e) {
            Assert.assertTrue(e.getMessage().contains("quack."));
            Assert.assertTrue(e.getStackTrace()[0].getMethodName().contains("func1"));
            Assert.assertTrue(e.getStackTrace()[1].getMethodName().contains("func2"));
            Assert.assertTrue(e.getStackTrace()[2].getMethodName().contains("func3"));
        }
        quack.close();
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
        QuackContext quack = QuackContext.create(useQuickJS);
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

            JavaScriptObject func = quack.compileFunction(script, "?");
            func.call(cb);
            Assert.fail("failure expected");
        }
        catch (Exception e) {
            Assert.assertTrue(e.getMessage().contains("java!"));
            findStack(e, "callback.*?QuackTests");
            findStack(e, "func1");
            findStack(e, "func2");
            findStack(e, "func3");
        }
        quack.close();
    }

    @Test
    public void testDuktapeExceptionMessageFromJava() {
        QuackContext quack = QuackContext.create(useQuickJS);
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

        JavaScriptObject func = quack.compileFunction(script, "?");
        func.call(cb, cb2);

        assertTrue(resultHolder.result.contains("java.lang.IllegalArgumentException: java!"));
        quack.close();
    }

    @Test
    public void testJavaStackInJavaScript() {
        QuackContext quack = QuackContext.create(useQuickJS);
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

        JavaScriptObject func = quack.compileFunction(script, "?");
        String ret = func.call(cb).toString();

        String splits[] = ret.split("\n");

        Assert.assertTrue(ret.contains("java!"));
        findStack(splits, "callback.*?QuackTests");
        findStack(splits, "func1");
        findStack(splits, "func2");
        findStack(splits, "func3");
        quack.close();
    }

    interface MarshallCallback {
        void callback(Object o, Boolean B, Short S, Integer I, Long L, Float F, Double D, boolean b, short s, int i, long l, float f, double d, String str);
    }

    @Test
    public void testJavaProxyInDuktapeThreadCrash() {
        QuackContext quack = QuackContext.create(false);
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

            JavaScriptObject func = quack.compileFunction(script, "?");
            func.call(cb, o, (Boolean)true, (Short)(short)2, (Integer)2, (Long)2L, (Float)2f, (Double)2d, true, (short)2, 2, 2L, 2f, 2d, "test");
        }
        catch (Exception e) {
            Assert.assertTrue(e.getMessage().contains("java!"));
        }
        quack.close();
    }

    @Test
    public void testJson() {
        QuackContext quack = QuackContext.create(useQuickJS);
        String script = "function() {" +
                "function RoundtripCallback() {" +
                "}" +
                "RoundtripCallback.prototype.callback = function(o) {" +
                "return o;" +
                "};" +
                "return new RoundtripCallback();" +
                "}";
        JavaScriptObject func = quack.compileFunction(script, "?");
        RoundtripCallback cb = ((JavaScriptObject)func.call()).proxyInterface(RoundtripCallback.class);

        JavaScriptObject ret = (JavaScriptObject)cb.callback(new QuackJsonObject("{\"meaningOfLife\":42}"));
        Object property = ret.get("meaningOfLife");
        assertTrue(property.equals(42d) || property.equals(42));
        quack.close();
    }

    @Test
    public void testBufferIn() {
        QuackContext quack = QuackContext.create(useQuickJS);

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
        assertEquals("done", quack.compileFunction(script, "?").call(b));

        quack.close();
    }
    @Test
    public void testBufferOut() {
        QuackContext quack = QuackContext.create(useQuickJS);

        String script = "function testBuffer(buf) {\n" +
                "\tvar u = new Uint8Array(10);\n" +
                "\tfor (var i = 0; i < 10; i++) {\n" +
                "\t\tu[i] = i;\n" +
                "\t}\n" +
                "\treturn u;\n" +
                "}";

        ByteBuffer b = (ByteBuffer)quack.coerceJavaScriptToJava(ByteBuffer.class, quack.compileFunction(script, "?").call());
        for (int i = 0; i < 10; i++) {
            assertEquals(i, b.get(i));
        }

        quack.close();
    }

    @Test
    public void testBufferInArray() {
        QuackContext quack = QuackContext.create(useQuickJS);

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
        assertEquals("done", quack.compileFunction(script, "?").call(b));
        assertTrue(!b.hasRemaining());

        quack.close();
    }

    @Test
    public void testSystemOut() {
        QuackContext quack = QuackContext.create(useQuickJS);
        JavaScriptObject global = quack.getGlobalObject();
        global.set("System", System.class);
        quack.evaluate("System.out.println('hello world');");
        quack.close();
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
        QuackContext quack = QuackContext.create();
        JavaScriptObject global = quack.getGlobalObject();
        global.set("RandomObject", RandomObject.class);
        RandomObject ret = quack.evaluate("var r = new RandomObject(); RandomObject.setBar(5); r.setFoo(3); r;", RandomObject.class);
        assertTrue(ret.foo == 3);
        assertTrue(RandomObject.bar == 5);
        quack.close();
    }

    @Test
    public void testCoercion() {
        QuackContext quack = QuackContext.create(useQuickJS);
        quack.putJavaToJavaScriptCoercion(Foo.class, (clazz, o) -> "hello world");
    }

    @Test
    public void testPromise() throws InterruptedException {
        QuackContext quack = QuackContext.create();

        String script = "new Promise((resolve, reject) => { resolve('hello'); });";
        JavaScriptObject jo = quack.evaluateForJavaScriptObject(script);
        QuackPromise promise = jo.proxyInterface(QuackPromise.class);

        Semaphore semaphore = new Semaphore(0);
        promise.then(new QuackPromiseReceiver(){
            @Override
            public void receive(Object o) {
                semaphore.release();
            }
        });

        assertTrue(semaphore.tryAcquire(500, TimeUnit.MILLISECONDS));
    }

    public static class Foo2 {
        public void hello(String str) {
            System.out.println(str);
        }
    }

    // this fails on android, probably something to do with the class loader.
    @Test
    public void testClassCreation() throws ClassNotFoundException {
        String script =
                "var Foo2 = JavaClass.forName('com.koushikdutta.quack.QuackTests$Foo2');\n" +
                        "var foo = new Foo2();\n" +
                        "foo.hello('hello world');\n";

        QuackContext quack = QuackContext.create();
        JavaScriptObject global = quack.getGlobalObject();
        global.set("JavaClass", Class.class);
        quack.evaluate(script);
        quack.close();
    }

    // unlike above, this works on android, because the class is provided directly.
    @Test
    public void testClassCreationExplicit() throws ClassNotFoundException {
        String script =
                        "var foo = new Foo2();\n" +
                        "foo.hello('hello world');\n";

        QuackContext quack = QuackContext.create();
        JavaScriptObject global = quack.getGlobalObject();
        global.set("Foo2", Foo2.class);
        quack.evaluate(script);
        quack.close();
    }

    interface TestJS {
        String foo();
    }

    @Test
    public void testJavaScriptObjectCallCoercion() {
        QuackContext quack = QuackContext.create(useQuickJS);
        String script = "function() { return (function() { return 'HI'; }) }";
        JSValue func = quack.compileFunction(script, "?").asJSValue();
        TestJS foo = func.apply(null).as(TestJS.class);
        assertEquals(foo.foo(), "HI");
    }

    interface TestJS2 {
        String foo1();
        String foo2();
    }

    @Test
    public void testJavaScriptObjectCallCoercion2() {
        QuackContext quack = QuackContext.create(useQuickJS);
        String script = "function() { return { foo1: function() { return 'HI'; }, foo2: function() { return 'BYE'; } } }";
        JSValue func = quack.compileFunction(script, "?").asJSValue();
        TestJS2 foo = func.apply(null).as(TestJS2.class);
        assertEquals(foo.foo1(), "HI");
        assertEquals(foo.foo2(), "BYE");
    }

    @Test
    public void testRunnable() {
        QuackContext quack = QuackContext.create(useQuickJS);
        quack.getGlobalObject().set("setTimeout", quack.coerceJavaToJavaScript(Runnable.class, new Runnable() {
            int foo = 34;
            @Override
            public void run() {
                System.out.println(foo);
            }
        }));
        quack.evaluate("setTimeout()");
        quack.close();
    }

    @Test
    public void testRunnableApply() {
        QuackContext quack = QuackContext.create(useQuickJS);
        quack.getGlobalObject().set("setTimeout", quack.coerceJavaToJavaScript(Runnable.class, new Runnable() {
            int foo = 34;
            @Override
            public void run() {
                System.out.println(foo);
            }
        }));
        quack.evaluate("setTimeout.apply(null)");
        quack.close();
    }


    public static class FieldTest {
        public int foo;
        public static int staticFoo;
    }
    @Test
    public void testFields() {
        QuackContext quack = QuackContext.create(useQuickJS);
        FieldTest test = new FieldTest();
        quack.getGlobalObject().set("test", test);
        quack.getGlobalObject().set("testClass", FieldTest.class);
        quack.evaluate("test.foo = 3; testClass.staticFoo = 4;");
        quack.close();

        assertEquals(test.foo, 3);
        assertEquals(FieldTest.staticFoo, 4);
    }

    public interface VarArgTest {
        Object invoke(Object... args);
    }

    @Test
    public void testVarArgs() {
        QuackContext quack = QuackContext.create();
        String scriptString = "(function(a, b, c, d) { return c; })";
        VarArgTest test = quack.evaluate(scriptString, VarArgTest.class);
        assertEquals(test.invoke("a", "b", "c"), "c");
        quack.close();
    }

    @Test
    public void testClassBug() {
        QuackContext quack = QuackContext.create();
        StringBuilder builder = new StringBuilder();
        builder.append("(function(cb) {\n");
        builder.append("class Foo{};\n");
        builder.append("class Bar extends Foo{};\n");
        builder.append("cb(Foo, Bar);\n");
        builder.append("}\n");
        builder.append(")\n");

        JavaScriptObject jo = quack.evaluateForJavaScriptObject(builder.toString());

        jo.call(new QuackMethodObject() {
            @Override
            public Object callMethod(Object thiz, Object... args) {
                return null;
            }
        });

        quack.close();
    }

    @Test
    public void testConstruct() {
        QuackContext quack = QuackContext.create(useQuickJS);
        quack.getGlobalObject().set("Test", new QuackObject() {
            @Override
            public Object construct(Object... args) {
                return new Object();
            }
        });

        quack.evaluate("new Test()");
        quack.close();
    }

    @Test
    public void testJsConstruct() {
        QuackContext quack = QuackContext.create(useQuickJS);
        quack.getGlobalObject().set("observe", quack.coerceJavaToJavaScript(RoundtripCallback.class, new RoundtripCallback() {
            @Override
            public Object callback(Object o) {
                return null;
            }
        }));

        JavaScriptObject Foo = quack.evaluateForJavaScriptObject("(function Foo(b) { observe(arguments.length); this.b = b } )");
        JavaScriptObject foo = (JavaScriptObject)Foo.construct("hello");
        assertEquals(foo.get("b"), "hello");
        quack.close();
    }

    @Test
    public void testTypeOf() {
        QuackContext quack = QuackContext.create(useQuickJS);
        assertEquals("object", quack.evaluateForJavaScriptObject("({})").typeof());
        assertEquals("function", quack.evaluateForJavaScriptObject("(function(){})").typeof());
        quack.close();
    }

    @Test
    public void testMarshalledInterfaceRoundtripMarshalling() {
        QuackContext quack = QuackContext.create(useQuickJS);
        RoundtripCallback cb = new RoundtripCallback() {
            @Override
            public Object callback(Object o) {
                // returning a proxied interface should get turned back into the same javascript object that
                // is being proxied by that interface.
                Callback c = (Callback)quack.coerceJavaScriptToJava(Callback.class, o);
                c.callback();
                return c;
            }
        };

        quack.evaluateForJavaScriptObject("(function(cb) { var f = function(){}; if (cb.callback(f) !== f) throw new Error('expected same object'); })").call(cb);
        quack.close();
    }

    @Test
    public void testErrorExcepionCoercion() {
        QuackContext quack = QuackContext.create(useQuickJS);
        try {
            quack.evaluateForJavaScriptObject("(function(t) {\n" +
            "function foo1() {" +
            "throw t.callback();\n" +
            "}\n" +
            "function foo2() {" +
            "foo1();\n" +
            "}\n" +
            "foo2();" +
            "});\n")
            .call(new RoundtripCallback() {
                @Override
                public Object callback(Object o) {
                    // create an error object from an exception for the
                    // script to throw.
                    Exception bad = new RuntimeException("bad");
                    JavaScriptObject jo = quack.newError(bad);
                    return jo;
                }
            });
        }
        catch (Exception e) {
            // the exception should be the one originally thrown,
            // including the js stack
            assertTrue(e instanceof RuntimeException);
            findStack(e, "foo1");
            findStack(e, "foo2");
            return;
        }
        fail("exception was expected");
        quack.close();
    }

    public static interface InterfaceWithProperty {
        @QuackProperty(name = "foo")
        Object getFoo();
        @QuackProperty(name = "foo")
        void setFoo(Object foo);
    }

    @Test
    public void testJavaScriptProperty() {
        QuackContext quack = QuackContext.create(useQuickJS);
        InterfaceWithProperty test = quack.evaluate(InterfaceWithProperty.class, "({ foo: 'hello' })", "?");
        assertEquals(test.getFoo(), "hello");
        test.setFoo("goober");
        assertEquals(test.getFoo(), "goober");
        quack.close();
    }

    // disabled because tracking memory pointers is expensive.
//    @Test
    public void testNativeArrayBufferSame() {
        QuackContext quack = QuackContext.create(useQuickJS);
        JavaScriptObject jo = quack.evaluateForJavaScriptObject("(function(cb) { var a = new ArrayBuffer(10); cb.callback(a); cb.callback(a); })");

        jo.call(new RoundtripCallback() {
            Object last;
            @Override
            public Object callback(Object o) {
                assertTrue(last == o || last == null);
                assertTrue(o instanceof ByteBuffer);
                last = o;
                return null;
            }
        });

        quack.close();
    }

    // disabled because tracking memory pointers is expensive.
//    @Test
    public void testNativeArrayBufferSameAndPositionResets() {
        QuackContext quack = QuackContext.create(useQuickJS);
        JavaScriptObject jo = quack.evaluateForJavaScriptObject("(function(cb) { var a = new ArrayBuffer(10); cb.callback(a); cb.callback(a); })");

        jo.call(new RoundtripCallback() {
            Object last;
            @Override
            public Object callback(Object o) {
                assertTrue(last == o || last == null);
                ByteBuffer bb = (ByteBuffer)o;
                assert(bb.remaining() == bb.capacity());
                bb.get(new byte[bb.remaining()]);
                last = o;
                return null;
            }
        });

        quack.close();
    }

    @Test
    public void testArrayBufferSameFromJava() {
        QuackContext quack = QuackContext.create(useQuickJS);
        Object ab = quack.evaluate("(new ArrayBuffer(10))");
        JavaScriptObject jo = quack.evaluateForJavaScriptObject("var last = null; function checker(ab) { if (last != null && last != ab) throw new Error('arraybuffer mismatch'); last = ab; }; checker;");
        jo.call(ab);
        jo.call(ab);
        quack.close();
    }

    @Test
    public void testModule() {
        QuackContext quack = QuackContext.create(useQuickJS);
        JavaScriptObject jo = quack.evaluateModule("import * as std from 'std';");
        // at the moment modules return null. there doesn't seem to be a way to get the exports out.
        assertNull(jo);
        quack.close();
    }

    @Test
    public void testIterable() {
        QuackContext quack = QuackContext.create(useQuickJS);
        JSValue value = quack.evaluateForJavaScriptObject("([2,3,4,5])").asJSValue();
        int total = 0;
        for (int i: value.asIterable(int.class)) {
            total += i;
        }
        assertEquals(total, 14);
        quack.close();
    }

    interface ArrayInterface {
        int[] getNumbers();
    }

    @Test
    public void testArray() {
        QuackContext quack = QuackContext.create(useQuickJS);
        ArrayInterface iface = quack.evaluate("(function() { return [2, 3, 4, 5] })", ArrayInterface.class);
        int total = 0;
        for (int i: iface.getNumbers()) {
            total += i;
        }
        assertEquals(total, 14);
        quack.close();
    }

    @Test
    public void testDotConstructBug() {
        QuackContext quack = QuackContext.create(useQuickJS);
        quack.getGlobalObject().set("global", quack.getGlobalObject());
        QuackObject Test = new QuackObject() {
            @Override
            public Object construct(Object... args) {
                return new Object();
            }
        };
        quack.getGlobalObject().set("Test", Test);

        assertEquals(quack.getGlobalObject().get("Test"), Test);
        quack.evaluate("new Test()");
        assertEquals(quack.getGlobalObject().get("Test"), Test);
        quack.close();
    }

    @Test
    public void testLong() {
        QuackContext quack = QuackContext.create(useQuickJS);
        JavaScriptObject jo = quack.evaluateForJavaScriptObject("(function(o){ return o; })");
        long value = 4000000000L;
        Object ret = quack.coerceJavaScriptToJava(long.class, jo.call(value));
        assertEquals(value, ret);
    }

    @Test
    public void testHeap() {
        QuackContext quack = QuackContext.create(useQuickJS);

        ByteBuffer buffer = ByteBuffer.allocateDirect(10000000);
        String script = "function(ret) { return ret; }";
        JavaScriptObject func = quack.compileFunction(script, "?");

        ByteBuffer ret = (ByteBuffer)func.call(buffer);
        assertSame(buffer, ret);
        quack.close();
    }
}
