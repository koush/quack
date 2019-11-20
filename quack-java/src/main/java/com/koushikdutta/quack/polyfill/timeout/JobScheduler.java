package com.koushikdutta.quack.polyfill.timeout;

import com.koushikdutta.quack.JavaScriptObject;
import com.koushikdutta.quack.QuackContext;

import java.util.Hashtable;

public interface JobScheduler {
    JobCanceller schedule(int timeout, Runnable runnable);
    JobCanceller schedule(Runnable runnable);

    /**
     * Installs setTimeout and setImmediate.
     * Installs a job scheduler for Promises.
     * @param quackContext
     */
    static void install(QuackContext quackContext, JobScheduler scheduler) {
        JavaScriptObject global = quackContext.getGlobalObject();

        {
            Hashtable<Integer, JobCanceller> timeouts = new Hashtable<>();
            global.set("setTimeout", new SetTimeout() {
                int timeoutCount;
                @Override
                public long invoke(JavaScriptObject cb, int milliseconds, Object... params) {
                    int myTimeout = timeoutCount++;
                    JobCanceller canceller = scheduler.schedule(milliseconds, () -> {
                        timeouts.remove(myTimeout);
                        global.call(params);
                    });
                    timeouts.put(myTimeout, canceller);
                    return myTimeout;
                }
            });
            global.set("clearTimeout", (ClearTimeout) timeout -> ClearTimeout.clear(timeout, timeouts));
        }

        {
            Hashtable<Integer, JobCanceller> timeouts = new Hashtable<>();
            global.set("setImmediate", new SetImmediate() {
                int timeoutCount;
                @Override
                public long invoke(JavaScriptObject cb, Object... params) {
                    int myTimeout = timeoutCount++;
                    JobCanceller canceller = scheduler.schedule(() -> {
                        timeouts.remove(myTimeout);
                        global.call(params);
                    });
                    timeouts.put(myTimeout, canceller);
                    return myTimeout;
                }
            });
            global.set("clearImmediate", (ClearTimeout) timeout -> ClearTimeout.clear(timeout, timeouts));
        }

        {
            Hashtable<Integer, JobCanceller> timeouts = new Hashtable<>();
            global.set("setInterval", new SetTimeout() {
                int timeoutCount;
                @Override
                public long invoke(JavaScriptObject cb, int milliseconds, Object... params) {
                    int myTimeout = timeoutCount++;
                    Runnable schedule = new Runnable() {
                        @Override
                        public void run() {
                            JobCanceller canceller = scheduler.schedule(milliseconds, () -> {
                                // reschedule first because the interval may cancel itself after.
                                run();
                                global.call(params);
                            });
                            timeouts.put(myTimeout, canceller);
                        }
                    };

                    schedule.run();
                    return myTimeout;
                }
            });
            global.set("clearInterval", (ClearTimeout) timeout -> ClearTimeout.clear(timeout, timeouts));
        }

        quackContext.setJobExecutor(scheduler::schedule);
    }
}
