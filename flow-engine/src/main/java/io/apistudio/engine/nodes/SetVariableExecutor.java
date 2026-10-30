package io.apistudio.engine.nodes;

import io.apistudio.contracts.flow.FlowNode;
import io.apistudio.engine.expr.Json;
import io.apistudio.engine.runtime.AbstractNodeExecutor;
import io.apistudio.engine.runtime.FlowRuntimeContext;
import io.apistudio.engine.runtime.NodeResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Node SET_VARIABLE dan TRANSFORM (BRD FR-FLOW-02, FR-FLOW-07).
 *
 * <p>Konfigurasi:
 * <pre>
 * { "assignments": { "total": "=request.body.price * request.body.qty" } }
 * </pre>
 * Setiap nilai dievaluasi sebagai ekspresi lalu ditulis ke {@code vars}.
 */
public final class SetVariableExecutor extends AbstractNodeExecutor {

    @Override
    protected NodeResult doExecute(FlowNode node, FlowRuntimeContext ctx) {
        Map<String, Object> assignments = new LinkedHashMap<>();

        Object raw = node.cfg("assignments");
        Map<String, Object> map = Json.asMap(raw);
        if (map != null) {
            map.forEach((name, expression) -> assignments.put(name, ctx.valueOf(expression)));
        } else {
            // dukungan bentuk ringkas: { "name": "...", "value": "=..." }
            String name = node.cfgString("name", null);
            if (name != null && !name.isBlank()) {
                assignments.put(name, ctx.valueOf(node.cfg("value")));
            }
        }

        assignments.forEach(ctx::setVar);
        assignments.forEach((k, v) -> log(ctx, "set " + k + " = " + io.apistudio.engine.expr.ExpressionEngine.stringify(v)));
        return NodeResult.out(assignments);
    }
}
