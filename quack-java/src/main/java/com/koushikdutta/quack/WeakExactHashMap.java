package com.koushikdutta.quack;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;

public class WeakExactHashMap<K, V> {
    private HashMap<Entry<K>, V> map = new HashMap<>();

    private int purgeThreshold = 0;
    public void setPurgeThreshold(int purgeThreshold) {
        this.purgeThreshold = purgeThreshold;
    }

    private static class Entry<K> {
        WeakReference<K> key;
        int hash;
        public Entry(K key) {
            if (key == null)
                throw new IllegalArgumentException("key can not be null");
            this.key = new WeakReference<>(key);
            hash = key.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            Entry<K> other = (Entry<K>)obj;
            K key = this.key.get();
            return key != null && other.key.get() == key;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private void maybePurge() {
        if (map.size() < purgeThreshold)
            return;

        purge();
    }

    public void purge() {
        int before = map.size();
        Iterator<Entry<K>> iter = map.keySet().iterator();
        while (iter.hasNext()) {
            Entry<K> entry = iter.next();
            if (entry.key.get() == null)
                iter.remove();
        }
        int after = map.size();
//        int purged = before - after;
        setPurgeThreshold(after * 2);
    }

    public V get(K key) {
        maybePurge();
        return map.get(new Entry<>(key));
    }

    public V put(K key, V value) {
        maybePurge();
        return map.put(new Entry<>(key), value);
    }

    public int size() {
        return map.size();
    }
}
