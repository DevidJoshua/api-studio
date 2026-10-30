package io.apistudio.engine.nodes;

import io.apistudio.contracts.flow.FlowNode;
import io.apistudio.engine.runtime.AbstractNodeExecutor;
import io.apistudio.engine.runtime.FlowRuntimeContext;
import io.apistudio.engine.runtime.NodeResult;

/**
 * Node DELAY: menunda eksekusi (dipakai untuk menguji timeout/retry).
 *
 * <pre>{ "ms": 500 }</pre>
 */
public final class DelayExecutor extends AbstractNodeExecutor {

    /** Batas atas penundaan agar flow tidak menggantung terlalu lama. */
    private static final int MAX_DELAY_MS = 30_000;

    @Override
    protected NodeResult doExecute(FlowNode node, FlowRuntimeContext ctx) throws InterruptedException {
        int ms = cfgNumber(node, "ms", ctx, 0);
        int bounded = Math.max(0, Math.min(ms, MAX_DELAY_MS));
        if (bounded > 0) {
            ctx.log("Menunggu " + bounded + " ms");
            Thread.sleep(bounded);
        }
        return NodeResult.out(bounded);
    }
}
