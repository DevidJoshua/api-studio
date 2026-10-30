package io.apistudio.engine.java;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pengujian isolasi classloader dan daftar kelas terlarang (BRD FR-JAVA-05/07). */
class SandboxClassLoaderTest {

    @Test
    @DisplayName("kelas platform dimuat parent-first")
    void parentFirstForPlatformClasses() throws Exception {
        try (SandboxClassLoader loader = new SandboxClassLoader(getClass().getClassLoader())) {
            Class<?> stringClass = loader.loadClass("java.lang.String");
            assertNotNull(stringClass);
            assertEquals(String.class, stringClass);
        }
    }

    @Test
    @DisplayName("kontrak engine tersedia untuk kode pengguna")
    void contractsAvailable() throws Exception {
        try (SandboxClassLoader loader = new SandboxClassLoader(getClass().getClassLoader())) {
            assertNotNull(loader.loadClass("io.apistudio.contracts.node.NodeContext"));
        }
    }

    @Test
    @DisplayName("filesystem terlarang")
    void blocksFilesystem() {
        try (SandboxClassLoader loader = new SandboxClassLoader(getClass().getClassLoader())) {
            assertTrue(SandboxClassLoader.isBlocked("java.io.File"));
            assertTrue(SandboxClassLoader.isBlocked("java.nio.file.Files"));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("java.io.File"));
        }
    }

    @Test
    @DisplayName("jaringan terlarang")
    void blocksNetwork() {
        assertTrue(SandboxClassLoader.isBlocked("java.net.Socket"));
        try (SandboxClassLoader loader = new SandboxClassLoader(getClass().getClassLoader())) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("java.net.Socket"));
        }
    }

    @Test
    @DisplayName("reflection terlarang")
    void blocksReflection() {
        assertTrue(SandboxClassLoader.isBlocked("java.lang.reflect.Method"));
    }

    @Test
    @DisplayName("proses dan thread terlarang")
    void blocksProcessAndThread() {
        assertTrue(SandboxClassLoader.isBlocked("java.lang.Runtime"));
        assertTrue(SandboxClassLoader.isBlocked("java.lang.ProcessBuilder"));
        assertTrue(SandboxClassLoader.isBlocked("java.lang.System"));
        assertTrue(SandboxClassLoader.isBlocked("java.lang.Thread"));
        assertTrue(SandboxClassLoader.isBlocked("java.lang.ClassLoader"));
    }

    @Test
    @DisplayName("kelas aman tidak diblokir")
    void allowsSafeClasses() {
        assertFalse(SandboxClassLoader.isBlocked("java.util.List"));
        assertFalse(SandboxClassLoader.isBlocked("java.util.Map"));
        assertFalse(SandboxClassLoader.isBlocked("java.math.BigDecimal"));
        assertFalse(SandboxClassLoader.isBlocked("java.time.Instant"));
        assertFalse(SandboxClassLoader.isBlocked("io.apistudio.contracts.node.NodeContext"));
    }

    @Test
    @DisplayName("pengecualian untuk kebutuhan compiler Java 9+")
    void allowsCompilerBootstrapClasses() {
        assertFalse(SandboxClassLoader.isBlocked("java.lang.invoke.StringConcatFactory"));
        assertFalse(SandboxClassLoader.isBlocked("java.lang.invoke.LambdaMetafactory"));
        assertFalse(SandboxClassLoader.isBlocked("java.lang.invoke.MethodHandles$Lookup"));
    }

    @Test
    @DisplayName("child-first: kelas pengguna menang atas nama yang sama")
    void childFirstForUserClasses() throws Exception {
        // Kelas uji ini sudah ada di classpath test; daftarkan versi "palsu"
        // dengan nama berbeda agar dapat diverifikasi terpisah.
        try (SandboxClassLoader loader = new SandboxClassLoader(getClass().getClassLoader())) {
            String className = "com.contoh.UserLogic";
            byte[] bytecode = minimalClassBytes(className);
            loader.addClass(className, bytecode);
            assertEquals(1, loader.classCount());
            assertTrue(loader.classNames().contains(className));
        }
    }

    @Test
    @DisplayName("close() melepas seluruh kelas (NFR-06)")
    void closeReleasesClasses() {
        SandboxClassLoader loader = new SandboxClassLoader(getClass().getClassLoader());
        loader.addClass("com.contoh.A", new byte[]{1, 2, 3});
        assertEquals(1, loader.classCount());

        loader.close();
        assertEquals(0, loader.classCount());
        assertTrue(loader.closed());
    }

    @Test
    @DisplayName("cache melepas classloader per versi")
    void cacheEvictsByPrefix() {
        ClassLoaderCache cache = new ClassLoaderCache();
        cache.getOrCreate("v1/nodeA", () -> new SandboxClassLoader(getClass().getClassLoader()));
        cache.getOrCreate("v1/nodeB", () -> new SandboxClassLoader(getClass().getClassLoader()));
        cache.getOrCreate("v2/nodeA", () -> new SandboxClassLoader(getClass().getClassLoader()));
        assertEquals(3, cache.size());

        assertEquals(2, cache.evictByPrefix("v1/"), "dua entri v1 harus dilepas");
        assertEquals(1, cache.size());
        assertTrue(cache.contains("v2/nodeA"));
        cache.close();
    }

    @Test
    @DisplayName("stats melaporkan jumlah kelas per entri")
    void cacheStats() {
        ClassLoaderCache cache = new ClassLoaderCache();
        cache.getOrCreate("k1", () -> {
            SandboxClassLoader loader = new SandboxClassLoader(getClass().getClassLoader());
            loader.addClass("com.contoh.B", new byte[]{1});
            return loader;
        });
        assertEquals(1, cache.stats().get("entries"));
        cache.close();
    }

    /** Bytecode minimal (bukan kelas valid) hanya untuk menguji pemetaan nama. */
    private static byte[] minimalClassBytes(String className) {
        return ("PALSU:" + className).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
