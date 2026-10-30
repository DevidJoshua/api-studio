package io.apistudio.engine.java;

import java.util.List;
import java.util.Map;

/**
 * Artifact terkompilasi untuk satu node Java (BRD FR-JAVA-03).
 *
 * @param nodeId     id node pemilik kode
 * @param className  nama kelas utama yang berisi method {@code run}
 * @param classes    bytecode kelas pengguna: nama kelas &rarr; isi
 * @param jars       daftar JAR pendukung (sudah didekode)
 */
public record CompiledArtifact(
        String nodeId,
        String className,
        Map<String, byte[]> classes,
        List<byte[]> jars
) {

    public CompiledArtifact {
        classes = classes == null ? Map.of() : Map.copyOf(classes);
        jars = jars == null ? List.of() : List.copyOf(jars);
    }

    public int classCount() {
        return classes.size();
    }

    public int jarCount() {
        return jars.size();
    }
}
