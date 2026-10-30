package io.apistudio.engine.runtime;

import io.apistudio.contracts.exec.TraceStep;
import io.apistudio.contracts.flow.ErrorPolicy;
import io.apistudio.contracts.flow.FlowEdge;
import io.apistudio.contracts.flow.FlowNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Penelusur graph flow (BRD Bagian 5.2, FR-FLOW-04/06).
 *
 * <p>Algoritma:
 * <ol>
 *   <li>Mulai dari node TRIGGER (atau node root bila tidak ada trigger).</li>
 *   <li>Ambil eksekutor sesuai jenis node dari {@link NodeExecutorRegistry}.</li>
 *   <li>Jalankan node dengan retry/timeout/fallback sesuai {@link ErrorPolicy}.</li>
 *   <li>Pilih edge keluar pertama yang port-nya cocok dengan hasil node.</li>
 *   <li>Berhenti bila node tidak melanjutkan (RESPONSE) atau tidak ada edge.</li>
 * </ol>
 *
 * <p>Batas pengaman: jumlah langkah maksimum mencegah siklus tak berujung, dan
 * graf bersiklus ditolak validator sebelum eksekusi.
 */
public class FlowWalker {

    private final NodeExecutorRegistry registry;
    private final TraceRecorder trace;
    private final int maxSteps;

    public FlowWalker(NodeExecutorRegistry registry, TraceRecorder trace, int maxSteps) {
        this.registry = registry;
        this.trace = trace;
        this.maxSteps = maxSteps <= 0 ? 2000 : maxSteps;
    }

    public TraceRecorder trace() {
        return trace;
    }

    /**
     * Jalankan flow dari titik masuk alaminya.
     *
     * @return node RESPONSE terakhir yang dieksekusi, atau {@code null} bila
     *         flow tidak berakhir pada RESPONSE
     */
    public FlowNode walk(FlowRuntimeContext ctx) {
        FlowNode start = ctx.flow().trigger()
                .or(() -> ctx.flow().rootNodeIds().stream()
                        .findFirst()
                        .flatMap(id -> ctx.flow().node(id)))
                .orElse(null);
        if (start == null) {
            throw new IllegalStateException("Flow tidak memiliki node awal (TRIGGER)");
        }
        return walkFrom(ctx, start.id());
    }

    /**
     * Jalankan sub-graph dimulai dari node tertentu.
     *
     * @return node RESPONSE bila tercapai, selain itu {@code null}
     */
    public FlowNode walkFrom(FlowRuntimeContext ctx, String startNodeId) {
        String currentId = startNodeId;
        int steps = 0;
        Set<String> visited = new LinkedHashSet<>();

        while (currentId != null) {
            if (++steps > maxSteps) {
                throw new IllegalStateException("Eksekusi melebihi batas " + maxSteps
                        + " langkah; periksa kemungkinan siklus pada graph");
            }
            FlowNode node = ctx.flow().node(currentId).orElse(null);
            if (node == null) {
                ctx.log("Node tujuan tidak ditemukan: " + currentId + "; flow berhenti");
                return null;
            }
            visited.add(node.id());

            NodeOutcome outcome = executeNode(node, ctx);
            if (outcome.terminal()) {
                return node;
            }
            if (outcome.nextNodeId() == null) {
                return null;
            }
            currentId = outcome.nextNodeId();
        }
        return null;
    }

    /** Hasil satu langkah: node berikutnya dan apakah flow berhenti. */
    private record NodeOutcome(String nextNodeId, boolean terminal) {
    }

