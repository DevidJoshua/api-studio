package io.apistudio.engine.validation;

import io.apistudio.contracts.flow.FlowDefinition;
import io.apistudio.contracts.flow.FlowEdge;
import io.apistudio.contracts.flow.FlowNode;
import io.apistudio.contracts.flow.NodeType;
import io.apistudio.engine.expr.ExpressionEngine;
import io.apistudio.engine.expr.ExpressionException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validasi flow sebelum disimpan/dipublish (BRD FR-FLOW-04).
 *
 * <p>Pemeriksaan yang dilakukan:
 * <ul>
 *   <li>Graph memiliki tepat satu TRIGGER dan minimal satu RESPONSE.</li>
 *   <li>Semua edge menunjuk node yang ada dan tidak menunjuk dirinya sendiri.</li>
 *   <li>Tidak ada siklus tak disengaja pada graph.</li>
 *   <li>Konfigurasi wajib setiap jenis node terisi.</li>
 *   <li>Ekspresi yang dipakai dapat diparse.</li>
 *   <li>Node DATABASE_QUERY hanya memakai SELECT.</li>
 *   <li>Kebijakan FALLBACK menyebut node yang ada.</li>
 * </ul>
 */
public final class FlowValidator {

    /** Hasil validasi lengkap. */
    public record Report(List<ValidationIssue> issues) {

        public Report {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }

        public boolean valid() {
            return issues.stream().noneMatch(ValidationIssue::isError);
        }

        public long errorCount() {
            return issues.stream().filter(ValidationIssue::isError).count();
        }

        public long warningCount() {
            return issues.stream().filter(i -> !i.isError()).count();
        }

