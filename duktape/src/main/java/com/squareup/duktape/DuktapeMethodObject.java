package com.squareup.duktape;

import java.util.ArrayList;
import java.util.Collections;

public interface DuktapeMethodObject extends DuktapeObject {
    @Override
    default Object get(Object key) {
        DuktapeMethodObject self = this;
        if ("call".equals(key)) {
            return new DuktapeMethodObject() {
                @Override
                public Object callMethod(Object thiz, Object... args) {
                    ArrayList<Object> a = new ArrayList<>();
                    Collections.addAll(a, args);
                    Object thisArg = null;
                    if (!a.isEmpty())
                        thisArg = a.remove(0);
                    return self.callMethod(thisArg, a.toArray());
                }
            };
        }
        else if ("apply".equals(key)) {
            return new DuktapeMethodObject() {
                @Override
                public Object callMethod(Object thiz, Object... args) {
                    ArrayList<Object> a = new ArrayList<>();
                    Collections.addAll(a, args);
                    Object thisArg = null;
                    if (!a.isEmpty())
                        thisArg = a.remove(0);
                    Object[] newArgs;
                    if (a.isEmpty()) {
                        newArgs = new Object[0];
                    }
                    else {
                        JavaScriptObject jarray = (JavaScriptObject)a.remove(0);
                        int length = ((Number)jarray.get("length")).intValue();
                        newArgs = new Object[length];
                        for (int i = 0; i < length; i++) {
                            newArgs[i] = jarray.get(i);
                        }
                    }
                    return self.callMethod(thisArg, newArgs);
                }
            };
        }
        else if ("toString".equals(key)) {
            return new DuktapeMethodObject() {
                @Override
                public Object callMethod(Object thiz, Object... args) {
                    return "function";
                }
            };
        }

        return null;
    }
}
