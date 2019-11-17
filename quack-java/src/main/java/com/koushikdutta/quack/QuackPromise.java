package com.koushikdutta.quack;

public interface QuackPromise {
    QuackPromise then(QuackPromiseReceiver receiver);
    @QuackMethodName(name = "catch")
    QuackPromise caught(QuackPromiseReceiver receiver);
}
