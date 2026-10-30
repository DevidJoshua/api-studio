package io.apistudio.cp.service;

import io.apistudio.contracts.exec.ExecutionResult;
import io.apistudio.contracts.exec.TraceStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pengujian assertion terhadap response, database, dan trace (BRD FR-SIM-06/07/08). */
class AssertionEngineTest {

    private final AssertionEngine engine = new AssertionEngine();

    private static ExecutionResult result(int status, Object body) {
        return new ExecutionResult(status, "OK",
                Map.of("Content-Type", "application/json", "X-Custom", "nilai"),
                body, "trace-1",
                List.of(
                        TraceStep.ok("trigger", "TRIGGER", "Trigger", "out", null, null, 1, List.of()),
                        TraceStep.ok("resp", "RESPONSE", "Response", null, null, null, 2, List.of())),
                10, List.of(), List.of("account"));
    }

    private static AssertionEngine.AssertionSpec spec(String type, String target,
                                                     Object expected, String operator) {
        return new AssertionEngine.AssertionSpec(type, target, expected, operator, null);
    }

    private AssertionEngine.Summary run(List<AssertionEngine.AssertionSpec> specs, ExecutionResult result) {
        return engine.run(specs, result, (sql, params) -> List.of(Map.of("jumlah", 2)), Map.of());
    }

    @Test
    @DisplayName("assertion status response")
    void statusAssertion() {
        var summary = run(List.of(spec("response.status", null, 200, "equals")), result(200, Map.of()));
        assertEquals(1, summary.passed());
        assertEquals(0, summary.failed());
    }

    @Test
    @DisplayName("assertion status yang tidak cocok dilaporkan gagal dengan pesan")
    void statusMismatch() {
        var summary = run(List.of(spec("response.status", null, 201, "equals")), result(200, Map.of()));
        assertEquals(1, summary.failed());
        assertTrue(summary.results().get(0).message().contains("200"));
    }

    @Test
    @DisplayName("assertion header tidak peka huruf besar/kecil")
    void headerAssertion() {
        var summary = run(List.of(spec("response.header", "x-custom", "nilai", "equals")),
                result(200, Map.of()));
        assertEquals(1, summary.passed());
    }

    @Test
    @DisplayName("assertion JSONPath pada body bersarang")
    void jsonPathAssertion() {
        Map<String, Object> body = Map.of("data", Map.of("amount", Map.of("value", "15000.00")),
                "responseCode", "2007300");
        var summary = run(List.of(
                spec("response.jsonpath", "responseCode", "2007300", "equals"),
                spec("response.jsonpath", "data.amount.value", "15000.00", "equals")),
                result(200, body));
        assertEquals(2, summary.passed());
    }

    @Test
    @DisplayName("assertion JSONPath tidak ada menghasilkan null")
    void jsonPathMissing() {
        var summary = run(List.of(spec("response.jsonpath", "tidak.ada", null, "notExists")),
                result(200, Map.of()));
        assertEquals(1, summary.passed());
    }

    @Test
    @DisplayName("operator numerik bekerja pada string numerik")
    void numericOperators() {
        Map<String, Object> body = Map.of("amount", "15000.00");
        var summary = run(List.of(
                spec("response.jsonpath", "amount", 10000, "gt"),
                spec("response.jsonpath", "amount", 15000, "gte"),
                spec("response.jsonpath", "amount", 20000, "lt")),
                result(200, body));
        assertEquals(3, summary.passed());
    }

    @Test
    @DisplayName("operator contains untuk list dan string")
    void containsOperator() {
        Map<String, Object> body = Map.of("tags", List.of("a", "b"));
        var summary = run(List.of(
                spec("response.jsonpath", "tags", "b", "contains"),
                spec("response.body", null, "tags", "contains")),
                result(200, body));
        assertEquals(2, summary.passed());
    }

    @Test
    @DisplayName("operator matches memakai regex")
    void matchesOperator() {
        Map<String, Object> body = Map.of("ref", "REF-0001");
        var summary = run(List.of(spec("response.jsonpath", "ref", "REF-\\d{4}", "matches")),
                result(200, body));
        assertEquals(1, summary.passed());
    }

