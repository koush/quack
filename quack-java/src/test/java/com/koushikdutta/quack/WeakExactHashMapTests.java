package com.koushikdutta.quack;

import org.junit.Test;

import java.lang.ref.WeakReference;

import static org.junit.Assert.assertEquals;

public class WeakExactHashMapTests {
    @Test
    public void testMap() {
        Object key = new Object();
        Object value = new Object();
        WeakReference<Object> ref = new WeakReference<>(key);
        WeakExactHashMap<Object, Object> map = new WeakExactHashMap<>();
        map.put(key, value);
        assertEquals(map.get(key), value);
        assertEquals(map.size(), 1);

        System.gc();
        System.gc();
        System.gc();
        System.gc();
        System.gc();
        System.gc();

        assertEquals(map.get(key), value);
        assertEquals(map.size(), 1);

        key = null;
        while (ref.get() != null) {
            System.gc();
        }
        map.purge();
        assertEquals(map.size(), 0);
    }
}
