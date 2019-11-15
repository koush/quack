# Quack

Quack provides Java (Android and desktop) bindings to JavaScript engines. 

## Features

* Share objects between runtimes seamlessly.
* Javascript Debugging support
* Invocations can be intercepted and coerced both in and out of the JavaScript runtime.
* Custom

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
quack.setGlobalProperty(System.class);
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
  // notice that it is not func.run.
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
quack.call(runnable);
// prints "hello world"
```



## Square Duktape-Android

Quack was initially forked from Square's Duktape Android library. But it has been totally rewritten to suit different needs.
