package io.apistudio.engine.nodes;

import io.apistudio.contracts.flow.FlowNode;
import io.apistudio.engine.runtime.AbstractNodeExecutor;
import io.apistudio.engine.runtime.FlowRuntimeContext;
import io.apistudio.engine.runtime.NodeResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Node TRIGGER: titik masuk flow (BRD FR-FLOW-01).
 *
 * <p>Menyusun ringkasan request yang masuk sehingga node berikutnya dapat
 * memakainya lewat variabel {@code request}.
 */
public final class TriggerExecutor extends AbstractNodeExecutor {

    @Override
    protected NodeResult doExecute(FlowNode node, FlowRuntimeContext ctx) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("method", ctx.request().method());
        output.put("path", ctx.request().path());
        output.put("headerCount", ctx.request().headers().size());
        output.put("queryCount", ctx.request().query().size());
        log(ctx, "Menerima request " + ctx.request().method() + " " + ctx.request().path());
        return NodeResult.out(output);
    }
}
