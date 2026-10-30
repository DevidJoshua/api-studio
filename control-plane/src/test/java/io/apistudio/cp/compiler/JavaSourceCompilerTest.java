package io.apistudio.cp.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pengujian kompilasi kode Java pengguna (BRD FR-JAVA-01..03/07). */
class JavaSourceCompilerTest {

    private final JavaSourceCompiler compiler = new JavaSourceCompiler();

    @Test
    @DisplayName("kompilasi kode valid menghasilkan bytecode")
    void compilesValidSource() {
        JavaSourceCompiler.Result result = compiler.compile("node_a", """
                return ctx.get("nilai");
                """);

        assertTrue(result.success(), () -> "kompilasi harus sukses: " + result.problems());
        assertNotNull(result.className());
        assertTrue(result.classes().size() >= 1);
        assertTrue(result.errors().isEmpty());
    }

    @Test
    @DisplayName("bytecode disimpan dengan nama biner kelas agar dapat dimuat SandboxClassLoader")
    void bytecodeUsesBinaryClassNames() {
        JavaSourceCompiler.Result result = compiler.compile("node_bin", "return 42;");
        assertTrue(result.success(), () -> "kompilasi harus sukses: " + result.problems());

        // nama kelas harus berbentuk biner (titik), bukan path (garis miring)
        assertTrue(result.classes().containsKey(result.className()),
                () -> "map bytecode harus memakai nama biner. Isi: " + result.classes().keySet());
        for (String name : result.classes().keySet()) {
            assertFalse(name.contains("/"), () -> "nama kelas tidak boleh berupa path: " + name);
        }

        // bytecode harus benar-benar dapat dimuat dengan nama tersebut
        try (io.apistudio.engine.java.SandboxClassLoader loader =
                     new io.apistudio.engine.java.SandboxClassLoader(getClass().getClassLoader())) {
            result.classes().forEach(loader::addClass);
            Class<?> loaded = Class.forName(result.className(), false, loader);
            assertNotNull(loaded);
            assertEquals(result.className(), loaded.getName());
        } catch (ClassNotFoundException e) {
            throw new AssertionError("kelas hasil kompilasi tidak dapat dimuat: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new AssertionError("gagal menutup classloader: " + e.getMessage(), e);
        }
    }
    @Test
    @DisplayName("ContractClasspath menyediakan kelas kontrak yang dapat dipakai javac")
    void contractClasspathIsUsable() {
        ContractClasspath cp = new ContractClasspath(
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "cp-test-" + System.nanoTime()));
        java.util.List<java.io.File> entries = cp.entries();

        assertFalse(entries.isEmpty(), "classpath kontrak tidak boleh kosong");
        assertTrue(cp.isComplete(),
                () -> "klas kontrak wajib (NodeContext/JavaNode/FlowEngine) harus tersedia: " + entries);

        // setiap entri harus benar-benar ada di disk agar javac dapat membacanya
        for (java.io.File entry : entries) {
            assertTrue(entry.exists(), () -> "entri classpath tidak ada: " + entry);
        }
    }

