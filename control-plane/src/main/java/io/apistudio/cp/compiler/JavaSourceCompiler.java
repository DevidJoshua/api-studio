package io.apistudio.cp.compiler;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Kompilasi kode Java pengguna sepenuhnya di memori (BRD FR-JAVA-01..03).
 *
 * <p>Kode pengguna cukup menulis isi method {@code run}. Kompiler membungkusnya
 * menjadi kelas yang mengimplementasikan kontrak node:
 *
 * <pre>
 * package io.apistudio.user;
 * public final class Node_&lt;suffix&gt; implements JavaNode {
 *     public Object run(NodeContext ctx) throws Exception {
 *         &lt;source pengguna&gt;
 *     }
 * }
 * </pre>
 *
 * <p>Error kompilasi dipetakan kembali ke nomor baris kode pengguna (FR-JAVA-01,
 * FR-JAVA-08) sehingga Studio dapat menampilkan penanda di baris yang tepat.
 */
public final class JavaSourceCompiler {

    /** Offset baris pembungkus sebelum isi pengguna dimulai (1-based). */
    private static final int CODE_START_LINE = 9;

    private static final String PACKAGE = "io.apistudio.user";

    /** Kelas yang tidak boleh dipakai kode pengguna (FR-JAVA-07). */
    private static final List<String> FORBIDDEN_APIS = List.of(
            "java.io.File",
            "java.io.FileInputStream",
            "java.io.FileOutputStream",
            "java.nio.file.Files",
            "java.nio.file.Paths",
            "java.net.Socket",
            "java.net.URL",
            "java.net.HttpURLConnection",
            "java.lang.Runtime",
            "java.lang.ProcessBuilder",
            "java.lang.System.exit",
            "java.lang.reflect.",
            "javax.script.",
            "java.sql.DriverManager"
    );

    /** Hasil kompilasi satu node. */
    public record Result(
            boolean success,
            String className,
            Map<String, byte[]> classes,
            List<Problem> problems
    ) {
        public Result {
            classes = classes == null ? Map.of() : Map.copyOf(classes);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        public static Result failed(List<Problem> problems) {
            return new Result(false, null, Map.of(), problems);
        }

        public List<Problem> errors() {
            return problems.stream().filter(p -> p.severity() == Severity.ERROR).toList();
        }

        /** Bytecode ter-base64 untuk disimpan pada versi flow. */
        public Map<String, Object> toStorage() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("className", className);
            Map<String, String> encoded = new LinkedHashMap<>();
            classes.forEach((name, bytes) -> encoded.put(name, Base64.getEncoder().encodeToString(bytes)));
            map.put("classes", encoded);
            map.put("classCount", classes.size());
            return map;
        }
    }

    /** Satu masalah kompilasi. */
    public record Problem(Severity severity, int line, int column, String message, String code) {
        /** Nomor baris pada kode pengguna (1-based); 0 bila tidak terkait baris. */
        public int userLine() {
            return line <= 0 ? 0 : Math.max(1, line - (CODE_START_LINE - 1));
        }
    }

    public enum Severity {
        ERROR,
        WARNING,
        NOTE
    }

    private final List<File> classpath;
    private final ContractClasspath contractClasspath;

    /** Kompiler dengan classpath default (classpath proses saat ini). */
    public JavaSourceCompiler() {
        this(List.of());
    }

    /**
     * @param extraClasspath JAR tambahan (mis. hasil resolusi Maven, FR-JAVA-04)
     */
    public JavaSourceCompiler(List<File> extraClasspath) {
        this(extraClasspath, null);
    }

    /**
     * @param extraClasspath    JAR tambahan (hasil resolusi Maven)
     * @param contractClasspath penyedia classpath kontrak; bila null dibuat
     *                          otomatis dengan cache di direktori sementara
     */
    public JavaSourceCompiler(List<File> extraClasspath, ContractClasspath contractClasspath) {
        this.classpath = extraClasspath == null ? List.of() : List.copyOf(extraClasspath);
        this.contractClasspath = contractClasspath != null
                ? contractClasspath
                : new ContractClasspath(java.nio.file.Path.of(
                        System.getProperty("java.io.tmpdir"), "apistudio-contract-cp"));
    }