    /**
     * Eksekusi satu node beserta retry, timeout, dan fallback.
     */
    private NodeOutcome executeNode(FlowNode node, FlowRuntimeContext ctx) {
        if (node.disabled()) {
            trace.record(node.id(), node.type().name(), node.label(), TraceStep.Status.DISABLED,
                    null, null, null, 0, null, List.of(), 1);
            ctx.log("Node " + node.id() + " dilewati (nonaktif)");
            String next = nextNode(ctx, node, "out");
            return new NodeOutcome(next, false);
        }

        ErrorPolicy policy = node.errorPolicy();
        int maxAttempts = policy.retriesOrZero() + 1;
        RuntimeException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            ctx.enterNode(node.id());
            long started = System.currentTimeMillis();
            try {
                NodeResult result = runWithTimeout(node, ctx, policy);
                long duration = System.currentTimeMillis() - started;
                List<String> logs = ctx.drainCurrentNodeLogs();
                TraceStep.Status status = attempt > 1 ? TraceStep.Status.RETRIED : TraceStep.Status.OK;
                trace.record(node.id(), node.type().name(), node.label(), status,
                        result.port(), traceInput(node, ctx), result.output(), duration, null, logs, attempt);

                if (result.port() == null) {
                    return new NodeOutcome(null, true);
                }
                String next = nextNode(ctx, node, result.port());
                return new NodeOutcome(next, false);
            } catch (Exception failure) {
                long duration = System.currentTimeMillis() - started;
                List<String> logs = ctx.drainCurrentNodeLogs();
                lastError = failure instanceof RuntimeException re
                        ? re
                        : new IllegalStateException(failure.getMessage(), failure);

                if (attempt < maxAttempts) {
                    trace.record(node.id(), node.type().name(), node.label(), TraceStep.Status.RETRIED,
                            null, traceInput(node, ctx), null, duration,
                            "Percobaan " + attempt + " gagal: " + lastError.getMessage(), logs, attempt);
                    ctx.log("Retry node " + node.id() + " (" + attempt + "/" + maxAttempts + ")");
                    sleep(policy.backoffOrZero());
                    continue;
                }

                trace.record(node.id(), node.type().name(), node.label(), TraceStep.Status.ERROR,
                        null, traceInput(node, ctx), null, duration, lastError.getMessage(), logs, attempt);
                return handleFailure(ctx, node, policy, lastError);
            }
        }
        return handleFailure(ctx, node, policy, lastError);
    }

    /** Terapkan kebijakan onError setelah semua percobaan gagal. */
    private NodeOutcome handleFailure(FlowRuntimeContext ctx, FlowNode node, ErrorPolicy policy,
                                      RuntimeException error) {
        return switch (policy.onErrorOrDefault()) {
            case CONTINUE -> {
                ctx.log("Node " + node.id() + " gagal, dilanjutkan (onError=CONTINUE)");
                yield new NodeOutcome(nextNode(ctx, node, "out"), false);
            }
            case FALLBACK -> {
                String fallbackId = policy.fallbackNodeId();
                if (fallbackId == null || fallbackId.isBlank()) {
                    throw new IllegalStateException("onError=FALLBACK tetapi fallbackNodeId kosong pada node "
                            + node.id(), error);
                }
                ctx.log("Node " + node.id() + " gagal, beralih ke fallback " + fallbackId);
                yield new NodeOutcome(fallbackId, false);
            }
            default -> throw error;
        };
    }

    /**
     * Jalankan eksekutor dengan batas waktu bila dikonfigurasi.
     *
     * <p>Timeout diterapkan dengan menjalankan node pada thread terpisah lalu
     * menunggu hasilnya. Thread pekerja diinterupsi setelah batas tercapai.
     */
    private NodeResult runWithTimeout(FlowNode node, FlowRuntimeContext ctx, ErrorPolicy policy) throws Exception {
        NodeExecutor executor = registry.executorFor(node.type())
                .orElseThrow(() -> new IllegalStateException(
                        "Tidak ada eksekutor untuk jenis node " + node.type()));
        Long timeoutMs = policy.timeoutMs();
        if (timeoutMs == null || timeoutMs <= 0) {
            return executor.execute(node, ctx);
        }

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable, "flow-node-" + node.id());
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            java.util.concurrent.Future<NodeResult> future = pool.submit(() -> executor.execute(node, ctx));
            try {
                return future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                future.cancel(true);
                throw new IllegalStateException("Node " + node.id() + " melewati timeout "
                        + timeoutMs + " ms");
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof Exception checked) {
                    throw checked;
                }
                throw new IllegalStateException(cause.getMessage(), cause);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Tentukan node berikutnya berdasarkan port.
     *
     * <p>Bila port spesifik tidak punya edge, port {@code out} dicoba sebagai
     * fallback agar node dengan satu keluaran tidak perlu konfigurasi ganda.
     */
    private String nextNode(FlowRuntimeContext ctx, FlowNode node, String port) {
        List<FlowEdge> outgoing = ctx.flow().outgoing(node.id());
        if (outgoing.isEmpty()) {
            return null;
        }
        String target = port == null ? FlowEdge.PORT_OUT : port;
        Optional<FlowEdge> direct = outgoing.stream()
                .filter(e -> e.portOrDefault().equals(target))
                .findFirst();
        if (direct.isPresent()) {
            return direct.get().to();
        }
        return outgoing.stream()
                .filter(e -> e.portOrDefault().equals(FlowEdge.PORT_OUT))
                .findFirst()
                .map(FlowEdge::to)
                .orElse(null);
    }

    /**
     * Data yang dicatat sebagai input node pada trace.
     *
     * <p>Untuk TRIGGER dipakai data request (method, path, header ter-mask,
     * body) karena itulah yang benar-benar "masuk". Untuk node lain dipakai
     * konfigurasi node agar pengguna dapat memeriksa parameter yang dipakai.
     * Nilainya dimask oleh {@link TraceRecorder} sebelum disimpan.
     */
    private static Object traceInput(FlowNode node, FlowRuntimeContext ctx) {
        if (node.type() == io.apistudio.contracts.flow.NodeType.TRIGGER) {
            Map<String, Object> input = new java.util.LinkedHashMap<>();
            input.put("method", ctx.request().method());
            input.put("path", ctx.request().path());
            input.put("headers", Masking.maskHeaders(ctx.request().headers()));
            input.put("query", ctx.request().query());
            input.put("body", ctx.request().body());
            return input;
        }
        return node.config();
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