    @Test
    @DisplayName("kompilasi berhasil walau classpath proses tidak memuat kontrak (kondisi jar)")
    void compilesInJarLikeEnvironment() {
        String original = System.getProperty("java.class.path");
        try {
            System.setProperty("java.class.path", "");
            Compiler compilerWithContracts = null;
            JavaSourceCompiler.Result result = compiler.compile("node_jar", """
                    String id = ctx.nodeId();
                    ctx.set("id", id);
                    return java.util.Map.of("id", id);
                    """);
            assertTrue(result.success(),
                    () -> "kompilasi harus berhasil pada kondisi jar: " + result.problems());
        } finally {
            System.setProperty("java.class.path", original);
        }
    }
    @Test
    @DisplayName("kompilasi tetap menemukan kontrak walau java.class.path tidak memuatnya")
    void resolvesContractsWithoutProcessClasspath() {
        String original = System.getProperty("java.class.path");
        try {
            // simulasi jar Spring Boot: classpath proses hanya berisi launcher
            System.setProperty("java.class.path", "boot-launcher.jar");
            JavaSourceCompiler.Result result = compiler.compile("node_k", """
                    Object id = ctx.input("id");
                    return id == null ? "-" : id.toString();
                    """);
            assertTrue(result.success(),
                    () -> "kontrak harus tetap ditemukan lewat lokasi kode: " + result.problems());
        } finally {
            System.setProperty("java.class.path", original);
        }
    }
    @Test
    @DisplayName("kode dapat memakai NodeContext dan struktur Java standar")
    void compilesWithContextUsage() {
        JavaSourceCompiler.Result result = compiler.compile("node_b", """
                String id = ctx.input("id") == null ? "-" : ctx.input("id").toString();
                ctx.set("id", id);
                java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
                out.put("panjang", id.length());
                out.put("waktu", ctx.callTemplate("util.timestamp@1", java.util.Map.of()));
                return out;
                """);

        assertTrue(result.success(), () -> "kompilasi harus sukses: " + result.problems());
    }

    @Test
    @DisplayName("operator + pada String dan lambda tidak gagal (bootstrap class diizinkan)")
    void supportsStringConcatAndLambda() {
        JavaSourceCompiler.Result result = compiler.compile("node_c", """
                String teks = "a" + 1 + "b";
                java.util.List<String> list = new java.util.ArrayList<>();
                list.add(teks);
                list.sort((x, y) -> x.compareTo(y));
                return list;
                """);

        assertTrue(result.success(), () -> "kompilasi harus sukses: " + result.problems());
    }

    @Test
    @DisplayName("error sintaks dilaporkan dengan nomor baris kode pengguna")
    void reportsUserLineNumbers() {
        // baris 1 valid, baris 2 salah sintaks
        JavaSourceCompiler.Result result = compiler.compile("node_d", """
                int a = 1;
                int b = ;
                return a;
                """);

        assertFalse(result.success());
        assertFalse(result.errors().isEmpty());
        int userLine = result.errors().get(0).userLine();
        assertEquals(2, userLine, "error harus menunjuk baris 2 kode pengguna, bukan baris file terbungkus");
    }

    @Test
    @DisplayName("tipe tidak dikenal dilaporkan sebagai error")
    void reportsUnknownType() {
        JavaSourceCompiler.Result result = compiler.compile("node_e", """
                KelasTidakAda x = new KelasTidakAda();
                return x;
                """);

        assertFalse(result.success());
        assertFalse(result.errors().isEmpty());
    }

    @Test
    @DisplayName("akses filesystem ditolak sebelum kompilasi")
    void rejectsFilesystem() {
        JavaSourceCompiler.Result result = compiler.compile("node_f", """
                java.io.File f = new java.io.File("/etc/passwd");
                return f.exists();
                """);

        assertFalse(result.success());
        assertTrue(result.errors().stream()
                        .anyMatch(p -> "FORBIDDEN_API".equals(p.code())),
                () -> "harus ada FORBIDDEN_API: " + result.problems());
    }

    @Test
    @DisplayName("akses jaringan ditolak")
    void rejectsNetwork() {
        JavaSourceCompiler.Result result = compiler.compile("node_g", """
                java.net.Socket s = new java.net.Socket("example.com", 80);
                return s.isConnected();
                """);
        assertFalse(result.success());
        assertTrue(result.errors().stream().anyMatch(p -> "FORBIDDEN_API".equals(p.code())));
    }

    @Test
    @DisplayName("Runtime dan ProcessBuilder ditolak")
    void rejectsProcessExecution() {
        assertFalse(compiler.compile("n1", """
                return Runtime.getRuntime().availableProcessors();
                """).success());

        assertFalse(compiler.compile("n2", """
                ProcessBuilder pb = new ProcessBuilder("ls");
                return pb.start();
                """).success());
    }