    /**
     * Kompilasi kode pengguna untuk satu node.
     *
     * @param nodeId id node; dipakai sebagai bagian nama kelas
     * @param source kode Java berisi isi method {@code run}
     */
    public Result compile(String nodeId, String source) {
        if (source == null || source.isBlank()) {
            return Result.failed(List.of(new Problem(Severity.ERROR, 0, 0,
                    "Kode Java kosong", "EMPTY_SOURCE")));
        }

        List<Problem> staticProblems = scanForbiddenApis(source);
        if (!staticProblems.isEmpty()) {
            return Result.failed(staticProblems);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return Result.failed(List.of(new Problem(Severity.ERROR, 0, 0,
                    "JDK tidak tersedia: Control Plane membutuhkan JDK penuh, bukan hanya JRE",
                    "NO_COMPILER")));
        }

        String simpleName = classNameFor(nodeId);
        String className = PACKAGE + "." + simpleName;
        String wrapped = wrap(simpleName, source);

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager standardManager =
                compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
        MemoryFileManager manager = new MemoryFileManager(standardManager);

        try {
            applyClasspath(standardManager);
            JavaFileObject unit = new StringSource(className, wrapped);
            List<String> options = List.of("-proc:none", "-nowarn", "-Xlint:none");
            boolean ok = compiler.getTask(null, manager, diagnostics, options, null, List.of(unit)).call();

            List<Problem> problems = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                problems.add(new Problem(
                        switch (d.getKind()) {
                            case ERROR -> Severity.ERROR;
                            case WARNING, MANDATORY_WARNING -> Severity.WARNING;
                            default -> Severity.NOTE;
                        },
                        (int) d.getLineNumber(),
                        (int) d.getColumnNumber(),
                        d.getMessage(Locale.ROOT),
                        String.valueOf(d.getCode())));
            }

            if (!ok) {
                return new Result(false, className, Map.of(), problems);
            }
            Map<String, byte[]> classes = manager.classBytes();
            // hanya kelas pengguna yang relevan; kelas pembungkus utama wajib ada
            return new Result(true, className, classes, problems);
        } catch (Exception e) {
            return Result.failed(List.of(new Problem(Severity.ERROR, 0, 0,
                    "Kompilasi gagal: " + e.getMessage(), "COMPILE_FAILED")));
        } finally {
            try {
                standardManager.close();
            } catch (IOException ignored) {
                // manager memori tidak menyimpan resource OS
            }
        }
    }

    /** Nama kelas sederhana yang stabil untuk satu node. */
    public static String classNameFor(String nodeId) {
        String sanitized = (nodeId == null ? "node" : nodeId).replaceAll("[^A-Za-z0-9]", "_");
        if (!Character.isJavaIdentifierStart(sanitized.charAt(0))) {
            sanitized = "N" + sanitized;
        }
        return "Node_" + sanitized;
    }

    /** Bungkus kode pengguna menjadi kelas lengkap. Perhatikan {@link #CODE_START_LINE}. */
    static String wrap(String simpleName, String userSource) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(PACKAGE).append(";\n");
        sb.append("import io.apistudio.contracts.node.JavaNode;\n");
        sb.append("import io.apistudio.contracts.node.NodeContext;\n");
        sb.append("import java.util.*;\n");
        sb.append("import java.math.*;\n");
        sb.append("@SuppressWarnings(\"all\")\n");
        sb.append("public final class ").append(simpleName).append(" implements JavaNode {\n");
        sb.append("    public Object run(NodeContext ctx) throws Exception {\n");
        sb.append(userSource).append('\n');
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** Deteksi pemakaian API terlarang sebelum kompilasi (FR-JAVA-07). */
    private List<Problem> scanForbiddenApis(String source) {
        List<Problem> problems = new ArrayList<>();
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // abaikan baris komentar agar tidak salah tuduh
            String withoutComment = stripComment(line);
            for (String forbidden : FORBIDDEN_APIS) {
                if (matchesForbidden(withoutComment, forbidden)) {
                    problems.add(new Problem(Severity.ERROR, i + 1, 0,
                            "Pemakaian API terlarang: " + forbidden
                                    + ". Kode pengguna tidak dapat mengakses filesystem, jaringan, proses, "
                                    + "atau reflection.",
                            "FORBIDDEN_API"));
                }
            }
        }
        return problems;
    }

    /**
     * Deteksi pemakaian API terlarang, termasuk bentuk tanpa kualifikasi paket.
     *
     * <p>Pemeriksaan teks sederhana ini adalah lapisan pertahanan pertama
     * (mudah dipahami dan cepat). Pertahanan sebenarnya ada pada
     * {@code SandboxClassLoader} yang menolak memuat kelas terlarang saat
     * runtime, sehingga variasi penulisan seperti import statis tetap gagal di
     * sana. Keduanya dipakai bersama.
     */
    private static boolean matchesForbidden(String line, String forbidden) {
        if (line.contains(forbidden)) {
            return true;
        }
        // bentuk tanpa paket: "Runtime.getRuntime()", "new ProcessBuilder(...)"
        int lastDot = forbidden.lastIndexOf('.');
        if (lastDot > 0) {
            String simple = forbidden.substring(lastDot + 1);
            if (simple.length() < 4) {
                return false;
            }
            return java.util.regex.Pattern
                    .compile("(^|[^A-Za-z0-9_.])" + java.util.regex.Pattern.quote(simple) + "\\b")
                    .matcher(line)
                    .find();
        }
        return false;
    }

    private static String stripComment(String line) {
        int single = line.indexOf("//");
        int triple = line.indexOf("/*");
        int cut = line.length();
        if (single >= 0) {
            cut = Math.min(cut, single);
        }
        if (triple >= 0) {
            cut = Math.min(cut, triple);
        }
        return line.substring(0, cut);
    }

    @SuppressWarnings("unchecked")
    /**
     * Susun classpath untuk kompilasi.
     *
     * <p>Ada dua sumber yang harus digabungkan:
     * <ol>
     *   <li>{@code java.class.path} — benar saat berjalan dari IDE atau
     *       {@code java -cp ...}.</li>
     *   <li>Lokasi kode sumber kelas kontrak saat ini — diperlukan saat aplikasi
     *       dibungkus jar Spring Boot, karena {@code java.class.path} hanya
     *       berisi {@code boot-launcher} sehingga kontrak tidak terlihat.</li>
     * </ol>
     *
     * <p>Pendekatan kedua memakai lokasi nyata kelas {@code NodeContext}; bila
     * kelas dimuat dari {@code nested:/...} (jar dalam jar), lokasi proyek
     * dianggap null dan dilewati.
     */
    private void applyClasspath(StandardJavaFileManager manager) throws IOException {
        List<File> entries = new ArrayList<>(classpath);

        // kontrak + engine harus selalu tersedia; ini menangani kasus jar Spring Boot
        entries.addAll(contractClasspath.entries());

        String processClasspath = System.getProperty("java.class.path");
        if (processClasspath != null && !processClasspath.isBlank()) {
            for (String entry : processClasspath.split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    addEntry(entries, new File(entry));
                }
            }
        }

        // lokasi kelas kontrak (menangani jar Spring Boot / classloader khusus)
        File contractLocation = locationOf(io.apistudio.contracts.node.NodeContext.class);
        if (contractLocation != null) {
            addEntry(entries, contractLocation);
        }
        // lokasi engine (butuh kelas runtime saat memuat bytecode pengguna)
        File engineLocation = locationOf(io.apistudio.engine.FlowEngine.class);
        if (engineLocation != null) {
            addEntry(entries, engineLocation);
        }

        if (!entries.isEmpty()) {
            manager.setLocation(StandardLocation.CLASS_PATH, entries);
        }
    }

    /** Tambahkan entri bila belum ada dan benar-benar ada di disk. */
    private static void addEntry(List<File> entries, File candidate) {
        if (candidate == null || !candidate.exists()) {
            return;
        }
        for (File existing : entries) {
            if (existing.getAbsoluteFile().equals(candidate.getAbsoluteFile())) {
                return;
            }
        }
        entries.add(candidate);
    }

    /**
     * Lokasi berkas (jar/direktori kelas) asal sebuah kelas.
     *
     * @return file, atau {@code null} bila tidak dapat ditentukan
     */
    private static File locationOf(Class<?> type) {
        try {
            java.security.CodeSource source = type.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            String path = source.getLocation().toURI().getPath();
            if (path == null || path.isBlank()) {
                return null;
            }
            File file = new File(java.net.URLDecoder.decode(path, "UTF-8"));
            // jar bersarang pada Spring Boot tidak dapat dipakai sebagai classpath
            return file.getPath().contains("!") ? null : file;
        } catch (Exception e) {
            return null;
        }
    }

    /** Sumber Java dari String. */
    private static final class StringSource extends SimpleJavaFileObject {
        private final String code;

        StringSource(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    /** File manager yang menulis hasil kompilasi ke memori. */
    private static final class MemoryFileManager extends javax.tools.ForwardingJavaFileManager<StandardJavaFileManager> {

        private final Map<String, byte[]> outputs = new LinkedHashMap<>();

        MemoryFileManager(StandardJavaFileManager delegate) {
            super(delegate);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                   JavaFileObject.Kind kind, javax.tools.FileObject sibling) {
            // Nama kelas disimpan dalam bentuk biner (titik), karena pemanggil
            // (SandboxClassLoader) memuat kelas dengan nama biner. Path untuk
            // URI dibuat terpisah agar kompilator tetap bekerja.
            return new ClassOutput(className);
        }

        Map<String, byte[]> classBytes() {
            return new LinkedHashMap<>(outputs);
        }

        private final class ClassOutput extends SimpleJavaFileObject {
            /** Nama biner kelas (dengan titik). */
            private final String binaryName;

            ClassOutput(String binaryName) {
                super(URI.create("mem:///" + binaryName.replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
                this.binaryName = binaryName;
            }

            @Override
            public OutputStream openOutputStream() {
                return new ByteArrayOutputStream() {
                    @Override
                    public void close() throws IOException {
                        super.close();
                        outputs.put(binaryName, toByteArray());
                    }
                };
            }
        }
    }
}
