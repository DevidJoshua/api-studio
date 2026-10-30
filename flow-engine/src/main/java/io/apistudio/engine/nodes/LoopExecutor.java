package io.apistudio.engine.nodes;

import io.apistudio.contracts.flow.FlowEdge;
import io.apistudio.contracts.flow.FlowNode;
import io.apistudio.engine.expr.Json;
import io.apistudio.engine.runtime.AbstractNodeExecutor;
import io.apistudio.engine.runtime.FlowRuntimeContext;
import io.apistudio.engine.FlowEngine;
import io.apistudio.engine.runtime.NodeResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Node LOOP: iterasi koleksi (BRD FR-FLOW-02).
 *
 * <pre>
 * { "items": "=request.body.orders", "itemName": "order" }
 * </pre>
 *
 * <p>Untuk setiap item, sub-graph pada port {@code out} dieksekusi dengan
 * variabel {@code loop.item} dan {@code loop.index}. Hasil tiap iterasi
 * dikumpulkan pada output {@code results}.
 */
public final class LoopExecutor extends AbstractNodeExecutor {

    private final FlowEngine.FlowRunner walker;

    public LoopExecutor(FlowEngine.FlowRunner walker) {
        this.walker = walker;
    }

    @Override
    protected NodeResult doExecute(FlowNode node, FlowRuntimeContext ctx) {
        Object raw = cfgValue(node, "items", ctx);
        List<Object> items = Json.asList(raw);
        if (items == null) {
            items = new ArrayList<>();
            if (raw != null) {
                items.add(raw);
            }
        }

        String itemName = cfgText(node, "itemName", ctx, "item");
        int maxIterations = cfgNumber(node, "maxIterations", ctx, 1000);
        if (items.size() > maxIterations) {
            throw new IllegalStateException("Jumlah iterasi " + items.size()
                    + " melebihi batas " + maxIterations);
        }

        FlowEdge bodyEdge = ctx.flow().outgoing(node.id(), "out").orElse(null);
        if (bodyEdge == null) {
            // tanpa body: hanya kembalikan koleksi apa adanya
            return NodeResult.out(Map.of("results", items, "count", items.size()));
        }

        List<Object> results = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            Map<String, Object> loopVars = new LinkedHashMap<>();
            loopVars.put("index", index);
            loopVars.put(itemName, items.get(index));
            loopVars.put("item", items.get(index));
            loopVars.put("isFirst", index == 0);
            loopVars.put("isLast", index == items.size() - 1);
            ctx.setVar("loop", loopVars);

            walker.walkFrom(ctx, bodyEdge.to());
            results.add(ctx.get("loopResult"));
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("results", results);
        output.put("count", results.size());
        ctx.setVar("loopResult", output);
        log(ctx, "Loop selesai untuk " + results.size() + " item");
        return NodeResult.out(output);
    }
}
