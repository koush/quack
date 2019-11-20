package com.koushikdutta.quack.polyfill.timeout;

import java.util.Hashtable;

interface ClearTimeout {
    void clear(int timeout);

    static void clear(int timeout, Hashtable<Integer, JobCanceller> timeouts) {
        JobCanceller canceller = timeouts.remove(timeout);
        if (canceller != null)
            canceller.cancel();
    }
}
