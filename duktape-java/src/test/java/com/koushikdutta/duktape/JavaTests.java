package com.koushikdutta.duktape;

import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.CoroutineSingletons;

public class JavaTests {
    @Test
    public void testPoop() {
        Poops poops = new Poops();
        Object ret = poops.doThing(new Continuation<Integer>() {
            @NotNull
            @Override
            public CoroutineContext getContext() {
                return EmptyCoroutineContext.INSTANCE;
            }

            @Override
            public void resumeWith(@NotNull Object o) {
                System.out.println(o);
            }
        });
        if (ret == CoroutineSingletons.COROUTINE_SUSPENDED) {
            System.out.println("SUP");
        }
    }
}
