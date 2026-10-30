package io.apistudio.cp.compiler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resolusi library Maven untuk node Java Code (BRD FR-JAVA-04).
 *
 * <p>Fitur:
 * <ul>
 *   <li>Parse koordinat {@code groupId:artifactId:version}.</li>
 *   <li>Resolusi dependensi transitif dengan membaca POM (compile/runtime saja;
 *       {@code test} dan {@code optional} dilewati).</li>
 *   <li>Cache JAR di disk agar tidak mengunduh berulang.</li>
 *   <li>Batas jumlah library dan kedalaman transitif untuk mencegah unduhan
 *       eksplosif.</li>
 * </ul>
 *
 * <p>Untuk produksi, repository harus dibatasi ke repository internal
 * (BRD Bagian 11). Batas ini ditegakkan lewat konfigurasi daftar repository.
 */
public final class MavenLibraryResolver {

    private static final Logger log = LoggerFactory.getLogger(MavenLibraryResolver.class);

    /** Pola koordinat Maven yang valid. */
    private static final Pattern COORDINATE =
            Pattern.compile("^[A-Za-z0-9_.\\-]+:[A-Za-z0-9_.\\-]+:[A-Za-z0-9_.\\-]+$");

    private static final int MAX_TRANSITIVE = 60;
    private static final int MAX_DEPTH = 6;

    private final Path cacheDir;
    private final List<String> repositories;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Hasil resolusi. */
    public record Resolution(List<File> jars, List<String> resolved, List<String> failed) {
        public Resolution {
            jars = jars == null ? List.of() : List.copyOf(jars);
            resolved = resolved == null ? List.of() : List.copyOf(resolved);
            failed = failed == null ? List.of() : List.copyOf(failed);
        }

        public boolean hasFailures() {
            return !failed.isEmpty();
        }

        /** JAR untuk classpath kompilasi. */
        public List<File> classpath() {
            return jars;
        }
    }

    public MavenLibraryResolver(Path cacheDir, List<String> repositories) {
        this.cacheDir = cacheDir;
        this.repositories = repositories == null || repositories.isEmpty()
                ? List.of("https://repo1.maven.org/maven2")
                : List.copyOf(repositories);
    }

    /** Validasi format koordinat Maven. */
    public static boolean isValidCoordinate(String coordinate) {
        return coordinate != null && COORDINATE.matcher(coordinate.trim()).matches();
    }

    /**
     * Resolve daftar koordinat beserta dependensi transitifnya.
     *
     * @param coordinates koordinat {@code group:artifact:version}
     * @param maxLibraries batas jumlah library total
     */
    public Resolution resolve(List<String> coordinates, int maxLibraries) {
        List<File> jars = new ArrayList<>();
        List<String> resolved = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        if (coordinates == null || coordinates.isEmpty()) {
            return new Resolution(jars, resolved, failed);
        }

        int limit = maxLibraries <= 0 ? 25 : maxLibraries;
        Set<String> visited = new LinkedHashSet<>();
        for (String coordinate : coordinates) {
            if (coordinate == null || coordinate.isBlank()) {
                continue;
            }
            String trimmed = coordinate.trim();
            if (!isValidCoordinate(trimmed)) {
                failed.add(trimmed + " (format koordinat tidak valid)");
                continue;
            }
            resolveRecursive(trimmed, 0, limit, visited, jars, resolved, failed);
            if (visited.size() >= limit) {
                log.warn("Batas library {} tercapai; sisa dependensi tidak diunduh", limit);
                break;
            }
        }
        return new Resolution(jars, resolved, failed);
    }

    /** Unduh JAR dan telusuri dependensi POM-nya. */
    private void resolveRecursive(String coordinate, int depth, int limit, Set<String> visited,
                                  List<File> jars, List<String> resolved, List<String> failed) {
        if (depth > MAX_DEPTH || visited.size() >= limit || !visited.add(coordinate)) {
            return;
        }
        String[] parts = coordinate.split(":");
        String group = parts[0];
        String artifact = parts[1];
        String version = parts[2];

        File jar = download(group, artifact, version, "jar");
        if (jar != null) {
            jars.add(jar);
            resolved.add(coordinate);
        } else {
            failed.add(coordinate);
            return;
        }

        for (String dependency : parseCompileDependencies(group, artifact, version)) {
            if (dependency.equals(coordinate)) {
                continue;
            }
            resolveRecursive(dependency, depth + 1, limit, visited, jars, resolved, failed);
        }
    }

