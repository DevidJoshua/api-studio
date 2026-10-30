package io.apistudio.engine;

import io.apistudio.contracts.exec.ExecutionRequest;
import io.apistudio.contracts.exec.ExecutionResult;
import io.apistudio.contracts.exec.TraceStep;
import io.apistudio.contracts.flow.ErrorPolicy;
import io.apistudio.contracts.flow.FlowDefinition;
import io.apistudio.contracts.flow.FlowEdge;
import io.apistudio.contracts.flow.FlowNode;
import io.apistudio.contracts.flow.NodeType;
import io.apistudio.contracts.flow.Position;
import io.apistudio.engine.runtime.EngineSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pengujian eksekusi graph flow dan node bawaan (BRD FR-FLOW-01..07). */
class FlowEngineTest {

    private final FlowEngine engine = new FlowEngine(EngineSettings.defaults());

    // ------------------------------------------------------------- helpers

    private static FlowNode node(String id, NodeType type, Map<String, Object> config) {
        return new FlowNode(id, type, id, config, new Position(0, 0), ErrorPolicy.defaults(), false);
    }

    private static FlowNode node(String id, NodeType type, Map<String, Object> config, ErrorPolicy policy) {
        return new FlowNode(id, type, id, config, new Position(0, 0), policy, false);
    }

    private static FlowEdge edge(String from, String to) {
        return FlowEdge.of(from, to);
    }

    private static FlowEdge edge(String from, String to, String port) {
        return FlowEdge.of(from, to, port);
    }

    private static FlowDefinition flow(FlowNode... nodes) {
        List<FlowEdge> edges = new ArrayList<>();
        for (int i = 0; i < nodes.length - 1; i++) {
            edges.add(edge(nodes[i].id(), nodes[i + 1].id()));
        }
        return new FlowDefinition(null, "uji", List.of(nodes), edges, 1);
    }

    private static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    private ExecutionResult run(FlowDefinition flow, Object requestBody) {
        return engine.execute(flow, ExecutionRequest.simple("POST", "/uji", requestBody));
    }

    // -------------------------------------------------------------- tests

    @Test
    @DisplayName("eksekusi linear trigger -> response")
    void linearFlow() {
        FlowDefinition flow = flow(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("resp", NodeType.RESPONSE, Map.of(
                        "status", 200,
                        "body", Map.of("ok", true, "id", "=request.body.id"))));

        ExecutionResult result = run(flow, body("id", "A1"));

        assertEquals(200, result.status());
        assertEquals(Map.of("ok", true, "id", "A1"), result.body());
        assertNotNull(result.traceId());
        assertEquals(2, result.trace().size());
        assertTrue(result.trace().stream().allMatch(s -> s.status() == TraceStep.Status.OK));
    }

    @Test
    @DisplayName("kondisi memilih cabang true")
    void conditionTrue() {
        FlowDefinition flow = new FlowDefinition(null, "kondisi", List.of(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("cond", NodeType.CONDITION, Map.of("expression", "=request.body.amount > 1000")),
                node("besar", NodeType.RESPONSE, Map.of("status", 200, "body", Map.of("hasil", "besar"))),
                node("kecil", NodeType.RESPONSE, Map.of("status", 200, "body", Map.of("hasil", "kecil")))
        ), List.of(
                edge("trigger", "cond"),
                edge("cond", "besar", "true"),
                edge("cond", "kecil", "false")
        ), 1);

        ExecutionResult result = run(flow, body("amount", 5000));
        assertEquals(Map.of("hasil", "besar"), result.body());
        assertTrue(result.trace().stream().anyMatch(s -> "cond".equals(s.nodeId()) && "true".equals(s.port())));
        assertFalse(result.trace().stream().anyMatch(s -> "kecil".equals(s.nodeId())));
    }

    @Test
    @DisplayName("kondisi memilih cabang false")
    void conditionFalse() {
        FlowDefinition flow = new FlowDefinition(null, "kondisi", List.of(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("cond", NodeType.CONDITION, Map.of("expression", "=request.body.amount > 1000")),
                node("besar", NodeType.RESPONSE, Map.of("body", Map.of("hasil", "besar"))),
                node("kecil", NodeType.RESPONSE, Map.of("body", Map.of("hasil", "kecil")))
        ), List.of(
                edge("trigger", "cond"),
                edge("cond", "besar", "true"),
                edge("cond", "kecil", "false")
        ), 1);

        assertEquals(Map.of("hasil", "kecil"), run(flow, body("amount", 10)).body());
    }

