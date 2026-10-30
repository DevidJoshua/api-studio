package io.apistudio.engine.java;

import java.util.Map;

/**
 * Penyedia artifact terkompilasi untuk satu run (BRD FR-JAVA-03/05).
 *
 * <p>Engine meminta artifact berdasarkan id node; Control Plane menyediakan
 * implementasi yang membaca bytecode tersimpan atau hasil kompilasi terbaru.
 */
@FunctionalInterface
public interface ArtifactResolver {

    /** Resolver kosong: tidak ada kode Java terkompilasi. */
    ArtifactResolver EMPTY = nodeId -> null;

    /**
     * Ambil artifact untuk node.
     *
     * @return artifact, atau {@code null} bila node tidak punya kode terkompilasi
     */
    CompiledArtifact resolve(String nodeId);

    /** Bangun resolver dari map artifact. */
    static ArtifactResolver of(Map<String, CompiledArtifact> artifacts) {
        return nodeId -> artifacts == null ? null : artifacts.get(nodeId);
    }
}
