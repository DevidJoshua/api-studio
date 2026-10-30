package io.apistudio.engine.nodes;

import io.apistudio.contracts.flow.FlowNode;
import io.apistudio.engine.runtime.AbstractNodeExecutor;
import io.apistudio.engine.runtime.FlowRuntimeContext;
import io.apistudio.engine.runtime.NodeResult;

/**
 * Node LOG: menulis pesan ke trace (BRD FR-SIM-02).
 *
 * <pre>{ "message": "Memproses ${request.body.id}" }</pre>
 */
public final class LogExecutor extends AbstractNodeExecutor {

    @Override
    protected NodeResult doExecute(FlowNode node, FlowRuntimeContext ctx) {
        String message = cfgText(node, "message", ctx, "");
        String level = cfgText(node, "level", ctx, "info");
        log(ctx, "[" + level.toLowerCase(java.util.Locale.ROOT) + "] " + message);
        return NodeResult.out(null);
    }
}
