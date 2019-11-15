package com.koushikdutta.quack;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface QuackProperty {
    String name() default "";
}
