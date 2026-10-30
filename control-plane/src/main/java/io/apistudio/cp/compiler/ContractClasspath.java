package io.apistudio.cp.compiler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Menyediakan classpath nyata untuk kompilasi kode pengguna (BRD FR-JAVA-03).
 *
 * <p>Masalah yang diselesaikan: saat aplikasi dijalankan dari <b>jar Spring
 * Boot</b>, kelas aplikasi berada di dalam arsip bersarang
 * ({@code BOOT-INF/lib/*.jar}) dan {@code java.class.path} hanya berisi
 * {@code boot-launcher}. javac tidak dapat membaca resource bersarang, sehingga
 * kompilasi gagal dengan "package io.apistudio.contracts.node does not exist".
 *
 * <p>Solusinya: kelas yang dibutuhkan kode pengguna diekstrak ke direktori cache
 * di disk, lalu direktori/jar hasil ekstrak dipakai sebagai classpath. Ekstraksi
 * dilakukan sekali (idempoten) dan aman dijalankan berulang.
 */
public final class ContractClasspath {

    private static final Logger log = LoggerFactory.getLogger(ContractClasspath.class);

    /** Kelas yang harus tersedia bagi kode pengguna. */
    private static final List<Class<?>> REQUIRED_CLASSES = List.of(
            io.apistudio.contracts.node.NodeContext.class,
            io.apistudio.contracts.node.JavaNode.class,
            io.apistudio.engine.FlowEngine.class
    );

    /** Nama artefak yang isinya diekstrak utuh (kontrak + engine + dependensinya). */
    private static final List<String> ARTIFACT_PATTERNS = List.of(
            "engine-contracts", "flow-engine", "jackson-"
    );

    private final Path cacheDir;
    private volatile List<File> cachedEntries;

    public ContractClasspath(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    /**
     * Entri classpath yang dapat dipakai javac.
     *
     * <p>Hasil di-cache setelah pemanggilan pertama karena ekstraksi relatif
     * mahal dan isinya tidak berubah selama proses berjalan.
     */
    public List<File> entries() {
        List<File> local = cachedEntries;
        if (local == null) {
            synchronized (this) {
                if (cachedEntries == null) {
                    cachedEntries = build();
                }
                local = cachedEntries;
            }
        }
        return local;
    }

    /** Bangun daftar entri classpath (dijalankan sekali). */
    private List<File> build() {
        Set<File> entries = new LinkedHashSet<>();

        File contractLocation = locationOf(io.apistudio.contracts.node.NodeContext.class);
        File engineLocation = locationOf(io.apistudio.engine.FlowEngine.class);

        // 1. Berjalan dari direktori kelas (IDE / target/classes): pakai langsung.
        boolean direct = addIfUsable(entries, contractLocation);
        addIfUsable(entries, engineLocation);
        if (direct) {
            log.info("Classpath kompilasi memakai direktori kelas langsung: {}", entries.size());
            return new ArrayList<>(entries);
        }

        // 2. Berjalan dari jar (termasuk jar Spring Boot): ekstrak ke cache.
        List<File> extracted = extractFromApplicationJar();
        if (!extracted.isEmpty()) {
            entries.addAll(extracted);
        } else {
            // 3. Fallback: mana pun entri yang dapat ditemukan, pakai apa adanya.
            addIfUsable(entries, contractLocation);
            addIfUsable(entries, engineLocation);
            log.warn("Ekstraksi artefak tidak menghasilkan entri; classpath kompilasi "
                    + "mungkin tidak lengkap. Periksa log bootstrap untuk detail.");
        }

        List<File> result = new ArrayList<>(entries);
        log.info("Classpath kompilasi kode pengguna: {} entri", result.size());
        return result;
    }

    /** Ekstrak artefak yang dibutuhkan dari jar aplikasi ke cache. */
    private List<File> extractFromApplicationJar() {
        List<File> result = new ArrayList<>();
        File appJar = applicationJar();
        if (appJar == null || !appJar.isFile()) {
            log.warn("Jar aplikasi tidak dapat ditemukan untuk ekstraksi kontrak");
            return result;
        }
        try {
            Files.createDirectories(cacheDir);
            try (JarFile jar = new JarFile(appJar)) {
                List<JarEntry> libEntries = jar.stream()
                        .filter(entry -> entry.getName().startsWith("BOOT-INF/lib/")
                                && entry.getName().endsWith(".jar"))
                        .filter(entry -> ARTIFACT_PATTERNS.stream()
                                .anyMatch(pattern -> entry.getName().contains(pattern)))
                        .toList();

                for (JarEntry entry : libEntries) {
                    String fileName = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
                    Path target = cacheDir.resolve(fileName);
                    if (!Files.exists(target)) {
                        try (InputStream input = jar.getInputStream(entry)) {
                            Files.copy(input, target);
                        }
                    }
                    result.add(target.toFile());
                }
                log.info("Kontrak diekstrak dari {} ke {} ({} artefak)",
                        appJar.getName(), cacheDir, result.size());
            }
        } catch (IOException e) {
            log.warn("Gagal mengekstrak artefak dari {}: {}", appJar, e.getMessage());
        }
        return result;
    }

    /**
     * Cari jar aplikasi yang sedang berjalan.
     *
     * <p>Beberapa strategi dicoba berurutan karena bentuk lokasi berbeda antara
     * {@code java -jar}, IDE, dan launcher jar Spring Boot:
     * <ol>
     *   <li>Argumen {@code sun.java.command} &rarr; bentuk {@code java -jar app.jar}.</li>
     *   <li>Lokasi kode kelas Control Plane &rarr; menangani {@code BOOT-INF}.</li>
     *   <li>Lokasi kode kelas ini sebagai cadangan.</li>
     * </ol>
     */
    private File applicationJar() {
        File fromCommand = fromCommandLine();
        if (fromCommand != null) {
            return fromCommand;
        }
        File fromControlPlane = fromCodeSource(
                "io.apistudio.cp.ControlPlaneApplication");
        if (fromControlPlane != null) {
            return fromControlPlane;
        }
        return fromCodeSource(getClass().getName());
    }

    /**
     * Baca jar dari argumen baris perintah.
     *
     * <p>Untuk {@code java -jar app.jar}, {@code sun.java.command} berisi path
     * jar. Ini bentuk paling umum saat menjalankan aplikasi hasil build.
     */
    private File fromCommandLine() {
        String command = System.getProperty("sun.java.command");
        if (command == null || command.isBlank()) {
            return null;
        }
        // ambil token pertama; pada `java -jar x.jar` itulah path jar
        String first = command.trim().split("\\s+")[0];
        if (!first.endsWith(".jar")) {
            return null;
        }
        File file = new File(first);
        if (!file.isAbsolute()) {
            file = new File(System.getProperty("user.dir"), first);
        }
        return file.isFile() ? file : null;
    }

    /** Cari jar dari lokasi kode sebuah kelas (nama kelas sebagai String). */
    private File fromCodeSource(String className) {
        try {
            Class<?> type = Class.forName(className, false, getClass().getClassLoader());
            java.security.CodeSource source = type.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            String raw = source.getLocation().toString();
            String candidate = raw;

            int bootIndex = candidate.indexOf("BOOT-INF");
            if (bootIndex > 0) {
                candidate = candidate.substring(0, bootIndex);
            }
            candidate = candidate.replaceFirst("^nested:", "");
            candidate = candidate.replaceFirst("^jar:", "");
            candidate = candidate.replaceFirst("^file:", "");
            candidate = candidate.replaceFirst("!/$", "");
            candidate = candidate.replaceFirst("!$", "");
            candidate = candidate.replaceFirst("/$", "");

            File file = new File(java.net.URLDecoder.decode(candidate, "UTF-8"));
            if (file.isFile() && file.getName().endsWith(".jar")) {
                return file;
            }
            // lokasi bisa berupa direktori target/classes dari IDE
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Lokasi asal sebuah kelas bila dapat dipakai sebagai classpath. */
    private static File locationOf(Class<?> type) {
        try {
            java.security.CodeSource source = type.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            String raw = source.getLocation().toString();
            if (raw.contains("BOOT-INF")) {
                // kelas bersarang: tidak dapat dipakai javac langsung
                return null;
            }
            if (raw.startsWith("nested:")) {
                return null;
            }
            File file = new File(source.getLocation().toURI());
            return file.exists() ? file : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** @return true bila entri ditambahkan */
    private static boolean addIfUsable(Set<File> entries, File candidate) {
        if (candidate != null && candidate.exists()) {
            return entries.add(candidate);
        }
        return false;
    }

    /** True bila seluruh kelas wajib dapat dimuat dari classpath hasil. */
    public boolean isComplete() {
        List<File> entries = entries();
        if (entries.isEmpty()) {
            return false;
        }
        for (Class<?> type : REQUIRED_CLASSES) {
            String path = type.getName().replace('.', '/') + ".class";
            boolean found = false;
            for (File entry : entries) {
                if (containsEntry(entry, path)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /** Periksa keberadaan entri kelas pada direktori atau jar. */
    private static boolean containsEntry(File entry, String classPath) {
        if (entry.isDirectory()) {
            return new File(entry, classPath).exists();
        }
        try (JarFile jar = new JarFile(entry)) {
            return jar.getEntry(classPath) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** Direktori cache (diagnostik). */
    public Path cacheDir() {
        return cacheDir;
    }
}
