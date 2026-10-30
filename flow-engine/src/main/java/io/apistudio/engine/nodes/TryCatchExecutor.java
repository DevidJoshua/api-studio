package io.apistudio.engine.nodes;

import io.apistudio.contracts.flow.FlowEdge;
import io.apistudio.contracts.flow.FlowNode;
import io.apistudio.engine.runtime.AbstractNodeExecutor;
import io.apistudio.engine.runtime.FlowRuntimeContext;
import io.apistudio.engine.FlowEngine;
import io.apistudio.engine.runtime.NodeResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Node TRY_CATCH: menangkap kegagalan sub-graph (BRD FR-FLOW-06).
 *
 * <p>Sub-graph pada port {@code out} dijalankan; bila gagal, sub-graph pada
 * port {@code error} dijalankan sebagai penangan. Error yang tertangkap tetap
 * dicatat ke trace.
 *
 * <pre>{ "catchMessage": "Transfer gagal diproses" }</pre>
 */
public final class TryCatchExecutor extends AbstractNodeExecutor {

    private final FlowEngine.FlowRunner walker;

    public TryCatchExecutor(FlowEngine.FlowRunner walker) {
        this.walker = walker;
    }

    @Override
    protected NodeResult doExecute(FlowNode node, FlowRuntimeContext ctx) {
        FlowEdge tryEdge = ctx.flow().outgoing(node.id(), "out").orElse(null);
        FlowEdge catchEdge = ctx.flow().outgoing(node.id(), "error").orElse(null);

        if (tryEdge == null) {
            throw new IllegalArgumentException("Node TRY_CATCH " + node.id() + " membutuhkan port 'out'");
        }

        Map<String, Object> output = new LinkedHashMap<>();
        try {
            walker.walkFrom(ctx, tryEdge.to());
            output.put("caught", false);
            output.put("result", ctx.get("branchResult"));
            ctx.setVar("tryResult", output);
            // Bila sub-graph berakhir pada RESPONSE, hentikan flow: response
            // sudah disusun dan tidak boleh ditimpa node setelah TRY_CATCH.
            if (ctx.get("response") != null) {
                return NodeResult.stop(output);
            }
            return NodeResult.out(output);
        } catch (RuntimeException failure) {
            ctx.log("Kegagalan tertangkap: " + failure.getMessage());
            output.put("caught", true);
            output.put("error", failure.getMessage());
            output.put("errorType", failure.getClass().getSimpleName());

            if (catchEdge == null) {
                // tanpa handler: laporkan kegagalan ke pemanggil
                throw failure;
            }
            ctx.setVar("catchError", output);
            walker.walkFrom(ctx, catchEdge.to());
            output.put("handled", ctx.get("branchResult"));
            ctx.setVar("tryResult", output);
            if (ctx.get("response") != null) {
                return NodeResult.stop(output);
            }
            return NodeResult.out(output);
        }
    }
}
