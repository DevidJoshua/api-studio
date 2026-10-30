package io.apistudio.engine.runtime;

import io.apistudio.contracts.flow.FlowNode;
import io.apistudio.engine.expr.ExpressionEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dasar eksekutor node: menyediakan resolusi konfigurasi berbasis ekspresi,
 * penulisan variabel, dan log node.
 *
 * <p>Kelas turunan hanya perlu mengimplementasikan {@link #doExecute}.
 */
public abstract class AbstractNodeExecutor implements NodeExecutor {

    @Override
    public NodeResult execute(FlowNode node, FlowRuntimeContext ctx) throws Exception {
        return doExecute(node, ctx);
    }

    /** Logika node; dipanggil setelah pemeriksaan node aktif. */
    protected abstract NodeResult doExecute(FlowNode node, FlowRuntimeContext ctx) throws Exception;

    /**
     * Evaluasi konfigurasi node sebagai ekspresi.
     *
     * <p>Nilai yang diawali {@code =} atau memuat {@code ${...}} dievaluasi;
     * selain itu dikembalikan apa adanya.
     */
    protected Object cfgValue(FlowNode node, String key, FlowRuntimeContext ctx) {
        return ctx.expression().resolve(node.cfg(key));
    }

    /** Konfigurasi string yang sudah dievaluasi. */
    protected String cfgText(FlowNode node, String key, FlowRuntimeContext ctx, String fallback) {
        Object value = cfgValue(node, key, ctx);
        if (value == null) {
            return fallback;
        }
        String text = ExpressionEngine.stringify(value);
        return text.isEmpty() && fallback != null ? fallback : text;
    }

    /** Konfigurasi angka yang sudah dievaluasi. */
    protected Integer cfgNumber(FlowNode node, String key, FlowRuntimeContext ctx, Integer fallback) {
        Object value = cfgValue(node, key, ctx);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    /** Konfigurasi map yang sudah dievaluasi. */
    protected Map<String, Object> cfgMap(FlowNode node, String key, FlowRuntimeContext ctx) {
        Object value = cfgValue(node, key, ctx);
        Map<String, Object> map = io.apistudio.engine.expr.Json.asMap(value);
        return map == null ? new LinkedHashMap<>() : map;
    }

    /** Konfigurasi list yang sudah dievaluasi. */
    protected List<Object> cfgList(FlowNode node, String key, FlowRuntimeContext ctx) {
        Object value = cfgValue(node, key, ctx);
        List<Object> list = io.apistudio.engine.expr.Json.asList(value);
        if (list != null) {
            return list;
        }
        return value == null ? new ArrayList<>() : new ArrayList<>(List.of(value));
    }

    /** Tulis log node ke trace dan ke konteks. */
    protected void log(FlowRuntimeContext ctx, String message) {
        ctx.logCurrentNode(message);
    }
}