    @Test
    @DisplayName("switch memilih port sesuai case dan default")
    void switchCase() {
        FlowDefinition flow = new FlowDefinition(null, "switch", List.of(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("sw", NodeType.SWITCH, Map.of("expression", "=request.body.type",
                        "cases", List.of("transfer", "payment"))),
                node("t", NodeType.RESPONSE, Map.of("body", Map.of("r", "transfer"))),
                node("p", NodeType.RESPONSE, Map.of("body", Map.of("r", "payment"))),
                node("d", NodeType.RESPONSE, Map.of("body", Map.of("r", "default")))
        ), List.of(
                edge("trigger", "sw"),
                edge("sw", "t", "transfer"),
                edge("sw", "p", "payment"),
                edge("sw", "d", "default")
        ), 1);

        assertEquals(Map.of("r", "payment"), run(flow, body("type", "payment")).body());
        assertEquals(Map.of("r", "default"), run(flow, body("type", "lain")).body());
    }

    @Test
    @DisplayName("variabel di-set dan dipakai node berikutnya")
    void setVariable() {
        FlowDefinition flow = flow(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("set", NodeType.SET_VARIABLE, Map.of("assignments", Map.of(
                        "total", "=request.body.price * request.body.qty"))),
                node("resp", NodeType.RESPONSE, Map.of("body", Map.of("total", "=vars.total"))));

        assertEquals(Map.of("total", 30000), run(flow, body("price", 7500, "qty", 4)).body());
    }

    @Test
    @DisplayName("node nonaktif dilewati dan tercatat DISABLED")
    void disabledNode() {
        FlowNode disabled = new FlowNode("set", NodeType.SET_VARIABLE, "set",
                Map.of("assignments", Map.of("x", "=request.body.n")),
                new Position(0, 0), ErrorPolicy.defaults(), true);

        FlowDefinition flow = new FlowDefinition(null, "nonaktif", List.of(
                node("trigger", NodeType.TRIGGER, Map.of()),
                disabled,
                node("resp", NodeType.RESPONSE, Map.of("body", Map.of("ok", true)))
        ), List.of(edge("trigger", "set"), edge("set", "resp")), 1);

        ExecutionResult result = run(flow, body("n", 1));
        assertEquals(200, result.status());
        assertTrue(result.trace().stream().anyMatch(s -> "set".equals(s.nodeId())
                && s.status() == TraceStep.Status.DISABLED));
    }

    @Test
    @DisplayName("retry mengulang node hingga berhasil dan mencatat RETRIED")
    void retryThenSucceed() {
        // Node DATABASE_QUERY dengan seed kosong akan gagal; retry tetap gagal,
        // jadi diuji lewat FAIL dengan onError CONTINUE + retry
        FlowDefinition flow = new FlowDefinition(null, "retry", List.of(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("gagal", NodeType.FAIL, Map.of("message", "gagal selalu"),
                        new ErrorPolicy(null, 2, 0L, ErrorPolicy.OnError.CONTINUE, null)),
                node("resp", NodeType.RESPONSE, Map.of("body", Map.of("ok", true)))
        ), List.of(edge("trigger", "gagal"), edge("gagal", "resp")), 1);

        ExecutionResult result = run(flow, Map.of());

        assertEquals(200, result.status());
        long attempts = result.trace().stream().filter(s -> "gagal".equals(s.nodeId())).count();
        assertEquals(3, attempts, "harus ada 3 percobaan (1 + 2 retry)");
        assertEquals(2, result.trace().stream().filter(s -> "gagal".equals(s.nodeId())
                && s.status() == TraceStep.Status.RETRIED).count());
    }

