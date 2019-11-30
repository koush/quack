package com.koushikdutta.quack.polyfill.job;

import com.koushikdutta.scratch.event.Cancellable;

import java.util.Hashtable;

@FunctionalInterface
public interface ClearTimeout {
    void clear(Object timeout);

    static void clear(Object timeout, Hashtable<?, Cancellable> timeouts) {
        if (timeout == null)
            return;
        Cancellable canceller = timeouts.remove(timeout);
        if (canceller != null)
            canceller.cancel();
    }
}
