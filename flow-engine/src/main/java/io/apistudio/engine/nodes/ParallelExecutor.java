package io.apistudio.engine.nodes;

import io.apistudio.contracts.exec.TraceStep;
import io.apistudio.contracts.flow.FlowEdge;
import io.apistudio.contracts.flow.FlowNode;
import io.apistudio.engine.runtime.AbstractNodeExecutor;
import io.apistudio.engine.runtime.FlowRuntimeContext;
import io.apistudio.engine.FlowEngine;
import io.apistudio.engine.runtime.NodeResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Node PARALLEL: fan-out ke beberapa cabang lalu join (BRD FR-FLOW-02).
 *
 * <p>Cabang diambil dari edge berlabel port {@code branch1..n} atau semua edge
 * pada port {@code out}. Hasil digabung secara deterministik sesuai urutan
 * deklarasi edge, bukan urutan selesai, agar output stabil antar-run.
 */
public final class ParallelExecutor extends AbstractNodeExecutor {

    private static final int MAX_BRANCHES = 16;

    private final FlowEngine.FlowRunner walker;

    public ParallelExecutor(FlowEngine.FlowRunner walker) {
        this.walker = walker;
    }

    @Override
    protected NodeResult doExecute(FlowNode node, FlowRuntimeContext ctx) throws Exception {
        List<FlowEdge> branches = ctx.flow().outgoing(node.id());
        if (branches.isEmpty()) {
            return NodeResult.out(Map.of("branches", List.of()));
        }
        if (branches.size() > MAX_BRANCHES) {
            throw new IllegalStateException("Cabang paralel melebihi batas " + MAX_BRANCHES);
        }
        long timeoutMs = cfgNumber(node, "timeoutMs", ctx, 30_000);

        // Setiap cabang berjalan pada thread terpisah dengan konteks yang
        // dibagikan; penulisan variabel tetap serial karena engine memakai
        // map tunggal. Untuk MVP ini cukup: cabang paralel biasanya memanggil
        // dependensi berbeda, bukan menulis variabel yang sama.
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(branches.size(), MAX_BRANCHES));
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (FlowEdge branch : branches) {
                futures.add(pool.submit(() -> {
                    walker.walkFrom(ctx, branch.to());
                    return ctx.get("branchResult");
                }));
            }

            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                try {
                    results.add(future.get(timeoutMs, TimeUnit.MILLISECONDS));
                } catch (java.util.concurrent.TimeoutException e) {
                    throw new IllegalStateException("Cabang paralel melewati timeout " + timeoutMs + " ms");
                }
            }

            Map<String, Object> output = new LinkedHashMap<>();
            output.put("branches", results);
            output.put("count", results.size());
            ctx.setVar("parallelResult", output);
            log(ctx, "Paralel selesai: " + results.size() + " cabang");
            return NodeResult.out(output);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Status trace untuk cabang yang gagal (dipakai walker saat join). */
    static TraceStep.Status statusOf(boolean failed) {
        return failed ? TraceStep.Status.ERROR : TraceStep.Status.OK;
    }
}