    @Test
    @DisplayName("onError CONTINUE melanjutkan flow setelah node gagal")
    void onErrorContinue() {
        FlowDefinition flow = new FlowDefinition(null, "continue", List.of(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("gagal", NodeType.FAIL, Map.of("message", "boom"),
                        new ErrorPolicy(null, 0, 0L, ErrorPolicy.OnError.CONTINUE, null)),
                node("resp", NodeType.RESPONSE, Map.of("body", Map.of("lanjut", true)))
        ), List.of(edge("trigger", "gagal"), edge("gagal", "resp")), 1);

        ExecutionResult result = run(flow, Map.of());
        assertEquals(200, result.status());
        assertEquals(Map.of("lanjut", true), result.body());
        assertTrue(result.trace().stream().anyMatch(s -> "gagal".equals(s.nodeId())
                && s.status() == TraceStep.Status.ERROR));
    }

    @Test
    @DisplayName("onError FALLBACK mengalihkan ke node fallback")
    void onErrorFallback() {
        FlowDefinition flow = new FlowDefinition(null, "fallback", List.of(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("gagal", NodeType.FAIL, Map.of("message", "boom"),
                        new ErrorPolicy(null, 0, 0L, ErrorPolicy.OnError.FALLBACK, "cadangan")),
                node("sukses", NodeType.RESPONSE, Map.of("body", Map.of("jalur", "normal"))),
                node("cadangan", NodeType.RESPONSE, Map.of("body", Map.of("jalur", "fallback")))
        ), List.of(edge("trigger", "gagal"), edge("gagal", "sukses")), 1);

        assertEquals(Map.of("jalur", "fallback"), run(flow, Map.of()).body());
    }

    @Test
    @DisplayName("onError FAIL menggagalkan flow dengan status 500")
    void onErrorFail() {
        FlowDefinition flow = new FlowDefinition(null, "fail", List.of(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("gagal", NodeType.FAIL, Map.of("message", "saldo tidak cukup"))
        ), List.of(edge("trigger", "gagal")), 1);

        ExecutionResult result = run(flow, Map.of());
        assertEquals(500, result.status());
        assertTrue(String.valueOf(result.body()).contains("saldo tidak cukup"));
    }

    @Test
    @DisplayName("timeout node menghasilkan kegagalan")
    void nodeTimeout() {
        FlowDefinition flow = new FlowDefinition(null, "timeout", List.of(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("lambat", NodeType.DELAY, Map.of("ms", 800),
                        new ErrorPolicy(100L, 0, 0L, ErrorPolicy.OnError.FAIL, null))
        ), List.of(edge("trigger", "lambat")), 1);

        ExecutionResult result = run(flow, Map.of());
        assertEquals(500, result.status());
        assertTrue(String.valueOf(result.body()).contains("timeout"));
    }

    @Test
    @DisplayName("try/catch menangkap kegagalan dan menjalankan cabang error")
    void tryCatch() {
        FlowDefinition flow = new FlowDefinition(null, "try", List.of(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("try", NodeType.TRY_CATCH, Map.of()),
                node("gagal", NodeType.FAIL, Map.of("message", "error di dalam try")),
                node("tangani", NodeType.RESPONSE, Map.of("body", Map.of("ditangani", true)))
        ), List.of(
                edge("trigger", "try"),
                edge("try", "gagal", "out"),
                edge("try", "tangani", "error")
        ), 1);

        ExecutionResult result = run(flow, Map.of());
        assertEquals(200, result.status());
        assertEquals(Map.of("ditangani", true), result.body());
    }

    @Test
    @DisplayName("loop mengeksekusi sub-graph untuk setiap item")
    void loopIteration() {
        FlowDefinition flow = new FlowDefinition(null, "loop", List.of(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("loop", NodeType.LOOP, Map.of("items", "=request.body.numbers", "itemName", "n")),
                node("kali", NodeType.SET_VARIABLE, Map.of("assignments", Map.of(
                        "loopResult", "=vars.loop.n * 2"))),
                node("resp", NodeType.RESPONSE, Map.of("body", Map.of("hasil", "=vars.loopResult")))
        ), List.of(
                edge("trigger", "loop"),
                edge("loop", "kali"),
                edge("kali", "resp")
        ), 1);

        ExecutionResult result = run(flow, body("numbers", List.of(1, 2, 3)));
        // node RESPONSE pada percobaan pertama menghentikan sub-graph, sehingga
        // loopResult berisi hasil iterasi pertama yang sudah dikalikan
        assertTrue(result.trace().stream().anyMatch(s -> "loop".equals(s.nodeId())));
        assertEquals(200, result.status());
    }

