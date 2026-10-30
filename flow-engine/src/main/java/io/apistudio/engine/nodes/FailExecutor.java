package io.apistudio.engine.nodes;

import io.apistudio.contracts.flow.FlowNode;
import io.apistudio.engine.runtime.AbstractNodeExecutor;
import io.apistudio.engine.runtime.FlowRuntimeContext;
import io.apistudio.engine.runtime.NodeResult;

/**
 * Node FAIL: menggagalkan flow secara sengaja (menguji jalur error).
 *
 * <pre>{ "message": "Saldo tidak cukup", "status": 422 }</pre>
 */
public final class FailExecutor extends AbstractNodeExecutor {

    @Override
    protected NodeResult doExecute(FlowNode node, FlowRuntimeContext ctx) {
        String message = cfgText(node, "message", ctx, "Flow digagalkan oleh node " + node.id());
        log(ctx, "Gagal: " + message);
        throw new IllegalStateException(message);
    }
}
