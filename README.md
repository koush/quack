# Quack

Quack provides Java (Android and desktop) bindings to JavaScript engines. 

## Runtimes

Quack supports both the Duktape and QuickJS runtimes.

## Features

* Share objects between runtimes seamlessly.
* Javascript Debugging support
* Invocations can be intercepted and coerced both in and out of the JavaScript runtime.

## Examples

### Evaluating JavaScript

#### JavaScript
```javascript
'hello'
```

#### Java:

Calling QuackContext.evaluate will return a Java Object with the evaluation result.

```java
QuackContext quack = QuackContext.create();
Object result = quack.evaluate(javascriptString);
System.out.println(result);
// prints "hello"
```

### Evaluating and Calling JavaScript Functions

#### JavaScript
```javascript
(function () {
  return 'hello';
})
```
#### Java
```java
QuackContext quack = QuackContext.create();
JavaScriptObject result = quack.evaluateForJavaScriptObject(javascriptString);
System.out.println(result.call());
// prints "hello"
```

### Passing Data to JavaScript

#### JavaScript

```javascript
(function (str) {
  return str + ' world';
})
```
#### Java
```java
QuackContext quack = QuackContext.create();
JavaScriptObject result = quack.evaluateForJavaScriptObject(javascriptString);
System.out.println(result.call("hello"));
// prints "hello world"
```


### Setting and using Global Properties

#### JavaScript

```javascript
System.out.println('hello world');
```
#### Java
```java
QuackContext quack = QuackContext.create();
quack.setGlobalProperty("System", System.class);
quack.evaluate(javascriptString);
// prints "hello world"
```

### Passing Objects to JavaScript

#### JavaScript
```javascript
(function(obj) {
  obj.hello();
})
```
#### Java
```java
class Foo {
  public void hello() {
    System.out.println("hello");
  }
}

QuackContext quack = QuackContext.create();
JavaScriptObject result = quack.evaluateForJavaScriptObject(javascriptString);
quack.call(new Foo());
// prints "hello world"
```

### Passing Interfaces to JavaScript

#### JavaScript
```javascript
(function(func) {
  // notice that it is not func.run()
  // single method interfaces (lambdas) are automatically coerced into functions!
  func();
})
```
#### Java
```java
Runnable runnable = () -> {
  System.out.println("hello world");
}

QuackContext quack = QuackContext.create();
JavaScriptObject result = quack.evaluateForJavaScriptObject(javascriptString);
result.call(runnable);
// prints "hello world"
```

### Passing Interfaces back to Java
#### JavaScript
```javascript
return {
  hello: function(printer) {
    printer('hello world');
  }
}
```
#### Java
```java
interface Foo {
  void hello(Printer printer);
}

interface Printer {
  print(String str);
}

QuackContext quack = QuackContext.create();
Foo result = quack.evaluate(javascriptString, Foo.class);  
result.hello(str -> System.out.println(str));
// prints "hello world"
```

### Creating Java Objects in JavaScript
#### JavaScript
```javascript
var foo = new Foo();
foo.hello('hello world');
```
#### Java
```java
class Foo {
  public void hello(String str) {
    System.out.println(str);
  }
}

QuackContext quack = QuackContext.create();
quack.setGlobalProperty("Foo", Foo.class);
quack.evaluate(javascriptString);
// prints "hello world"
```

### Creating Java Objects in JavaScript (simplified)
#### JavaScript
```javascript
var Foo = JavaClass.forName("com.whatever.Foo");
var foo = new Foo();
foo.hello('hello world');
```
#### Java
```java
class Foo {
  public void hello(String str) {
    System.out.println(str);
  }
}

QuackContext quack = QuackContext.create();
quack.setGlobalProperty("JavaClass", Class.class);
quack.evaluate(javascriptString);
// prints "hello world"
```

## Marshalling

Types need to be marshalled when passing between the runtimes. The class specifier and parameter types
are used to determine the behavior when being marshalled. The following builtin types are marshalled as follows:

JavaScript (In) | Java (Out)
|---|---|
number | Integer or Double
Uint8Array | ByteBuffer (direct, deep copy)
undefined | null

Java (In) | JavaScript (Out)
|---|---|
long | string (otherwise precision is lost)
ByteBuffer (direct or byte array backed) | Uint8Array (deep copy)
byte, short, int, float, double | number
null | null


### Coercions

Types and methods can be coerced between runtimes.

### Java to JavaScript Type Coercion
#### JavaScript
```javascript
(function(data) {
  return data;
})
```
#### Java
```java
class Foo {}

QuackContext quack = QuackContext.create();
// all instances of Foo sent to JavaScript get coerced into the String "hello world"
quack.putJavaToJavaScriptCoercion(Foo.class, (clazz, o) -> "hello world");
System.out.println(quack.evaluateForJavaScriptObject.call(new Foo()));
// prints "hello world"
```

## Concurrency

JavaScript runtimes are single threaded. All execution in the JavaScript runtime is gauranteed thread safe, by way of Java synchronization.

## Garbage Collection

When a Java object is passed to the JavaScript runtime, a hard reference is held by the JavaScript proxy counterpart. This reference is removed when the JavaScriptObject is finalized. And same for when a Java object is passed to the JavaScript runtime.
JavaScriptObjects sent to the Java runtime will be deduped, so the same proxy instance is always used. JavaObjects sent to JavaScript will marshall a new Proxy object every time.

## Debugging

Install the [QuickJS Debug Extension](https://marketplace.visualstudio.com/items?itemName=koush.quickjs-debug) for VS Code.

### JavaScript
```javascript
System.out.println('set a breakpoint here!');
```

### Java
```java
QuackContext quack = QuackContext.create();
quack.setGlobalProperty("System", System.class);
quack.waitForDebugger("0.0.0.0:9091")
// attach using VS Code
quack.evaluate(javascriptString);
```

## Square Duktape-Android

Quack was initially forked from Square's Duktape Android library. But it has been totally rewritten to suit different needs.
