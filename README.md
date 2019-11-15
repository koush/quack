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

```
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


## Square Duktape-Android

Quack was initially forked from Square's Duktape Android library. But it has been totally rewritten to suit different needs.
