package io.apistudio.engine.nodes;

import io.apistudio.contracts.flow.FlowEdge;
import io.apistudio.contracts.flow.FlowNode;
import io.apistudio.engine.runtime.AbstractNodeExecutor;
import io.apistudio.engine.runtime.FlowRuntimeContext;
import io.apistudio.engine.runtime.NodeResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Node CONDITION dan SWITCH (BRD FR-FLOW-02).
 *
 * <p>CONDITION mengevaluasi satu ekspresi dan memilih port {@code true} atau
 * {@code false}. SWITCH mengevaluasi nilai lalu memilih port sesuai case;
 * port {@code default} dipakai bila tidak ada yang cocok.
 *
 * <pre>
 * CONDITION: { "expression": "=request.body.amount > 1000" }
 * SWITCH:    { "expression": "=request.body.type", "cases": ["transfer", "payment"] }
 * </pre>
 */
public final class ConditionExecutor extends AbstractNodeExecutor {

    @Override
    protected NodeResult doExecute(FlowNode node, FlowRuntimeContext ctx) {
        String expression = node.cfgString("expression", null);
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Ekspresi wajib pada node " + node.id());
        }
        Object evaluated = ctx.valueOf(expression);

        if (node.type() == io.apistudio.contracts.flow.NodeType.SWITCH) {
            String port = switchPort(node, evaluated);
            log(ctx, "switch " + expression + " = " + evaluated + " -> port " + port);
            return NodeResult.to(port, evaluated);
        }

        boolean truthy = toBoolean(evaluated);
        log(ctx, "kondisi " + expression + " = " + evaluated + " -> " + truthy);
        return NodeResult.to(truthy ? FlowEdge.PORT_TRUE : FlowEdge.PORT_FALSE, evaluated);
    }

    /** Tentukan port SWITCH berdasarkan case yang dikonfigurasi. */
    private String switchPort(FlowNode node, Object evaluated) {
        Object rawCases = node.cfg("cases");
        if (rawCases instanceof java.util.List<?> cases) {
            String text = evaluated == null ? "" : String.valueOf(evaluated);
            for (Object item : cases) {
                if (item == null) {
                    continue;
                }
                String caseValue = String.valueOf(item);
                if (caseValue.equals(text)) {
                    return caseValue;
                }
            }
        }
        Object rawMap = node.cfg("caseMap");
        if (rawMap instanceof Map<?, ?> map) {
            Object mapped = map.get(evaluated == null ? "null" : String.valueOf(evaluated));
            if (mapped != null) {
                return String.valueOf(mapped);
            }
        }
        return "default";
    }

    /** Konversi nilai ekspresi menjadi boolean dengan aturan yang jelas. */
    static boolean toBoolean(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty() || "false".equalsIgnoreCase(trimmed) || "0".equals(trimmed)) {
                return false;
            }
            return true;
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        if (value instanceof java.util.List<?> l) {
            return !l.isEmpty();
        }
        return true;
    }

    /** Helper untuk node lain yang perlu membaca konfigurasi case. */
    static Map<String, Object> portMap(String port) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("port", port);
        return map;
    }
}
