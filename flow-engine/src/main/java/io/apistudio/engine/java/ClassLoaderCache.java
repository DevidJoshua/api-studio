package io.apistudio.engine.java;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache classloader per versi flow (BRD FR-JAVA-05, NFR-06).
 *
 * <p>Kompilasi dan pembuatan classloader mahal, sehingga hasilnya disimpan per
 * kunci versi + node. Ketika versi dilepas (setelah drain), entri dihapus dan
 * classloader ditutup agar kelasnya dapat di-GC.
 */
public final class ClassLoaderCache implements AutoCloseable {

    private final Map<String, SandboxClassLoader> cache = new ConcurrentHashMap<>();
    private final ClassLoader parent;

    public ClassLoaderCache() {
        this(SandboxClassLoader.class.getClassLoader());
    }

    public ClassLoaderCache(ClassLoader parent) {
        this.parent = parent;
    }

    /**
     * Ambil atau buat classloader untuk satu artifact.
     *
     * @param key     kunci versi, mis. {@code apiId/versionId/nodeId}
     * @param factory pembuat classloader bila belum ada
     */
    public SandboxClassLoader getOrCreate(String key, java.util.function.Supplier<SandboxClassLoader> factory) {
        return cache.computeIfAbsent(key, k -> factory.get());
    }

    /** True bila classloader untuk kunci tersebut sudah ada. */
    public boolean contains(String key) {
        return cache.containsKey(key);
    }

    /** Jumlah classloader aktif. */
    public int size() {
        return cache.size();
    }

    /** Lepas classloader untuk satu versi (dipanggil setelah drain). */
    public void evict(String key) {
        SandboxClassLoader loader = cache.remove(key);
        if (loader != null) {
            loader.close();
        }
    }

    /** Lepas seluruh classloader untuk satu awalan kunci (mis. seluruh node pada satu versi). */
    public int evictByPrefix(String prefix) {
        List<String> keys = new ArrayList<>(cache.keySet());
        int removed = 0;
        for (String key : keys) {
            if (key.startsWith(prefix)) {
                evict(key);
                removed++;
            }
        }
        return removed;
    }

    /** Ringkasan isi cache untuk diagnostik. */
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("entries", cache.size());
        Map<String, Integer> classes = new LinkedHashMap<>();
        cache.forEach((key, loader) -> classes.put(key, loader.classCount()));
        stats.put("classes", classes);
        return stats;
    }

    @Override
    public void close() {
        cache.values().forEach(SandboxClassLoader::close);
        cache.clear();
    }

    /** Utilitas dekode Base64 yang dipakai bersama. */
    public static byte[] decodeBase64(String value) {
        return Base64.getDecoder().decode(value);
    }
}
