package io.apistudio.engine.runtime;

import io.apistudio.contracts.flow.FlowNode;

/** Eksekutor untuk satu jenis node. */
public interface NodeExecutor {

    /**
     * Jalankan node.
     *
     * @param node node yang dieksekusi
     * @param ctx  konteks run
     * @return hasil node (port berikutnya dan output)
     * @throws Exception bila eksekusi gagal
     */
    NodeResult execute(FlowNode node, FlowRuntimeContext ctx) throws Exception;

    /** Port default node ketika tidak ada port khusus. */
    default String defaultPort() {
        return "out";
    }
}
