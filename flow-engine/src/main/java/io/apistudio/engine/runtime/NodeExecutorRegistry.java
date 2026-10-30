package io.apistudio.engine.runtime;

import io.apistudio.contracts.flow.NodeType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** Registry eksekutor per jenis node (BRD FR-FLOW-02). */
public final class NodeExecutorRegistry {

    private final Map<NodeType, NodeExecutor> executors = new EnumMap<>(NodeType.class);

    /** Daftarkan eksekutor; pendaftaran ulang menimpa yang lama. */
    public NodeExecutorRegistry register(NodeType type, NodeExecutor executor) {
        executors.put(type, executor);
        return this;
    }

    public Optional<NodeExecutor> executorFor(NodeType type) {
        return Optional.ofNullable(executors.get(type));
    }

    public boolean supports(NodeType type) {
        return executors.containsKey(type);
    }

    public int size() {
        return executors.size();
    }
}