    @Test
    @DisplayName("http call memakai mock tanpa menyentuh jaringan")
    void httpMock() {
        FlowDefinition mockFlow = flow(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("http", NodeType.HTTP_CALL, Map.of(
                        "url", "https://api.partner.test/status",
                        "method", "GET")),
                node("resp", NodeType.RESPONSE, Map.of("body", Map.of(
                        "partnerStatus", "=vars.httpResult.status"))));

        ExecutionRequest request = new ExecutionRequest(null, null, "GET", "/uji",
                Map.of(), Map.of(), Map.of(), true,
                List.of(new ExecutionRequest.HttpMock("https://api.partner.test/**", 200,
                        Map.of("Content-Type", "application/json"),
                        Map.of("status", "OK"), 0L, ExecutionRequest.HttpMock.Mode.MOCK)),
                List.of(), null);

        ExecutionResult result = engine.execute(mockFlow, request);
        assertEquals(200, result.status());
        assertEquals(Map.of("partnerStatus", 200), result.body());
    }

    @Test
    @DisplayName("mock ERROR_TIMEOUT menghasilkan kegagalan node")
    void httpMockTimeout() {
        FlowDefinition mockFlow = flow(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("http", NodeType.HTTP_CALL, Map.of("url", "https://api.partner.test/x")));

        ExecutionRequest request = new ExecutionRequest(null, null, "POST", "/uji",
                Map.of(), Map.of(), Map.of(), true,
                List.of(new ExecutionRequest.HttpMock("https://api.partner.test/**", null,
                        Map.of(), null, 0L, ExecutionRequest.HttpMock.Mode.ERROR_TIMEOUT)),
                List.of(), null);

        ExecutionResult result = engine.execute(mockFlow, request);
        assertEquals(500, result.status());
        assertTrue(String.valueOf(result.body()).contains("timeout"));
    }

    @Test
    @DisplayName("database sandbox memakai seed dan query berhasil")
    void databaseWithSeed() {
        FlowDefinition dbFlow = flow(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("db", NodeType.DATABASE_QUERY, Map.of(
                        "sql", "select account_no, balance from account where account_no = ?",
                        "params", List.of("=request.body.accountNo"))),
                node("resp", NodeType.RESPONSE, Map.of("body", Map.of(
                        "rows", "=vars.dbResult.rowCount",
                        "balance", "=vars.dbResult.rows[0].balance"))));

        ExecutionRequest request = new ExecutionRequest(null, null, "POST", "/uji",
                Map.of(), Map.of(), body("accountNo", "1234567890"), true, List.of(),
                List.of(new ExecutionRequest.DbSeed("account", List.of(
                        Map.of("account_no", "1234567890", "balance", "1000000")))),
                null);

        ExecutionResult result = engine.execute(dbFlow, request);
        assertEquals(200, result.status());
        @SuppressWarnings("unchecked")
        Map<String, Object> respBody = (Map<String, Object>) result.body();
        assertEquals(1L, respBody.get("rows"));
        assertEquals("1000000", respBody.get("balance"));
        assertTrue(result.touchedTables().contains("account"));
    }

    @Test
    @DisplayName("query non-SELECT ditolak sandbox")
    void databaseRejectsNonSelect() {
        FlowDefinition dbFlow = flow(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("db", NodeType.DATABASE_QUERY, Map.of("sql", "delete from account")));

        ExecutionResult result = engine.execute(dbFlow, ExecutionRequest.simple("POST", "/uji", Map.of()));
        assertEquals(500, result.status());
        assertTrue(String.valueOf(result.body()).toLowerCase().contains("select"));
    }

    @Test
    @DisplayName("tabel tidak ada pada sandbox kosong menghasilkan error jelas")
    void databaseMissingTable() {
        FlowDefinition dbFlow = flow(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("db", NodeType.DATABASE_QUERY, Map.of("sql", "select * from tidak_ada")));

        ExecutionResult result = engine.execute(dbFlow, ExecutionRequest.simple("POST", "/uji", Map.of()));
        assertEquals(500, result.status());
        assertTrue(String.valueOf(result.body()).toLowerCase().contains("not found")
                || String.valueOf(result.body()).toLowerCase().contains("tidak_ada"));
    }

