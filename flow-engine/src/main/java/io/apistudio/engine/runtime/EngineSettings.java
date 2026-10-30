package io.apistudio.engine.runtime;

import io.apistudio.contracts.flow.NodeType;

import java.util.List;
import java.util.Set;

/**
 * Konfigurasi eksekusi engine (timeout global, sandbox, batas langkah).
 *
 * <p>Nilai default aman untuk MVP; Control Plane memetakannya dari
 * {@code application.yml} agar dapat disesuaikan per environment.
 */
public record EngineSettings(
        long flowTimeoutMs,
        int maxTraceSteps,
        boolean sandboxEnabled,
        int sandboxMaxRows,
        int maxSteps,
        Set<NodeType> allowedNodeTypes,
        List<String> allowedHosts
) {

    public EngineSettings {
        allowedNodeTypes = allowedNodeTypes == null || allowedNodeTypes.isEmpty()
                ? Set.of(NodeType.values())
                : Set.copyOf(allowedNodeTypes);
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
    }

    public static EngineSettings defaults() {
        return new EngineSettings(
                30_000,
                5_000,
                true,
                1_000,
                2_000,
                Set.of(NodeType.values()),
                List.of()
        );
    }

    public EngineSettings withAllowedHosts(List<String> hosts) {
        return new EngineSettings(flowTimeoutMs, maxTraceSteps, sandboxEnabled, sandboxMaxRows,
                maxSteps, allowedNodeTypes, hosts);
    }
}
