package io.apistudio.engine.validation;

import io.apistudio.contracts.flow.ErrorPolicy;
import io.apistudio.contracts.flow.FlowDefinition;
import io.apistudio.contracts.flow.FlowEdge;
import io.apistudio.contracts.flow.FlowNode;
import io.apistudio.contracts.flow.NodeType;
import io.apistudio.contracts.flow.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pengujian validasi graph flow (BRD FR-FLOW-04). */
class FlowValidatorTest {

    private final FlowValidator validator = new FlowValidator();

    private static FlowNode node(String id, NodeType type, Map<String, Object> config) {
        return new FlowNode(id, type, id, config, new Position(0, 0), ErrorPolicy.defaults(), false);
    }

    private static FlowDefinition definition(List<FlowNode> nodes, List<FlowEdge> edges) {
        return new FlowDefinition(null, "uji", nodes, edges, 1);
    }

    @Test
    @DisplayName("flow valid tanpa isu error")
    void validFlow() {
        FlowDefinition flow = definition(
                List.of(node("trigger", NodeType.TRIGGER, Map.of()),
                        node("resp", NodeType.RESPONSE, Map.of("status", 200))),
                List.of(FlowEdge.of("trigger", "resp")));

        FlowValidator.Report report = validator.validate(flow);
        assertTrue(report.valid(), () -> "tidak boleh ada error: " + report.toMap());
        assertEquals(0, report.errorCount());
    }

    @Test
    @DisplayName("flow kosong ditolak")
    void emptyFlow() {
        FlowValidator.Report report = validator.validate(FlowDefinition.empty("kosong"));
        assertFalse(report.valid());
        assertTrue(report.issues().stream().anyMatch(i -> i.code().equals("FLOW_EMPTY")));
    }

    @Test
    @DisplayName("TRIGGER wajib ada")
    void triggerRequired() {
        FlowDefinition flow = definition(
                List.of(node("resp", NodeType.RESPONSE, Map.of())), List.of());
        FlowValidator.Report report = validator.validate(flow);
        assertTrue(report.issues().stream().anyMatch(i -> i.code().equals("TRIGGER_MISSING")));
    }

    @Test
    @DisplayName("TRIGGER tidak boleh lebih dari satu")
    void triggerSingle() {
        FlowDefinition flow = definition(
                List.of(node("t1", NodeType.TRIGGER, Map.of()),
                        node("t2", NodeType.TRIGGER, Map.of()),
                        node("resp", NodeType.RESPONSE, Map.of())),
                List.of(FlowEdge.of("t1", "resp")));
        assertTrue(validator.validate(flow).issues().stream()
                .anyMatch(i -> i.code().equals("TRIGGER_MULTIPLE")));
    }

    @Test
    @DisplayName("RESPONSE yang hilang hanya peringatan")
    void responseMissingIsWarning() {
        FlowDefinition flow = definition(
                List.of(node("trigger", NodeType.TRIGGER, Map.of())), List.of());
        FlowValidator.Report report = validator.validate(flow);
        assertTrue(report.valid());
        assertTrue(report.issues().stream().anyMatch(i -> i.code().equals("RESPONSE_MISSING")));
    }

    @Test
    @DisplayName("edge ke node tidak ada ditolak")
    void danglingEdge() {
        FlowDefinition flow = definition(
                List.of(node("trigger", NodeType.TRIGGER, Map.of())),
                List.of(FlowEdge.of("trigger", "hantu")));
        assertTrue(validator.validate(flow).issues().stream()
                .anyMatch(i -> i.code().equals("EDGE_TARGET_MISSING")));
    }

    @Test
    @DisplayName("edge ke diri sendiri ditolak")
    void selfLoop() {
        FlowDefinition flow = definition(
                List.of(node("trigger", NodeType.TRIGGER, Map.of()),
                        node("a", NodeType.LOG, Map.of())),
                List.of(FlowEdge.of("trigger", "a"), FlowEdge.of("a", "a")));
        assertTrue(validator.validate(flow).issues().stream()
                .anyMatch(i -> i.code().equals("EDGE_SELF_LOOP")));
    }

    @Test
    @DisplayName("siklus terdeteksi")
    void cycleDetected() {
        FlowDefinition flow = definition(
                List.of(node("trigger", NodeType.TRIGGER, Map.of()),
                        node("a", NodeType.LOG, Map.of()),
                        node("b", NodeType.LOG, Map.of())),
                List.of(FlowEdge.of("trigger", "a"), FlowEdge.of("a", "b"), FlowEdge.of("b", "a")));

        FlowValidator.Report report = validator.validate(flow);
        assertFalse(report.valid());
        assertTrue(report.issues().stream().anyMatch(i -> i.code().equals("CYCLE_DETECTED")));
    }

    @Test
    @DisplayName("CONDITION wajib punya ekspresi")
    void conditionRequiresExpression() {
        FlowDefinition flow = definition(
                List.of(node("trigger", NodeType.TRIGGER, Map.of()),
                        node("cond", NodeType.CONDITION, Map.of()),
                        node("resp", NodeType.RESPONSE, Map.of())),
                List.of(FlowEdge.of("trigger", "cond"), FlowEdge.of("cond", "resp", "true")));
        assertTrue(validator.validate(flow).issues().stream()
                .anyMatch(i -> i.code().equals("CONFIG_REQUIRED") && "cond".equals(i.nodeId())));
    }

