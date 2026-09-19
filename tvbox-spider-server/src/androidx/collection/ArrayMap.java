package androidx.collection;

import java.util.LinkedHashMap;

/**
 * androidx.collection.ArrayMap shim。
 * 部分 spider 会调用 com.github.catvod.net.OkHttp 的 ArrayMap 重载，为保证签名一致而提供。
 * 语义上 ArrayMap 就是 Map，这里用 LinkedHashMap 承载（保持插入序）。
 */
public class ArrayMap<K, V> extends LinkedHashMap<K, V> {

    public ArrayMap() {
        super();
    }

    public ArrayMap(int capacity) {
        super(Math.max(4, capacity));
    }

    public ArrayMap(ArrayMap<K, V> map) {
        super(map);
    }

    public void ensureCapacity(int minimumCapacity) {
    }

    public int indexOfKey(Object key) {
        int index = 0;
        for (K k : keySet()) {
            if (k == null ? key == null : k.equals(key)) return index;
            index++;
        }
        return -1;
    }

    public K keyAt(int index) {
        int i = 0;
        for (K k : keySet()) {
            if (i++ == index) return k;
        }
        return null;
    }

    public V valueAt(int index) {
        int i = 0;
        for (V v : values()) {
            if (i++ == index) return v;
        }
        return null;
    }
}
