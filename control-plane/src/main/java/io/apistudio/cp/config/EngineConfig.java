package io.apistudio.cp.config;

import io.apistudio.cp.compiler.MavenLibraryResolver;
import io.apistudio.engine.FlowEngine;
import io.apistudio.engine.runtime.EngineSettings;
import io.apistudio.engine.validation.FlowValidator;
import io.apistudio.contracts.flow.NodeType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Konfigurasi bean inti (BRD Bagian 5.1, 10.3).
 *
 * <p>Control Plane menyematkan Flow Engine pada MVP agar deployment sederhana.
 * Pada arsitektur target, Flow Engine berjalan sebagai service terpisah dan
 * Control Plane memanggilnya via gRPC/HTTP. Pemisahan ini dijaga di level
 * antarmuka: engine tidak mengimpor kelas Control Plane.
 */
@Configuration
public class EngineConfig {

    /**
     * Flow Engine tunggal untuk seluruh proses.
     *
     * <p>Bean dibuat singleton agar cache classloader dipakai bersama. Cache
     * inilah yang harus dilepas saat versi di-drain (NFR-06).
     */
    @Bean
    public FlowEngine flowEngine(@Value("${apistudio.engine.flow-timeout-ms:30000}") long flowTimeoutMs,
                                 @Value("${apistudio.engine.max-steps:2000}") int maxSteps,
                                 @Value("${apistudio.engine.max-trace-steps:5000}") int maxTraceSteps,
                                 @Value("${apistudio.engine.sandbox-max-rows:1000}") int sandboxMaxRows,
                                 @Value("${apistudio.engine.sandbox-enabled:true}") boolean sandboxEnabled) {
        EngineSettings settings = new EngineSettings(
                flowTimeoutMs,
                maxTraceSteps,
                sandboxEnabled,
                sandboxMaxRows,
                maxSteps,
                Set.of(NodeType.values()),
                List.of()
        );
        return new FlowEngine(settings);
    }

    /** Validator flow dipakai saat validasi manual dan gate publish. */
    @Bean
    public FlowValidator flowValidator() {
        return new FlowValidator();
    }

    /** Resolver library Maven untuk node Java Code (FR-JAVA-04). */
    @Bean
    public MavenLibraryResolver mavenLibraryResolver(
            @Value("${apistudio.library-cache}") String libraryCache,
            @Value("${apistudio.maven-repositories:https://repo1.maven.org/maven2}") String repositoriesCsv) {
        // Daftar repository dapat diberikan sebagai CSV; dinormalisasi di sini
        // agar konfigurasi mudah diubah tanpa mengubah kode.
        List<String> repositories = Arrays.stream(repositoriesCsv.split("[,\\s]+"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        return new MavenLibraryResolver(Path.of(libraryCache), repositories);
    }
}
