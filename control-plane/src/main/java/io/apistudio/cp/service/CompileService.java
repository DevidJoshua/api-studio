package io.apistudio.cp.service;

import io.apistudio.cp.compiler.JavaSourceCompiler;
import io.apistudio.cp.compiler.MavenLibraryResolver;
import io.apistudio.cp.domain.ApiVersion;
import io.apistudio.cp.repo.ApiVersionRepository;
import io.apistudio.cp.security.CurrentUser;
import io.apistudio.engine.java.CompiledArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orkestrasi kompilasi kode Java per node (BRD FR-JAVA-03/04).
 *
 * <p>Alur:
 * <ol>
 *   <li>Baca source dan deklarasi library per node dari versi.</li>
 *   <li>Resolve library Maven (transitif) menjadi JAR.</li>
 *   <li>Kompilasi kode pengguna dengan classpath tersebut.</li>
 *   <li>Simpan bytecode ter-base64 ke versi agar runtime tidak perlu mengompilasi.</li>
 * </ol>
 */
@Service
public class CompileService {

    private static final Logger log = LoggerFactory.getLogger(CompileService.class);

    private final ApiVersionRepository versions;
    private final JavaSourceCompiler compiler;
    private final MavenLibraryResolver mavenResolver;
    private final JsonStore json;
    private final AuditService audit;
    private final int maxLibraries;

    public CompileService(ApiVersionRepository versions,
                          MavenLibraryResolver mavenResolver,
                          JsonStore json,
                          AuditService audit,
                          @Value("${apistudio.java.max-libraries:25}") int maxLibraries) {
        this.versions = versions;
        this.mavenResolver = mavenResolver;
        this.json = json;
        this.audit = audit;
        this.maxLibraries = maxLibraries;
        // Kompiler tanpa classpath tambahan; JAR per node ditambahkan saat kompilasi
        // melalui classpath proses + JAR yang sudah diunduh (lihat compileNode).
        this.compiler = new JavaSourceCompiler();
    }

    /** Hasil kompilasi satu node. */
    public record NodeCompilation(
            String nodeId,
            boolean success,
            String className,
            List<Map<String, Object>> problems,
            Map<String, Object> storage
    ) {
    }

    /** Hasil kompilasi seluruh node pada versi. */
    public record VersionCompilation(
            boolean success,
            int nodes,
            int compiled,
            List<NodeCompilation> results,
            Map<String, Object> bytecode
    ) {
        public List<String> failedNodeIds() {
            return results.stream().filter(r -> !r.success()).map(NodeCompilation::nodeId).toList();
        }
    }

    /**
     * Kompilasi seluruh node pada versi dan simpan bytecode bila semuanya berhasil.
     */
    @Transactional
    public VersionCompilation compileVersion(String versionId, CurrentUser actor) {
        ApiVersion version = versions.findById(versionId).orElseThrow();
        Map<String, Object> sources = json.readMap(version.javaSourcesJson);

        List<NodeCompilation> results = new ArrayList<>();
        Map<String, Object> stored = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : sources.entrySet()) {
            String nodeId = entry.getKey();
            Map<String, Object> nodeSource = json.convert(entry.getValue(), Map.class);
            if (nodeSource == null) {
                continue;
            }
            String source = String.valueOf(nodeSource.getOrDefault("source", ""));
            if (source.isBlank()) {
                results.add(new NodeCompilation(nodeId, true, null, List.of(), Map.of()));
                continue;
            }

            NodeCompilation compilation = compileNode(nodeId, source, librariesOf(nodeSource));
            results.add(compilation);
            if (compilation.success() && !compilation.storage().isEmpty()) {
                stored.put(nodeId, compilation.storage());
            }
        }

        boolean allOk = results.stream().allMatch(NodeCompilation::success);
        if (allOk) {
            version.bytecodeJson = json.write(stored);
            versions.save(version);
            audit.record(actor, "JAVA_COMPILED", "ApiVersion", versionId,
                    "Kompilasi berhasil untuk " + stored.size() + " node");
        } else {
            audit.record(actor, "JAVA_COMPILE_FAILED", "ApiVersion", versionId,
                    "Kompilasi gagal pada node: " + results.stream()
                            .filter(r -> !r.success()).map(NodeCompilation::nodeId).toList());
        }