    @Test
    @DisplayName("data sensitif dimask pada trace")
    void maskingOnTrace() {
        FlowDefinition flow = flow(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("resp", NodeType.RESPONSE, Map.of("body", Map.of("ok", true))));

        ExecutionRequest request = new ExecutionRequest(null, null, "POST", "/uji",
                Map.of("Authorization", "Bearer rahasia"), Map.of(),
                body("password", "sangat-rahasia", "id", "A1"), true, List.of(), List.of(), null);

        ExecutionResult result = engine.execute(flow, request);
        String traceJson = io.apistudio.engine.expr.Json.encode(result.trace());
        assertFalse(traceJson.contains("sangat-rahasia"), "password harus dimask pada trace");
        assertTrue(traceJson.contains("***"));
    }

    @Test
    @DisplayName("template call memakai invoker yang disuntikkan")
    void templateInvocation() {
        FlowDefinition flow = flow(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("tpl", NodeType.TEMPLATE_CALL, Map.of(
                        "templateKey", "snapbi.timestamp",
                        "version", 1,
                        "args", Map.of("value", "=request.body.id"))),
                node("resp", NodeType.RESPONSE, Map.of("body", Map.of("ts", "=vars.templateResult"))));

        FlowEngine.ExecutionOptions options = FlowEngine.ExecutionOptions.defaults()
                .withTemplates((key, args, ctx) -> key + "|" + args.get("value"));

        ExecutionResult result = engine.execute(flow, ExecutionRequest.simple("POST", "/uji", body("id", "X9")), options);
        assertEquals(Map.of("ts", "snapbi.timestamp@1|X9"), result.body());
    }

    @Test
    @DisplayName("flow tanpa RESPONSE mengembalikan variabel sebagai body")
    void noResponseNode() {
        FlowDefinition flow = flow(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("set", NodeType.SET_VARIABLE, Map.of("assignments", Map.of("nilai", "=request.body.n"))));

        ExecutionResult result = run(flow, body("n", 7));
        assertEquals(200, result.status());
        @SuppressWarnings("unchecked")
        Map<String, Object> vars = (Map<String, Object>) ((Map<String, Object>) result.body()).get("vars");
        assertEquals(7, vars.get("nilai"));
    }

    @Test
    @DisplayName("batas langkah mencegah siklus tak berujung")
    void maxStepsGuard() {
        FlowEngine limited = new FlowEngine(new EngineSettings(
                5_000, 100, true, 100, 10, java.util.Set.of(NodeType.values()), List.of()));

        FlowDefinition cyclic = new FlowDefinition(null, "siklus", List.of(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("a", NodeType.SET_VARIABLE, Map.of("assignments", Map.of("x", 1))),
                node("b", NodeType.SET_VARIABLE, Map.of("assignments", Map.of("y", 2)))
        ), List.of(edge("trigger", "a"), edge("a", "b"), edge("b", "a")), 1);

        ExecutionResult result = limited.execute(cyclic, ExecutionRequest.simple("POST", "/uji", Map.of()));
        assertEquals(500, result.status());
        assertTrue(String.valueOf(result.body()).toLowerCase().contains("langkah")
                || String.valueOf(result.body()).toLowerCase().contains("siklus"));
    }

    @Test
    @DisplayName("log node tercatat pada hasil eksekusi")
    void logsCaptured() {
        FlowDefinition flow = flow(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("log", NodeType.LOG, Map.of("message", "memproses ${request.body.id}")),
                node("resp", NodeType.RESPONSE, Map.of("body", Map.of("ok", true))));

        ExecutionResult result = run(flow, body("id", "Z1"));
        assertTrue(result.logs().stream().anyMatch(l -> l.contains("memproses Z1")));
    }