        /** Bentuk map untuk respons JSON. */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("valid", valid());
            map.put("errors", errorCount());
            map.put("warnings", warningCount());
            map.put("issues", issues.stream().map(i -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("severity", i.severity().name());
                item.put("code", i.code());
                item.put("message", i.message());
                item.put("nodeId", i.nodeId());
                return item;
            }).toList());
            return map;
        }
    }

    private static final List<String> SELECT_PREFIXES = List.of("select", "with");

    public Report validate(FlowDefinition flow) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (flow == null) {
            issues.add(ValidationIssue.error("FLOW_EMPTY", "Definisi flow kosong", null));
            return new Report(issues);
        }
        if (flow.nodes().isEmpty()) {
            issues.add(ValidationIssue.error("FLOW_EMPTY", "Flow belum memiliki node", null));
            return new Report(issues);
        }

        validateNodes(flow, issues);
        validateEdges(flow, issues);
        detectCycles(flow, issues);
        validateConfig(flow, issues);
        validateExpressions(flow, issues);
        validateReachability(flow, issues);
        return new Report(issues);
    }

    private void validateNodes(FlowDefinition flow, List<ValidationIssue> issues) {
        List<FlowNode> triggers = flow.nodes().stream()
                .filter(n -> n.type() == NodeType.TRIGGER)
                .toList();
        if (triggers.isEmpty()) {
            issues.add(ValidationIssue.error("TRIGGER_MISSING",
                    "Flow harus memiliki satu node TRIGGER sebagai titik masuk", null));
        } else if (triggers.size() > 1) {
            issues.add(ValidationIssue.error("TRIGGER_MULTIPLE",
                    "Flow hanya boleh memiliki satu node TRIGGER, ditemukan " + triggers.size(), null));
        }

        if (flow.nodes().stream().noneMatch(n -> n.type() == NodeType.RESPONSE)) {
            issues.add(ValidationIssue.warning("RESPONSE_MISSING",
                    "Flow tidak memiliki node RESPONSE; response dibentuk dari variabel terakhir", null));
        }

        Set<String> ids = new HashSet<>();
        for (FlowNode node : flow.nodes()) {
            if (node.id() == null || node.id().isBlank()) {
                issues.add(ValidationIssue.error("NODE_ID_EMPTY", "Ada node tanpa id", null));
                continue;
            }
            if (!ids.add(node.id())) {
                issues.add(ValidationIssue.error("NODE_ID_DUPLICATE",
                        "Id node duplikat: " + node.id(), node.id()));
            }
            if (node.label() == null || node.label().isBlank()) {
                issues.add(ValidationIssue.warning("NODE_LABEL_EMPTY",
                        "Node " + node.id() + " tidak memiliki judul", node.id()));
            }
        }
    }

    private void validateEdges(FlowDefinition flow, List<ValidationIssue> issues) {
        Set<String> ids = new HashSet<>();
        for (FlowNode node : flow.nodes()) {
            ids.add(node.id());
        }
        Set<String> uniqueEdges = new LinkedHashSet<>();
        for (FlowEdge edge : flow.edges()) {
            if (edge.from() == null || !ids.contains(edge.from())) {
                issues.add(ValidationIssue.error("EDGE_SOURCE_MISSING",
                        "Edge menunjuk node sumber yang tidak ada: " + edge.from(), null));
                continue;
            }
            if (edge.to() == null || !ids.contains(edge.to())) {
                issues.add(ValidationIssue.error("EDGE_TARGET_MISSING",
                        "Edge menunjuk node tujuan yang tidak ada: " + edge.to(), edge.from()));
                continue;
            }
            if (edge.from().equals(edge.to())) {
                issues.add(ValidationIssue.error("EDGE_SELF_LOOP",
                        "Edge menghubungkan node ke dirinya sendiri: " + edge.from(), edge.from()));
            }
            String key = edge.from() + "->" + edge.to() + ":" + edge.portOrDefault();
            if (!uniqueEdges.add(key)) {
                issues.add(ValidationIssue.warning("EDGE_DUPLICATE",
                        "Edge duplikat: " + key, edge.from()));
            }
            if (edge.portOrDefault().equals(FlowEdge.PORT_OUT)
                    && incomingByPort(flow, edge.from()) > 1) {
                issues.add(ValidationIssue.warning("EDGE_AMBIGUOUS_OUT",
                        "Node " + edge.from() + " memiliki lebih dari satu edge port 'out'; "
                                + "edge pertama yang dipakai", edge.from()));
            }
        }

        // port wajib pada node bercabang
        for (FlowNode node : flow.nodes()) {
            if (node.type() == NodeType.CONDITION) {
                boolean hasTrue = flow.outgoing(node.id(), FlowEdge.PORT_TRUE).isPresent();
                boolean hasFalse = flow.outgoing(node.id(), FlowEdge.PORT_FALSE).isPresent();
                if (!hasTrue || !hasFalse) {
                    issues.add(ValidationIssue.warning("CONDITION_PORT_INCOMPLETE",
                            "Node CONDITION " + node.id()
                                    + " sebaiknya memiliki cabang 'true' dan 'false'", node.id()));
                }
            }
            if (node.type() == NodeType.TRY_CATCH
                    && flow.outgoing(node.id(), FlowEdge.PORT_ERROR).isEmpty()) {
                issues.add(ValidationIssue.warning("TRYCATCH_NO_CATCH",
                        "Node TRY_CATCH " + node.id() + " tidak memiliki port 'error'", node.id()));
            }
        }
    }

    private long incomingByPort(FlowDefinition flow, String nodeId) {
        return flow.outgoing(nodeId).stream()
                .filter(e -> e.portOrDefault().equals(FlowEdge.PORT_OUT))
                .count();
    }

    /** Deteksi siklus dengan DFS tiga warna. */
    private void detectCycles(FlowDefinition flow, List<ValidationIssue> issues) {
        Map<String, Integer> state = new LinkedHashMap<>();
        for (FlowNode node : flow.nodes()) {
            state.put(node.id(), 0);
        }
        for (FlowNode node : flow.nodes()) {
            if (state.get(node.id()) == 0) {
                List<String> path = new ArrayList<>();
                if (dfsCycle(flow, node.id(), state, path)) {
                    issues.add(ValidationIssue.error("CYCLE_DETECTED",
                            "Graph memiliki siklus: " + String.join(" -> ", path), node.id()));
                    return;
                }
            }
        }
    }

    private boolean dfsCycle(FlowDefinition flow, String nodeId, Map<String, Integer> state,
                             List<String> path) {
        state.put(nodeId, 1);
        path.add(nodeId);
        for (FlowEdge edge : flow.outgoing(nodeId)) {
            String next = edge.to();
            Integer nextState = state.get(next);
            if (nextState == null) {
                continue;
            }
            if (nextState == 1) {
                path.add(next);
                return true;
            }
            if (nextState == 0 && dfsCycle(flow, next, state, path)) {
                return true;
            }
        }
        path.remove(path.size() - 1);
        state.put(nodeId, 2);
        return false;
    }

    /** Pemeriksaan konfigurasi wajib per jenis node. */
    private void validateConfig(FlowDefinition flow, List<ValidationIssue> issues) {
        for (FlowNode node : flow.nodes()) {
            switch (node.type()) {
                case CONDITION, SWITCH -> {
                    String expr = node.cfgString("expression", "");
                    if (expr.isBlank()) {
                        issues.add(ValidationIssue.error("CONFIG_REQUIRED",
                                "Node " + node.type() + " " + node.id() + " memerlukan 'expression'",
                                node.id()));
                    }
                }
                case HTTP_CALL -> {
                    if (node.cfgString("url", "").isBlank()) {
                        issues.add(ValidationIssue.error("CONFIG_REQUIRED",
                                "Node HTTP_CALL " + node.id() + " memerlukan 'url'", node.id()));
                    }
                }
                case DATABASE_QUERY -> {
                    String sql = node.cfgString("sql", "").trim().toLowerCase(java.util.Locale.ROOT);
                    if (sql.isBlank()) {
                        issues.add(ValidationIssue.error("CONFIG_REQUIRED",
                                "Node DATABASE_QUERY " + node.id() + " memerlukan 'sql'", node.id()));
                    } else if (!sql.startsWith("=") && !sql.contains("${")
                            && SELECT_PREFIXES.stream().noneMatch(sql::startsWith)) {
                        issues.add(ValidationIssue.error("SQL_NOT_SELECT",
                                "Node DATABASE_QUERY " + node.id() + " hanya boleh SELECT", node.id()));
                    }
                }
                case TEMPLATE_CALL -> {
                    if (node.cfgString("templateKey", "").isBlank()) {
                        issues.add(ValidationIssue.error("CONFIG_REQUIRED",
                                "Node TEMPLATE_CALL " + node.id() + " memerlukan 'templateKey'",
                                node.id()));
                    }
                }
                case RESPONSE -> {
                    Integer status = node.cfgInt("status", 200);
                    if (status == null || status < 100 || status > 599) {
                        issues.add(ValidationIssue.error("RESPONSE_STATUS_INVALID",
                                "Status response node " + node.id() + " tidak valid: " + status,
                                node.id()));
                    }
                }
                case LOOP -> {
                    if (node.cfg("items") == null) {
                        issues.add(ValidationIssue.warning("CONFIG_RECOMMENDED",
                                "Node LOOP " + node.id() + " sebaiknya memiliki 'items'", node.id()));
                    }
                }
                default -> {
                    // jenis lain tidak punya konfigurasi wajib
                }
            }

            // fallback harus menunjuk node yang ada
            if (node.errorPolicy().onErrorOrDefault() == io.apistudio.contracts.flow.ErrorPolicy.OnError.FALLBACK) {
                String fallbackId = node.errorPolicy().fallbackNodeId();
                if (fallbackId == null || fallbackId.isBlank()) {
                    issues.add(ValidationIssue.error("FALLBACK_MISSING",
                            "Node " + node.id() + " memakai onError=FALLBACK tanpa fallbackNodeId",
                            node.id()));
                } else if (flow.node(fallbackId).isEmpty()) {
                    issues.add(ValidationIssue.error("FALLBACK_NOT_FOUND",
                            "Fallback node tidak ditemukan: " + fallbackId, node.id()));
                }
            }
        }
    }

    /** Pastikan setiap ekspresi dapat diparse. */
    private void validateExpressions(FlowDefinition flow, List<ValidationIssue> issues) {
        ExpressionEngine engine = new ExpressionEngine(name -> null, () -> "1970-01-01T00:00:00Z", null);
        for (FlowNode node : flow.nodes()) {
            validateValue(node.id(), node.config(), engine, issues);
        }
    }

    private void validateValue(String nodeId, Object value, ExpressionEngine engine,
                               List<ValidationIssue> issues) {
        if (value instanceof String text) {
            String trimmed = text.trim();
            try {
                if (trimmed.startsWith("=") && !trimmed.startsWith("==")) {
                    engine.eval(trimmed.substring(1));
                } else if (trimmed.contains("${")) {
                    // parse bagian template tanpa mengevaluasi nilainya
                    int index = 0;
                    while (true) {
                        int start = trimmed.indexOf("${", index);
                        if (start < 0) {
                            break;
                        }
                        int end = trimmed.indexOf('}', start);
                        if (end < 0) {
                            issues.add(ValidationIssue.error("EXPRESSION_UNCLOSED",
                                    "Template tidak tertutup pada node " + nodeId, nodeId));
                            break;
                        }
                        engine.eval(trimmed.substring(start + 2, end));
                        index = end + 1;
                    }
                }
            } catch (ExpressionException e) {
                issues.add(ValidationIssue.error("EXPRESSION_INVALID",
                        "Ekspresi tidak valid pada node " + nodeId + ": " + e.getMessage(), nodeId));
            }
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> validateValue(nodeId, v, engine, issues));
        } else if (value instanceof List<?> list) {
            list.forEach(v -> validateValue(nodeId, v, engine, issues));
        }
    }

    /** Peringatkan node yang tidak dapat dijangkau dari TRIGGER. */
    private void validateReachability(FlowDefinition flow, List<ValidationIssue> issues) {
        FlowNode trigger = flow.trigger().orElse(null);
        if (trigger == null) {
            return;
        }
        Set<String> reachable = new LinkedHashSet<>();
        collectReachable(flow, trigger.id(), reachable);
        for (FlowNode node : flow.nodes()) {
            if (!reachable.contains(node.id())) {
                issues.add(ValidationIssue.warning("NODE_UNREACHABLE",
                        "Node " + node.id() + " tidak dapat dijangkau dari TRIGGER", node.id()));
            }
        }
    }

    private void collectReachable(FlowDefinition flow, String nodeId, Set<String> visited) {
        if (!visited.add(nodeId)) {
            return;
        }
        for (FlowEdge edge : flow.outgoing(nodeId)) {
            collectReachable(flow, edge.to(), visited);
        }
    }
}