    @Test
    @DisplayName("HTTP_CALL wajib punya url")
    void httpRequiresUrl() {
        FlowDefinition flow = definition(
                List.of(node("trigger", NodeType.TRIGGER, Map.of()),
                        node("http", NodeType.HTTP_CALL, Map.of())),
                List.of(FlowEdge.of("trigger", "http")));
        assertTrue(validator.validate(flow).issues().stream()
                .anyMatch(i -> i.code().equals("CONFIG_REQUIRED") && "http".equals(i.nodeId())));
    }

    @Test
    @DisplayName("DATABASE_QUERY menolak SQL non-SELECT")
    void databaseRejectsNonSelect() {
        FlowDefinition flow = definition(
                List.of(node("trigger", NodeType.TRIGGER, Map.of()),
                        node("db", NodeType.DATABASE_QUERY, Map.of("sql", "delete from account"))),
                List.of(FlowEdge.of("trigger", "db")));

        FlowValidator.Report report = validator.validate(flow);
        assertFalse(report.valid());
        assertTrue(report.issues().stream().anyMatch(i -> i.code().equals("SQL_NOT_SELECT")));
    }

    @Test
    @DisplayName("DATABASE_QUERY menerima SELECT")
    void databaseAcceptsSelect() {
        FlowDefinition flow = definition(
                List.of(node("trigger", NodeType.TRIGGER, Map.of()),
                        node("db", NodeType.DATABASE_QUERY, Map.of("sql", "select * from account"))),
                List.of(FlowEdge.of("trigger", "db")));
        assertTrue(validator.validate(flow).valid());
    }

    @Test
    @DisplayName("ekspresi tidak valid terdeteksi")
    void invalidExpression() {
        FlowDefinition flow = definition(
                List.of(node("trigger", NodeType.TRIGGER, Map.of()),
                        node("cond", NodeType.CONDITION, Map.of("expression", "=1 +"))),
                List.of(FlowEdge.of("trigger", "cond")));

        FlowValidator.Report report = validator.validate(flow);
        assertFalse(report.valid());
        assertTrue(report.issues().stream().anyMatch(i -> i.code().equals("EXPRESSION_INVALID")));
    }

    @Test
    @DisplayName("template string tidak tertutup terdeteksi")
    void unclosedTemplate() {
        FlowDefinition flow = definition(
                List.of(node("trigger", NodeType.TRIGGER, Map.of()),
                        node("log", NodeType.LOG, Map.of("message", "halo ${request.body.id"))),
                List.of(FlowEdge.of("trigger", "log")));

        assertTrue(validator.validate(flow).issues().stream()
                .anyMatch(i -> i.code().equals("EXPRESSION_UNCLOSED")));
    }

    @Test
    @DisplayName("FALLBACK tanpa fallbackNodeId ditolak")
    void fallbackRequiresTarget() {
        FlowNode broken = new FlowNode("gagal", NodeType.FAIL, "gagal", Map.of(),
                new Position(0, 0),
                new ErrorPolicy(null, 0, 0L, ErrorPolicy.OnError.FALLBACK, null), false);

        FlowDefinition flow = definition(
                List.of(node("trigger", NodeType.TRIGGER, Map.of()), broken),
                List.of(FlowEdge.of("trigger", "gagal")));

        assertTrue(validator.validate(flow).issues().stream()
                .anyMatch(i -> i.code().equals("FALLBACK_MISSING")));
    }

    @Test
    @DisplayName("FALLBACK ke node tidak ada ditolak")
    void fallbackTargetMustExist() {
        FlowNode broken = new FlowNode("gagal", NodeType.FAIL, "gagal", Map.of(),
                new Position(0, 0),
                new ErrorPolicy(null, 0, 0L, ErrorPolicy.OnError.FALLBACK, "tidak-ada"), false);

        FlowDefinition flow = definition(
                List.of(node("trigger", NodeType.TRIGGER, Map.of()), broken),
                List.of(FlowEdge.of("trigger", "gagal")));

        assertTrue(validator.validate(flow).issues().stream()
                .anyMatch(i -> i.code().equals("FALLBACK_NOT_FOUND")));
    }

    @Test
    @DisplayName("status response tidak valid ditolak")
    void invalidResponseStatus() {
        FlowDefinition flow = definition(
                List.of(node("trigger", NodeType.TRIGGER, Map.of()),
                        node("resp", NodeType.RESPONSE, Map.of("status", 9999))),
                List.of(FlowEdge.of("trigger", "resp")));

        assertTrue(validator.validate(flow).issues().stream()
                .anyMatch(i -> i.code().equals("RESPONSE_STATUS_INVALID")));
    }

    @Test
    @DisplayName("node tak terjangkau diberi peringatan")
    void unreachableNode() {
        FlowDefinition flow = definition(
                List.of(node("trigger", NodeType.TRIGGER, Map.of()),
                        node("resp", NodeType.RESPONSE, Map.of()),
                        node("yatim", NodeType.LOG, Map.of("message", "x"))),
                List.of(FlowEdge.of("trigger", "resp")));

        FlowValidator.Report report = validator.validate(flow);
        assertTrue(report.valid());
        assertTrue(report.issues().stream().anyMatch(i -> i.code().equals("NODE_UNREACHABLE")));
    }

    @Test
    @DisplayName("id node duplikat ditolak")
    void duplicateNodeId() {
        FlowDefinition flow = definition(
                List.of(node("trigger", NodeType.TRIGGER, Map.of()),
                        node("sama", NodeType.LOG, Map.of()),
                        node("sama", NodeType.LOG, Map.of())),
                List.of(FlowEdge.of("trigger", "sama")));

        assertTrue(validator.validate(flow).issues().stream()
                .anyMatch(i -> i.code().equals("NODE_ID_DUPLICATE")));
    }
}