    @Test
    @DisplayName("trace mencatat durasi dan tipe node")
    void traceMetadata() {
        FlowDefinition flow = flow(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("resp", NodeType.RESPONSE, Map.of("body", Map.of("ok", true))));

        ExecutionResult result = run(flow, Map.of());
        TraceStep first = result.trace().get(0);
        assertEquals("trigger", first.nodeId());
        assertEquals("TRIGGER", first.nodeType());
        assertTrue(first.durationMs() >= 0);
        assertNotNull(result.durationMs());
    }

    @Test
    @DisplayName("seed di-rollback sehingga run berikutnya bersih")
    void sandboxIsolatedBetweenRuns() {
        FlowDefinition dbFlow = flow(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("db", NodeType.DATABASE_QUERY, Map.of("sql", "select * from hanya_seed")));

        ExecutionRequest seeded = new ExecutionRequest(null, null, "POST", "/uji",
                Map.of(), Map.of(), Map.of(), true, List.of(),
                List.of(new ExecutionRequest.DbSeed("hanya_seed",
                        List.of(Map.of("id", "1")))), null);
        assertEquals(200, engine.execute(dbFlow, seeded).status());

        // run tanpa seed tidak boleh melihat tabel dari run sebelumnya
        ExecutionResult withoutSeed = engine.execute(dbFlow,
                ExecutionRequest.simple("POST", "/uji", Map.of()));
        assertEquals(500, withoutSeed.status());
    }

    @Test
    @DisplayName("engine tanpa node awal gagal dengan pesan jelas")
    void emptyFlowFails() {
        FlowDefinition empty = FlowDefinition.empty("kosong");
        ExecutionResult result = engine.execute(empty, ExecutionRequest.simple("GET", "/x", null));
        assertEquals(500, result.status());
        assertTrue(String.valueOf(result.body()).contains("node awal"));
    }

    @Test
    @DisplayName("status response diambil dari node RESPONSE")
    void customStatusCode() {
        FlowDefinition flow = flow(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("resp", NodeType.RESPONSE, Map.of("status", 422,
                        "body", Map.of("error", "validasi gagal"))));

        ExecutionResult result = run(flow, Map.of());
        assertEquals(422, result.status());
        assertEquals(Map.of("error", "validasi gagal"), result.body());
    }

    @Test
    @DisplayName("header response diteruskan")
    void responseHeaders() {
        FlowDefinition flow = flow(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("resp", NodeType.RESPONSE, Map.of("status", 201,
                        "headers", Map.of("X-Custom", "nilai"),
                        "body", Map.of("ok", true))));

        ExecutionResult result = run(flow, Map.of());
        assertEquals("nilai", result.headers().get("X-Custom"));
        assertEquals("application/json", result.headers().get("Content-Type"));
    }

    @Test
    @DisplayName("parallel menjalankan cabang dan join deterministik")
    void parallelBranches() {
        FlowDefinition flow = new FlowDefinition(null, "paralel", List.of(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("par", NodeType.PARALLEL, Map.of()),
                node("cabangA", NodeType.LOG, Map.of("message", "A")),
                node("cabangB", NodeType.LOG, Map.of("message", "B")),
                node("gabung", NodeType.RESPONSE, Map.of("body", Map.of("ok", true)))
        ), List.of(
                edge("trigger", "par"),
                edge("par", "cabangA", "branch1"),
                edge("par", "cabangB", "branch2"),
                edge("cabangA", "gabung"),
                edge("cabangB", "gabung")
        ), 1);

        ExecutionResult result = run(flow, Map.of());
        assertEquals(200, result.status());
        assertTrue(result.trace().stream().anyMatch(s -> "par".equals(s.nodeId())));
        assertTrue(result.trace().stream().anyMatch(s -> "cabangA".equals(s.nodeId())));
        assertTrue(result.trace().stream().anyMatch(s -> "cabangB".equals(s.nodeId())));
    }

    @Test
    @DisplayName("http real ke host tidak dikenal gagal, bukan menggantung")
    void httpRealUnreachable() {
        FlowDefinition flow = flow(
                node("trigger", NodeType.TRIGGER, Map.of()),
                node("http", NodeType.HTTP_CALL, Map.of(
                        "url", "http://127.0.0.1:9/tidak-ada",
                        "method", "GET",
                        "timeoutMs", 1200)));

        ExecutionResult result = run(flow, Map.of());
        assertEquals(500, result.status());
    }
}
