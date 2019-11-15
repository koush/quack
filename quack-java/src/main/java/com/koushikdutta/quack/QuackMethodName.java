package com.koushikdutta.quack;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface QuackMethodName {
    String name() default "";
}
