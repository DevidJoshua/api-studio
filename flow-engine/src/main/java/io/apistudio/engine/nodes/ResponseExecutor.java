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
 * Node RESPONSE: menyusun response akhir flow (BRD FR-FLOW-02).
 *
 * <p>Konfigurasi:
 * <pre>
 * {
 *   "status": 200,
 *   "headers": { "Content-Type": "application/json" },
 *   "body": { "responseCode": "2007300", "id": "=request.body.id" }
 * }
 * </pre>
 * Nilai body dievaluasi secara rekursif sehingga ekspresi dapat dipakai di
 * kedalaman mana pun.
 */
public final class ResponseExecutor extends AbstractNodeExecutor {

    @Override
    public String defaultPort() {
        return "out";
    }

    @Override
    protected NodeResult doExecute(FlowNode node, FlowRuntimeContext ctx) {
        int status = cfgNumber(node, "status", ctx, 200);

        Map<String, Object> headers = new LinkedHashMap<>();
        Object rawHeaders = node.cfg("headers");
        Map<String, Object> headerMap = Json.asMap(rawHeaders);
        if (headerMap != null) {
            headerMap.forEach((k, v) -> {
                Object value = ctx.valueOf(v);
                headers.put(k, ExpressionEngine.stringify(value));
            });
        }
        if (!headers.containsKey("Content-Type")) {
            headers.put("Content-Type", "application/json");
        }

        Object body = ctx.valueOf(node.cfg("body"));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status);
        response.put("headers", headers);
        response.put("body", body);
        ctx.setResponse(response);
        ctx.setVar("status", status);

        log(ctx, "Response " + status + " disiapkan");
        // RESPONSE menghentikan flow; port null menandakan selesai
        return NodeResult.stop(response);
    }
}