    @Test
    @DisplayName("assertion schema memeriksa field wajib")
    void schemaAssertion() {
        Map<String, Object> body = Map.of("id", "A1", "detail", Map.of("status", "OK"));
        var ok = run(List.of(spec("response.schema", "id,detail.status", null, "equals")),
                result(200, body));
        assertEquals(1, ok.passed());

        var missing = run(List.of(spec("response.schema", "id,detail.tidakAda", null, "equals")),
                result(200, body));
        assertEquals(1, missing.failed());
        assertTrue(String.valueOf(missing.results().get(0).actual()).contains("tidakAda"));
    }

    @Test
    @DisplayName("assertion DB memakai eksekutor query")
    void databaseAssertion() {
        AssertionEngine.DbQueryExecutor executor = (sql, params) ->
                List.of(Map.of("jumlah", 2), Map.of("jumlah", 3));

        var value = engine.run(List.of(new AssertionEngine.AssertionSpec("db.value", "jumlah",
                        2, "equals", "select jumlah from t")),
                result(200, Map.of()), executor, Map.of());
        assertEquals(1, value.passed());

        var rowCount = engine.run(List.of(new AssertionEngine.AssertionSpec("db.rowcount", null,
                        2, "equals", "select * from t")),
                result(200, Map.of()), executor, Map.of());
        assertEquals(1, rowCount.passed());
    }

    @Test
    @DisplayName("assertion DB tanpa eksekutor gagal dengan pesan jelas")
    void databaseWithoutExecutor() {
        var summary = engine.run(List.of(new AssertionEngine.AssertionSpec("db.value", "x", 1,
                "equals", "select 1")), result(200, Map.of()), null, Map.of());
        assertEquals(1, summary.failed());
        assertTrue(summary.results().get(0).message().toLowerCase().contains("db"));
    }

    @Test
    @DisplayName("assertion trace nodeExecuted dan nodeSkipped")
    void traceAssertions() {
        var executed = run(List.of(spec("trace.nodeExecuted", "trigger", null, null)),
                result(200, Map.of()));
        assertEquals(1, executed.passed());

        var skipped = run(List.of(spec("trace.nodeSkipped", "node_java", null, null)),
                result(200, Map.of()));
        assertEquals(1, skipped.passed());

        var notSkipped = run(List.of(spec("trace.nodeSkipped", "trigger", null, null)),
                result(200, Map.of()));
        assertEquals(1, notSkipped.failed());
    }

    @Test
    @DisplayName("assertion durasi dan jumlah trace")
    void durationAndCountAssertions() {
        var duration = run(List.of(spec("trace.durationUnder", null, 100, null)),
                result(200, Map.of()));
        assertEquals(1, duration.passed());

        var count = run(List.of(spec("trace.count", "RESPONSE", 1, "equals")),
                result(200, Map.of()));
        assertEquals(1, count.passed());
    }

    @Test
    @DisplayName("jenis assertion tidak dikenal dilaporkan gagal")
    void unknownType() {
        var summary = run(List.of(spec("response.tidakAda", null, 1, "equals")),
                result(200, Map.of()));
        assertEquals(1, summary.failed());
        assertTrue(summary.results().get(0).message().contains("tidak dikenal"));
    }

    @Test
    @DisplayName("tanpa assertion dianggap lulus")
    void emptyAssertions() {
        var summary = run(List.of(), result(200, Map.of()));
        assertEquals(0, summary.failed());
        assertTrue(summary.allPassed());
    }

    @Test
    @DisplayName("operator notEquals dan exists")
    void notEqualsAndExists() {
        Map<String, Object> body = Map.of("status", "OK");
        var summary = run(List.of(
                spec("response.jsonpath", "status", "FAIL", "notEquals"),
                spec("response.jsonpath", "status", null, "exists"),
                spec("response.jsonpath", "tidakAda", null, "notExists")),
                result(200, body));
        assertEquals(3, summary.passed());
    }

    @Test
    @DisplayName("perbandingan boolean toleran terhadap string")
    void booleanComparison() {
        var summary = run(List.of(spec("response.jsonpath", "ok", true, "equals")),
                result(200, Map.of("ok", "true")));
        assertEquals(1, summary.passed());
    }

    @Test
    @DisplayName("daftar jenis assertion tersedia untuk UI")
    void supportedTypes() {
        assertFalse(AssertionEngine.supportedTypes().isEmpty());
        assertTrue(AssertionEngine.supportedTypes().stream()
                .anyMatch(m -> "response.status".equals(m.get("type"))));
    }
}
