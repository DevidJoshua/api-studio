package io.apistudio.engine.java;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Classloader terisolasi per versi flow (BRD FR-JAVA-05, NFR-06).
 *
 * <p>Karakteristik:
 * <ul>
 *   <li><b>Child-first</b> untuk kelas pengguna dan library yang diunggah,
 *       sehingga library pengguna menang atas classpath platform (mencegah
 *       konflik versi antar flow).</li>
 *   <li><b>Parent-first</b> untuk paket platform ({@code java.*}, {@code javax.*},
 *       {@code io.apistudio.contracts.*}, Jackson, SLF4J) agar kontrak tetap
 *       konsisten.</li>
 *   <li><b>Daftar kelas terlarang</b> (FR-JAVA-07): filesystem, jaringan,
 *       reflection, proses, thread, classloader.</li>
 *   <li>Dapat ditutup ({@link #close()}) agar kelas dilepas dan metaspace tidak
 *       bocor saat deploy berulang (NFR-06).</li>
 * </ul>
 *
 * <p><b>Batasan penting:</b> ini bukan sandbox proses. Kode yang dibatasi daftar
 * ini tidak boleh dianggap aman terhadap penyerang canggih. Sesuai BRD Bagian
 * 11, fase awal menyasar tim internal tepercaya; multi-tenant memerlukan
 * isolasi proses/container.
 */
public final class SandboxClassLoader extends ClassLoader implements AutoCloseable {

    private static final List<String> PARENT_FIRST_PREFIXES = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "io.apistudio.contracts.",
            "com.fasterxml.jackson.",
            "org.slf4j."
    );

    private static final List<String> BLOCKED_PREFIXES = List.of(
            "java.io.",
            "java.nio.file.",
            "java.net.",
            "java.lang.reflect.",
            "javax.naming.",
            "javax.script.",
            "jdk.internal.",
            "sun.misc."
    );

    /**
     * Pengecualian dari daftar blokir: kelas yang dibutuhkan compiler Java 9+
     * untuk operator {@code +} pada String dan lambda. Keduanya tidak memberi
     * akses filesystem/jaringan.
     */
    private static final List<String> ALLOWED_EXCEPTIONS = List.of(
            "java.lang.invoke.StringConcatFactory",
            "java.lang.invoke.LambdaMetafactory",
            "java.lang.invoke.MethodHandles",
            "java.lang.invoke.MethodHandles$Lookup",
            "java.lang.invoke.MethodType",
            "java.lang.invoke.CallSite"
    );

    private static final List<String> BLOCKED_CLASSES = List.of(
            "java.lang.Runtime",
            "java.lang.ProcessBuilder",
            "java.lang.Process",
            "java.lang.System",
            "java.lang.Thread",
            "java.lang.ThreadGroup",
            "java.lang.ClassLoader",
            "java.lang.SecurityManager",
            "java.sql.DriverManager",
            "java.util.ServiceLoader",
            "java.util.Timer",
            "java.util.concurrent.Executors",
            "java.util.concurrent.ForkJoinPool",
            "java.util.concurrent.CompletableFuture",
            "java.util.concurrent.ThreadPoolExecutor"
    );

    private final Map<String, byte[]> classes = new LinkedHashMap<>();
    private final Map<String, byte[]> resources = new LinkedHashMap<>();

    public SandboxClassLoader(ClassLoader parent) {
        super("flow-sandbox", parent);
    }

    /** Daftarkan bytecode kelas milik pengguna. */
    public void addClass(String className, byte[] bytecode) {
        classes.put(className, bytecode);
    }

    /** Daftarkan resource non-kelas (mis. {@code META-INF/services}). */
    public void addResource(String path, byte[] content) {
        resources.put(path, content);
    }

    /**
     * Muat seluruh kelas dari isi JAR (Base64 sudah didekode oleh pemanggil).
     */
    public void addJar(byte[] jarBytes) {
        if (jarBytes == null || jarBytes.length == 0) {
            return;
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] content = zip.readAllBytes();
                String name = entry.getName();
                if (name.endsWith(".class")) {
                    String className = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                    if (!isBlocked(className)) {
                        classes.putIfAbsent(className, content);
                    }
                } else {
                    resources.putIfAbsent(name, content);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("JAR tidak dapat dibaca: " + e.getMessage(), e);
        }
    }

    /** True bila kelas dilarang untuk kode pengguna. */
    public static boolean isBlocked(String className) {
        if (className == null) {
            return false;
        }
        if (ALLOWED_EXCEPTIONS.contains(className)) {
            return false;
        }
        if (BLOCKED_CLASSES.contains(className)) {
            return true;
        }
        for (String prefix : BLOCKED_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (isBlocked(name)) {
            throw new ClassNotFoundException("Akses ke kelas terlarang: " + name);
        }
        if (PARENT_FIRST_PREFIXES.stream().anyMatch(name::startsWith)) {
            return super.loadClass(name, resolve);
        }
        Class<?> loaded = findLoadedClass(name);
        if (loaded != null) {
            return loaded;
        }
        // child-first: kelas pengguna dan library menang atas parent
        if (classes.containsKey(name)) {
            Class<?> defined = defineClassFromBytes(name);
            if (resolve) {
                resolveClass(defined);
            }
            return defined;
        }
        return super.loadClass(name, resolve);
    }

    private Class<?> defineClassFromBytes(String name) {
        byte[] bytes = classes.get(name);
        return defineClass(name, bytes, 0, bytes.length);
    }

    @Override
    protected java.net.URL findResource(String name) {
        // jangan mengekspos resource platform ke kode pengguna
        return resources.containsKey(name) ? null : null;
    }

    @Override
    public java.io.InputStream getResourceAsStream(String name) {
        byte[] content = resources.get(name);
        return content == null ? null : new ByteArrayInputStream(content);
    }

    /** Jumlah kelas yang terdaftar (untuk diagnostik). */
    public int classCount() {
        return classes.size();
    }

    /** Daftar nama kelas terdaftar (untuk diagnostik). */
    public List<String> classNames() {
        return new ArrayList<>(classes.keySet());
    }

    /**
     * Lepas seluruh referensi bytecode agar classloader dapat di-GC.
     *
     * <p>Dipanggil setelah versi flow di-drain (NFR-06).
     */
    @Override
    public void close() {
        classes.clear();
        resources.clear();
    }

    /** True bila classloader sudah ditutup. */
    public boolean closed() {
        return classes.isEmpty() && resources.isEmpty();
    }
}