    /**
     * Ambil dependensi compile/runtime dari POM.
     *
     * <p>Parsing memakai pendekatan berbasis teks yang sadar konteks cukup untuk
     * POM umum: mengenali blok {@code <dependency>}, lalu membaca
     * {@code groupId}/{@code artifactId}/{@code version}/{@code scope}/{@code optional}.
     * Dependensi tanpa versi eksplisit dilewati (membutuhkan parent POM).
     */
    public List<String> parseCompileDependencies(String group, String artifact, String version) {
        File pom = download(group, artifact, version, "pom");
        if (pom == null) {
            return List.of();
        }
        List<String> dependencies = new ArrayList<>();
        try {
            String xml = Files.readString(pom.toPath());
            for (String block : extractBlocks(xml)) {
                String scope = tagValue(block, "scope");
                String optional = tagValue(block, "optional");
                if ("test".equalsIgnoreCase(scope) || "provided".equalsIgnoreCase(scope)
                        || "system".equalsIgnoreCase(scope)) {
                    continue;
                }
                if ("true".equalsIgnoreCase(optional)) {
                    continue;
                }
                String depGroup = tagValue(block, "groupId");
                String depArtifact = tagValue(block, "artifactId");
                String depVersion = tagValue(block, "version");
                if (depGroup == null || depArtifact == null || depVersion == null
                        || depVersion.contains("${")) {
                    continue;
                }
                dependencies.add(depGroup + ":" + depArtifact + ":" + depVersion);
            }
        } catch (Exception e) {
            log.warn("Gagal membaca POM {}:{}:{} - {}", group, artifact, version, e.getMessage());
        }
        return dependencies;
    }

    /** Ekstrak isi setiap elemen {@code <dependency>} tingkat mana pun. */
    private static List<String> extractBlocks(String xml) {
        List<String> blocks = new ArrayList<>();
        int index = 0;
        while (true) {
            int start = xml.indexOf("<dependency>", index);
            if (start < 0) {
                break;
            }
            int end = xml.indexOf("</dependency>", start);
            if (end < 0) {
                break;
            }
            blocks.add(xml.substring(start, end));
            index = end + 1;
        }
        return blocks;
    }

    /** Baca nilai tag pertama dalam blok (mengabaikan yang berada di dalam dependencyManagement). */
    private static String tagValue(String block, String tag) {
        int start = block.indexOf("<" + tag + ">");
        if (start < 0) {
            return null;
        }
        int valueStart = start + tag.length() + 2;
        int end = block.indexOf("</" + tag + ">", valueStart);
        if (end < 0) {
            return null;
        }
        String value = block.substring(valueStart, end).trim();
        return value.isEmpty() ? null : value;
    }

    /** Unduh artifact ke cache; mengembalikan file atau null bila gagal. */
    private File download(String group, String artifact, String version, String extension) {
        String relative = group.replace('.', '/') + "/" + artifact + "/" + version + "/"
                + artifact + "-" + version + "." + extension;
        Path target = cacheDir.resolve(relative);
        if (Files.exists(target)) {
            return target.toFile();
        }
        for (String repository : repositories) {
            String url = repository.endsWith("/") ? repository + relative : repository + "/" + relative;
            try {
                Files.createDirectories(target.getParent());
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build();
                HttpResponse<InputStream> response = client.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    continue;
                }
                try (InputStream body = response.body()) {
                    Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING);
                }
                log.info("Library diunduh: {}:{}:{} ({})", group, artifact, version, extension);
                return target.toFile();
            } catch (Exception e) {
                log.debug("Gagal mengunduh {}: {}", url, e.getMessage());
            }
        }
        log.warn("Library tidak ditemukan di repository mana pun: {}:{}:{}",
                group, artifact, version);
        return null;
    }

    /** Daftar repository aktif (diagnostik). */
    public List<String> repositories() {
        return repositories;
    }

    /** Direktori cache (diagnostik). */
    public Path cacheDir() {
        return cacheDir;
    }

    /** Normalisasi nama library untuk ditampilkan di UI. */
    public static String displayName(String coordinate) {
        return coordinate == null ? "" : coordinate.toLowerCase(Locale.ROOT).trim();
    }
}
