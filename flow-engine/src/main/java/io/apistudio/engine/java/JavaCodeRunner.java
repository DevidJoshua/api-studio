package io.apistudio.engine.java;

import io.apistudio.contracts.node.NodeContext;
import io.apistudio.engine.runtime.FlowRuntimeContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Menjalankan kode Java pengguna yang sudah dikompilasi (BRD FR-JAVA-01..08).
 *
 * <p>Kelas pengguna dibungkus oleh Control Plane menjadi kelas dengan method
 * {@code public Object run(NodeContext ctx)}. Runner ini membuat classloader
 * terisolasi per artifact, memuat kelas, lalu memanggil method tersebut.
 */
public final class JavaCodeRunner {

    private final ClassLoaderCache classLoaderCache;

    public JavaCodeRunner(ClassLoaderCache classLoaderCache) {
        this.classLoaderCache = classLoaderCache == null ? new ClassLoaderCache() : classLoaderCache;
    }

    public ClassLoaderCache cache() {
        return classLoaderCache;
    }

    /**
     * Jalankan artifact.
     *
     * @param cacheKey kunci cache classloader (versi + node)
     * @param artifact artifact terkompilasi
     * @param ctx      konteks flow yang diteruskan ke kode pengguna
     * @return nilai hasil {@code run}
     * @throws Exception kegagalan kompilasi/pemuatan/eksekusi kode pengguna
     */
    public Object run(String cacheKey, CompiledArtifact artifact, FlowRuntimeContext ctx) throws Exception {
        if (artifact == null) {
            throw new IllegalStateException("Artifact Java tidak tersedia untuk node ini");
        }
        SandboxClassLoader loader = classLoaderCache.getOrCreate(cacheKey, () -> buildLoader(artifact));
        Class<?> type;
        try {
            type = Class.forName(artifact.className(), true, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Kelas hasil kompilasi tidak ditemukan: "
                    + artifact.className(), e);
        }

        NodeContext nodeContext = new EngineNodeContext(ctx, artifact.nodeId());
        try {
            Method run = type.getMethod("run", NodeContext.class);
            return run.invoke(type.getDeclaredConstructor().newInstance(), nodeContext);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Kelas " + artifact.className()
                    + " tidak memiliki method run(NodeContext)", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            throw new IllegalStateException(cause.getMessage(), cause);
        }
    }

    private SandboxClassLoader buildLoader(CompiledArtifact artifact) {
        SandboxClassLoader loader = new SandboxClassLoader(getClass().getClassLoader());
        for (byte[] jar : artifact.jars()) {
            loader.addJar(jar);
        }
        artifact.classes().forEach(loader::addClass);
        return loader;
    }
}
