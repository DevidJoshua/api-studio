package io.apistudio.engine.nodes;

import io.apistudio.contracts.flow.FlowNode;
import io.apistudio.engine.expr.ExpressionEngine;
import io.apistudio.engine.expr.Json;
import io.apistudio.engine.runtime.AbstractNodeExecutor;
import io.apistudio.engine.runtime.FlowRuntimeContext;
import io.apistudio.engine.runtime.NodeResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Node TEMPLATE_CALL: memanggil function template (BRD FR-TPL-01..07).
 *
 * <pre>
 * {
 *   "templateKey": "snapbi.signature",
 *   "version": 1,
 *   "args": { "method": "=request.method", "path": "=request.path" }
 * }
 * </pre>
 *
 * <p>Versi template menjadi bagian dari kunci sehingga flow mengunci ke versi
 * tertentu dan hanya naik versi secara eksplisit (FR-TPL-04).
 */
public final class TemplateCallExecutor extends AbstractNodeExecutor {

    @Override
    protected NodeResult doExecute(FlowNode node, FlowRuntimeContext ctx) {
        String key = cfgText(node, "templateKey", ctx, "");
        if (key.isBlank()) {
            throw new IllegalArgumentException("templateKey wajib pada node " + node.id());
        }
        Integer version = cfgNumber(node, "version", ctx, null);
        String templateKey = key.contains("@") || version == null ? key : key + "@" + version;

        Map<String, Object> args = new LinkedHashMap<>();
        Object raw = node.cfg("args");
        Map<String, Object> rawMap = Json.asMap(raw);
        if (rawMap != null) {
            rawMap.forEach((k, v) -> args.put(k, ctx.valueOf(v)));
        }

        Object result = ctx.templates().invoke(templateKey, args, ctx);
        ctx.setVar("templateResult", result);
        log(ctx, "Template " + templateKey + " -> "
                + ExpressionEngine.stringify(result));
        return NodeResult.out(result);
    }
}
