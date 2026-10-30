package io.apistudio.engine.nodes;

import io.apistudio.contracts.flow.FlowNode;
import io.apistudio.engine.java.ArtifactResolver;
import io.apistudio.engine.java.CompiledArtifact;
import io.apistudio.engine.java.JavaCodeRunner;
import io.apistudio.engine.runtime.AbstractNodeExecutor;
import io.apistudio.engine.runtime.FlowRuntimeContext;
import io.apistudio.engine.runtime.NodeResult;

/**
 * Node JAVA_CODE: menjalankan kode Java pengguna (BRD FR-JAVA-01..08).
 *
 * <p>Bytecode diambil dari {@link ArtifactResolver}; classloader terisolasi
 * per versi + node disediakan {@link JavaCodeRunner}.
 */
public final class JavaCodeExecutor extends AbstractNodeExecutor {

    private final JavaCodeRunner runner;
    private final ArtifactResolver artifacts;

    public JavaCodeExecutor(JavaCodeRunner runner, ArtifactResolver artifacts) {
        this.runner = runner;
        this.artifacts = artifacts == null ? ArtifactResolver.EMPTY : artifacts;
    }

    @Override
    protected NodeResult doExecute(FlowNode node, FlowRuntimeContext ctx) throws Exception {
        CompiledArtifact artifact = artifacts.resolve(node.id());
        if (artifact == null) {
            String source = node.cfgString("source", "");
            if (source != null && !source.isBlank()) {
                throw new IllegalStateException("Kode pada node " + node.id()
                        + " belum dikompilasi. Jalankan Check/dianggap gagal saat publish.");
            }
            throw new IllegalStateException("Node " + node.id() + " tidak memiliki kode Java");
        }

        String cacheKey = cacheKey(node, ctx);
        Object output = runner.run(cacheKey, artifact, ctx);
        ctx.setVar("javaResult", output);
        log(ctx, "Kode Java selesai dieksekusi");
        return NodeResult.out(output);
    }

    /** Kunci cache: versi flow + node, agar isolasi library per versi terjaga. */
    private String cacheKey(FlowNode node, FlowRuntimeContext ctx) {
        String version = ctx.request().flowVersion();
        return (version == null ? "unversioned" : version) + "/" + node.id();
    }
}
