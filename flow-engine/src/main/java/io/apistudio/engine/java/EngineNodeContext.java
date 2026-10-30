package io.apistudio.engine.java;

import io.apistudio.contracts.node.NodeContext;
import io.apistudio.engine.expr.Json;
import io.apistudio.engine.runtime.FlowRuntimeContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Implementasi {@link NodeContext} yang menjembatani kode pengguna ke
 * {@link FlowRuntimeContext}.
 *
 * <p>Kode pengguna hanya melihat antarmuka sempit ini; tidak ada akses ke
 * runtime, classloader, atau sandbox database secara langsung.
 */
final class EngineNodeContext implements NodeContext {

    private final FlowRuntimeContext ctx;
    private final String nodeId;

    EngineNodeContext(FlowRuntimeContext ctx, String nodeId) {
        this.ctx = ctx;
        this.nodeId = nodeId;
    }

    @Override
    public Object get(String name) {
        Object vars = ctx.get("vars");
        if (vars instanceof Map<?, ?> map && map.containsKey(name)) {
            return map.get(name);
        }
        return ctx.get(name);
    }

    @Override
    public void set(String name, Object value) {
        ctx.setVar(name, value);
    }

    @Override
    public Object input(String field) {
        Map<String, Object> body = inputMap();
        if (body.containsKey(field)) {
            return body.get(field);
        }
        // dukung path bertitik: input("amount.value")
        if (field != null && field.contains(".")) {
            return Json.at(body, field);
        }
        return null;
    }

    @Override
    public Map<String, Object> inputMap() {
        Map<String, Object> body = Json.asMap(ctx.request().body());
        return body == null ? new LinkedHashMap<>() : body;
    }

    @Override
    public Map<String, String> headers() {
        return ctx.request().headers();
    }

    @Override
    public Map<String, String> query() {
        return ctx.request().query();
    }

    @Override
    public Object eval(String expression) {
        return ctx.eval(expression);
    }

    @Override
    public void log(String message) {
        ctx.logCurrentNode(nodeId + ": " + message);
    }

    @Override
    public Object callTemplate(String templateKey, Map<String, Object> args) {
        return ctx.templates().invoke(templateKey, args == null ? Map.of() : args, ctx);
    }

    @Override
    public String nodeId() {
        return nodeId;
    }
}