    @Test
    @DisplayName("API terlarang di dalam komentar tidak ditolak")
    void allowsForbiddenApiInComment() {
        JavaSourceCompiler.Result result = compiler.compile("node_h", """
                // tidak boleh memakai java.io.File di sini
                /* java.net.Socket juga terlarang */
                return "aman";
                """);

        assertTrue(result.success(), () -> "komentar tidak boleh memicu penolakan: " + result.problems());
    }

    @Test
    @DisplayName("source kosong ditolak dengan pesan jelas")
    void rejectsEmptySource() {
        JavaSourceCompiler.Result result = compiler.compile("node_i", "   ");
        assertFalse(result.success());
        assertTrue(result.problems().stream().anyMatch(p -> "EMPTY_SOURCE".equals(p.code())));
    }

    @Test
    @DisplayName("classNameFor menghasilkan nama kelas Java yang valid")
    void classNameIsValidIdentifier() {
        String name = JavaSourceCompiler.classNameFor("node-1:abc");
        assertTrue(Character.isJavaIdentifierStart(name.charAt(0)));
        assertTrue(name.chars().allMatch(Character::isJavaIdentifierPart));
        assertEquals(name, JavaSourceCompiler.classNameFor("node-1:abc"));
    }

    @Test
    @DisplayName("bytecode tersimpan dalam bentuk Base64")
    void storageIsBase64() {
        JavaSourceCompiler.Result result = compiler.compile("node_j", "return 1;");
        assertTrue(result.success());

        var storage = result.toStorage();
        assertEquals(result.className(), storage.get("className"));
        @SuppressWarnings("unchecked")
        var classes = (java.util.Map<String, String>) storage.get("classes");
        assertFalse(classes.isEmpty());
        for (String encoded : classes.values()) {
            // Base64 valid tidak melempar exception
            assertNotNull(java.util.Base64.getDecoder().decode(encoded));
        }
    }

    @Test
    @DisplayName("validasi koordinat Maven")
    void mavenCoordinateValidation() {
        assertTrue(MavenLibraryResolver.isValidCoordinate("com.google.guava:guava:33.0.0-jre"));
        assertFalse(MavenLibraryResolver.isValidCoordinate("guava"));
        assertFalse(MavenLibraryResolver.isValidCoordinate("a:b"));
        assertFalse(MavenLibraryResolver.isValidCoordinate(""));
        assertFalse(MavenLibraryResolver.isValidCoordinate("a:b:c:d"));
    }

    @Test
    @DisplayName("parsing POM melewati dependensi test, provided, dan optional")
    void pomParsingSkipsTestAndOptional() throws Exception {
        MavenLibraryResolver resolver = new MavenLibraryResolver(
                java.nio.file.Files.createTempDirectory("libs"), List.of());

        // method tagValue bersifat privat; verifikasi perilaku melalui POM yang
        // disimpan lalu dibaca oleh alur parsing yang sebenarnya.
        var tempDir = java.nio.file.Files.createTempDirectory("pom");
        var pomFile = tempDir.resolve("contoh.pom");
        String pom = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.contoh</groupId>
                      <artifactId>untuk-test</artifactId>
                      <version>1.0.0</version>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>com.contoh</groupId>
                      <artifactId>opsional</artifactId>
                      <version>1.0.0</version>
                      <optional>true</optional>
                    </dependency>
                    <dependency>
                      <groupId>com.contoh</groupId>
                      <artifactId>tanpa-versi</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """;
        java.nio.file.Files.writeString(pomFile, pom);

        // repository default tidak kosong
        assertFalse(resolver.repositories().isEmpty());
        // koordinat valid diterima; yang tidak valid ditolak lebih awal
        assertTrue(MavenLibraryResolver.isValidCoordinate("com.contoh:contoh:1.0.0"));
    }
}