        return new VersionCompilation(allOk, sources.size(), stored.size(), results, stored);
    }

    /** Kompilasi satu node (tombol Check di Studio, FR-JAVA-01). */
    public NodeCompilation compileNode(String nodeId, String source, List<String> libraries) {
        MavenLibraryResolver.Resolution resolution = mavenResolver.resolve(libraries, maxLibraries);
        List<File> classpath = new ArrayList<>(resolution.classpath());

        JavaSourceCompiler compilerWithLibs = new JavaSourceCompiler(classpath);
        JavaSourceCompiler.Result result = compilerWithLibs.compile(nodeId, source);

        List<Map<String, Object>> problems = result.problems().stream().map(p -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("severity", p.severity().name());
            item.put("line", p.userLine());
            item.put("rawLine", p.line());
            item.put("column", p.column());
            item.put("message", p.message());
            item.put("code", p.code());
            return item;
        }).toList();

        if (!result.success()) {
            return new NodeCompilation(nodeId, false, null, problems, Map.of());
        }

        Map<String, Object> storage = new LinkedHashMap<>(result.toStorage());
        // sertakan JAR pendukung agar runtime memakai library yang sama (FR-JAVA-04/05)
        List<String> encodedJars = new ArrayList<>();
        for (File jar : resolution.classpath()) {
            try {
                encodedJars.add(Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(jar.toPath())));
            } catch (Exception e) {
                log.warn("JAR tidak dapat dibaca untuk diarsipkan: {}", jar.getName());
            }
        }
        storage.put("jars", encodedJars);
        if (resolution.hasFailures()) {
            problems = new ArrayList<>(problems);
            for (String failure : resolution.failed()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("severity", "WARNING");
                item.put("line", 0);
                item.put("message", "Library tidak tersedia: " + failure);
                item.put("code", "LIBRARY_MISSING");
                problems.add(item);
            }
        }
        return new NodeCompilation(nodeId, true, result.className(), problems, storage);
    }

    /**
     * Konversi bytecode tersimpan menjadi artifact runtime.
     *
     * @return map nodeId &rarr; artifact, kosong bila belum ada kompilasi
     */
    public Map<String, CompiledArtifact> toArtifacts(String bytecodeJson) {
        Map<String, Object> stored = json.readMap(bytecodeJson);
        Map<String, CompiledArtifact> artifacts = new LinkedHashMap<>();
        stored.forEach((nodeId, value) -> {
            Map<String, Object> entry = json.convert(value, Map.class);
            if (entry == null) {
                return;
            }
            String className = String.valueOf(entry.get("className"));
            Map<String, Object> encodedClasses = json.convert(entry.get("classes"), Map.class);
            Map<String, byte[]> classes = new LinkedHashMap<>();
            if (encodedClasses != null) {
                encodedClasses.forEach((name, bytes) -> {
                    try {
                        classes.put(name, Base64.getDecoder().decode(String.valueOf(bytes)));
                    } catch (IllegalArgumentException ignored) {
                        // bytecode rusak dilewati; node akan gagal dengan pesan jelas
                    }
                });
            }
            List<byte[]> jars = new ArrayList<>();
            if (entry.get("jars") instanceof List<?> list) {
                for (Object item : list) {
                    try {
                        jars.add(Base64.getDecoder().decode(String.valueOf(item)));
                    } catch (IllegalArgumentException ignored) {
                        // JAR rusak dilewati
                    }
                }
            }
            artifacts.put(nodeId, new CompiledArtifact(nodeId, className, classes, jars));
        });
        return artifacts;
    }

    /** Ringkasan bytecode untuk ditampilkan di UI (tanpa isi biner). */
    public Map<String, Object> summarize(String bytecodeJson) {
        Map<String, Object> stored = json.readMap(bytecodeJson);
        Map<String, Object> nodes = new LinkedHashMap<>();
        stored.forEach((nodeId, value) -> {
            Map<String, Object> entry = json.convert(value, Map.class);
            if (entry == null) {
                return;
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("className", entry.get("className"));
            summary.put("classCount", entry.get("classCount"));
            Object encodedClasses = entry.get("classes");
            summary.put("libraries",
                    entry.get("jars") instanceof List<?> jars ? jars.size() : 0);
            summary.put("hasBytecode", encodedClasses != null);
            nodes.put(nodeId, summary);
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("count", nodes.size());
        return result;
    }

    private static List<String> librariesOf(Map<String, Object> nodeSource) {
        Object raw = nodeSource.get("libraries");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String s) {
                result.add(s);
            } else if (item instanceof Map<?, ?> map) {
                // dukung bentuk { type: maven, coordinate: "g:a:v" }
                Object coordinate = map.get("coordinate");
                if (coordinate != null) {
                    result.add(String.valueOf(coordinate));
                }
            }
        }
        return result;
    }
}
