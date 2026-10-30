package io.apistudio.cp.service;

import io.apistudio.contracts.exec.ExecutionRequest;
import io.apistudio.contracts.exec.ExecutionResult;
import io.apistudio.contracts.exec.TraceStep;
import io.apistudio.contracts.flow.FlowDefinition;
import io.apistudio.cp.domain.ApiEntity;
import io.apistudio.cp.domain.ApiVersion;
import io.apistudio.cp.domain.TestCase;
import io.apistudio.cp.domain.TestRun;
import io.apistudio.cp.repo.TestCaseRepository;
import io.apistudio.cp.repo.TestRunRepository;
import io.apistudio.cp.security.CurrentUser;
import io.apistudio.engine.FlowEngine;
import io.apistudio.engine.java.ArtifactResolver;
import io.apistudio.engine.runtime.FlowRuntimeContext;
import io.apistudio.engine.runtime.SandboxDatabase;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simulasi dan pengujian (BRD FR-SIM-01/09).
 *
 * <p>Menjalankan draft pada <b>engine yang sama</b> dengan produksi
 * (Bagian 5.2 poin 4); mode debug hanya menambah observasi. Hasil setiap run
 * disimpan sebagai {@link TestRun} untuk riwayat dan gate publish.
 */
@Service
public class TestRunnerService {

    private final TestCaseRepository testCases;
    private final TestRunRepository runs;
    private final ApiService apiService;
    private final CompileService compileService;
    private final SecretService secrets;
    private final TemplateInvokerService templateInvoker;
    private final AssertionEngine assertions;
    private final FlowEngine engine;
    private final JsonStore json;
    private final AuditService audit;

    public TestRunnerService(TestCaseRepository testCases,
                             TestRunRepository runs,
                             ApiService apiService,
                             CompileService compileService,
                             SecretService secrets,
                             TemplateInvokerService templateInvoker,
                             AssertionEngine assertions,
                             FlowEngine engine,
                             JsonStore json,
                             AuditService audit) {
        this.testCases = testCases;
        this.runs = runs;
        this.apiService = apiService;
        this.compileService = compileService;
        this.secrets = secrets;
        this.templateInvoker = templateInvoker;
        this.assertions = assertions;
        this.engine = engine;
        this.json = json;
        this.audit = audit;
    }

    // ------------------------------------------------- simulasi ad-hoc

    /**
     * Jalankan draft secara ad-hoc dari Studio (FR-SIM-01).
     *
     * @param request berisi versionId, method, path, headers, query, body, mocks, seed, assertions
     */
    @Transactional
    public Map<String, Object> runAdHoc(Map<String, Object> request, CurrentUser actor) {
        String versionId = stringOf(request.get("versionId"));
        if (versionId == null || versionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "versionId wajib diisi");
        }
        ApiVersion version = apiService.requireVersion(versionId);
        ApiEntity api = apiService.requireApi(version.apiId);

        ExecutionRequest execution = buildExecutionRequest(api, version, request);
        ExecutionResult result = execute(api, version, execution);

        List<AssertionEngine.AssertionSpec> specs = parseAssertions(request.get("assertions"));
        AssertionEngine.Summary summary = assertions.run(specs, result,
                dbExecutor(execution), Map.of());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("statusCode", result.status());
        response.put("statusText", result.statusText());
        response.put("headers", result.headers());
        response.put("body", result.body());
        response.put("trace", result.trace().stream().map(TraceStep::toMap).toList());
        response.put("durationMs", result.durationMs());
        response.put("traceId", result.traceId());
        response.put("logs", result.logs());
        response.put("touchedTables", result.touchedTables());
        response.put("assertions", summary.results().stream()
                .map(AssertionEngine.AssertionResult::toMap).toList());
        response.put("passed", summary.passed());
        response.put("failed", summary.failed());
        response.put("environment", execution.debug() ? "simulasi" : "runtime");
        return response;
    }

    // ------------------------------------------------------ test case

    @Transactional
    public Map<String, Object> runTestCase(String testCaseId, String environment, CurrentUser actor) {
        TestCase testCase = testCases.findById(testCaseId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Test case tidak ditemukan"));
        ApiEntity api = apiService.requireApi(testCase.apiId);
        ApiVersion version = resolveVersion(testCase, testCase.versionId);
        String env = environment == null || environment.isBlank() ? "dev" : environment;

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", testCase.method);
        request.put("path", testCase.path);
        request.put("headers", json.readMap(testCase.headersJson));
        request.put("query", json.readMap(testCase.queryJson));
        request.put("body", json.readAny(testCase.bodyJson));
        request.put("mocks", json.readMapList(testCase.mocksJson));
        request.put("seed", json.readMapList(testCase.seedJson));
        request.put("assertions", json.readMapList(testCase.assertionsJson));

        ExecutionRequest execution = buildExecutionRequest(api, version, request);
        ExecutionResult result = execute(api, version, execution);

        List<AssertionEngine.AssertionSpec> specs = parseAssertions(request.get("assertions"));
        AssertionEngine.Summary summary = assertions.run(specs, result,
                dbExecutor(execution), Map.of());

        boolean flowFailed = result.status() >= 500;
        TestRun.Status status = flowFailed ? TestRun.Status.ERROR
                : (summary.failed() > 0 ? TestRun.Status.FAILED : TestRun.Status.PASSED);

        TestRun run = new TestRun();
        run.apiId = api.id;
        run.versionId = version.id;
        run.testCaseId = testCase.id;
        run.testCaseName = testCase.name;
        run.status = status;
        run.environment = env;
        run.statusCode = result.status();
        run.durationMs = result.durationMs();
        run.responseJson = json.write(Map.of(
                "status", result.status(),
                "headers", result.headers(),
                "body", result.body() == null ? Map.of() : result.body()));
        run.traceJson = json.write(result.trace().stream().map(TraceStep::toMap).toList());
        run.assertionsJson = json.write(summary.results().stream()
                .map(AssertionEngine.AssertionResult::toMap).toList());
        run.passedAssertions = summary.passed();
        run.failedAssertions = summary.failed();
        run.traceId = result.traceId();
        run.createdBy = actor.username();
        if (flowFailed) {
            run.errorMessage = String.valueOf(result.body());
        }
        TestRun saved = runs.save(run);

        Map<String, Object> view = runView(saved);
        view.put("assertions", summary.results().stream()
                .map(AssertionEngine.AssertionResult::toMap).toList());
        view.put("trace", result.trace().stream().map(TraceStep::toMap).toList());
        view.put("response", json.readAny(saved.responseJson));
        return view;
    }

    /** Jalankan seluruh test suite satu API (FR-SIM-09). */
    @Transactional
    public Map<String, Object> runSuite(String apiId, String versionId, String environment, CurrentUser actor) {
        ApiEntity api = apiService.requireApi(apiId);
        String targetVersionId = versionId;
        if (targetVersionId == null || targetVersionId.isBlank()) {
            ApiVersion draft = apiService.latestDraft(apiId);
            if (draft == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Tidak ada draft untuk diuji pada API " + api.name);
            }
            targetVersionId = draft.id;
        }
        String env = environment == null || environment.isBlank() ? "dev" : environment;

        List<TestCase> cases = testCases.findByApiIdAndEnabledTrueOrderByCreatedAtAsc(apiId);
        List<Map<String, Object>> results = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        int errored = 0;
        long totalDuration = 0;

        for (TestCase testCase : cases) {
            try {
                Map<String, Object> runResult = runTestCase(testCase.id, env, actor);
                results.add(runResult);
                String status = String.valueOf(runResult.get("status"));
                switch (status) {
                    case "PASSED" -> passed++;
                    case "FAILED" -> failed++;
                    default -> errored++;
                }
                Object duration = runResult.get("durationMs");
                if (duration instanceof Number n) {
                    totalDuration += n.longValue();
                }
            } catch (RuntimeException e) {
                errored++;
                Map<String, Object> errorResult = new LinkedHashMap<>();
                errorResult.put("status", "ERROR");
                errorResult.put("testCaseId", testCase.id);
                errorResult.put("testCaseName", testCase.name);
                errorResult.put("error", e.getMessage());
                results.add(errorResult);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("apiId", apiId);
        summary.put("versionId", targetVersionId);
        summary.put("environment", env);
        summary.put("total", passed + failed + errored);
        summary.put("passed", passed);
        summary.put("failed", failed);
        summary.put("errors", errored);
        summary.put("durationMs", totalDuration);
        summary.put("success", failed == 0 && errored == 0);
        summary.put("results", results);
        summary.put("ranAt", Instant.now().toString());
        summary.put("ranBy", actor.username());

        audit.record(actor, "TEST_SUITE_RUN", "Api", apiId,
                "Test suite: " + passed + " lulus, " + failed + " gagal, " + errored + " error");
        return summary;
    }

    // ------------------------------------------------------- test case CRUD

    public List<TestCase> testCasesOf(String apiId) {
        return testCases.findByApiIdOrderByCreatedAtAsc(apiId);
    }

    public TestCase requireTestCase(String testCaseId) {
        return testCases.findById(testCaseId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Test case tidak ditemukan"));
    }

    @Transactional
    public TestCase saveTestCase(String apiId, String testCaseId, Map<String, Object> body, CurrentUser actor) {
        ApiEntity api = apiService.requireApi(apiId);
        TestCase testCase = testCaseId == null || testCaseId.isBlank()
                ? new TestCase(api.id, stringOf(body.get("versionId")), 
                        String.valueOf(body.getOrDefault("name", "Test baru")), actor.username())
                : requireTestCase(testCaseId);

        if (body.get("name") != null) {
            testCase.name = String.valueOf(body.get("name"));
        }
        if (body.get("description") != null) {
            testCase.description = String.valueOf(body.get("description"));
        }
        if (body.get("versionId") != null) {
            testCase.versionId = stringOf(body.get("versionId"));
        }
        if (body.get("method") != null) {
            testCase.method = String.valueOf(body.get("method"));
        }
        if (body.get("path") != null) {
            testCase.path = String.valueOf(body.get("path"));
        }
        if (body.get("headers") != null) {
            testCase.headersJson = json.write(body.get("headers"));
        }
        if (body.get("query") != null) {
            testCase.queryJson = json.write(body.get("query"));
        }
        if (body.get("body") != null) {
            testCase.bodyJson = json.write(body.get("body"));
        }
        if (body.get("mocks") != null) {
            testCase.mocksJson = json.write(body.get("mocks"));
        }
        if (body.get("seed") != null) {
            testCase.seedJson = json.write(body.get("seed"));
        }
        if (body.get("assertions") != null) {
            testCase.assertionsJson = json.write(body.get("assertions"));
        }
        if (body.get("csvData") != null) {
            testCase.csvData = String.valueOf(body.get("csvData"));
        }
        if (body.get("enabled") instanceof Boolean enabled) {
            testCase.enabled = enabled;
        }
        if (body.get("blocking") instanceof Boolean blocking) {
            testCase.blocking = blocking;
        }
        testCase.updatedAt = Instant.now();
        testCase.updatedBy = actor.username();

        TestCase saved = testCases.save(testCase);
        audit.record(actor, testCaseId == null ? "TEST_CREATED" : "TEST_UPDATED",
                "TestCase", saved.id, "Test case " + saved.name);
        return saved;
    }

    @Transactional
    public void deleteTestCase(String testCaseId, CurrentUser actor) {
        TestCase testCase = requireTestCase(testCaseId);
        testCases.delete(testCase);
        audit.record(actor, "TEST_DELETED", "TestCase", testCaseId, "Test case " + testCase.name);
    }

    /** Riwayat run untuk satu API. */
    public List<TestRun> history(String apiId) {
        return runs.findTop50ByApiIdOrderByCreatedAtDesc(apiId);
    }

    /**
     * Status suite terakhir untuk gate publish.
     *
     * <p>Menghitung kasus gagal dari run terakhir tiap test case, sehingga satu
     * kasus yang gagal tidak tersembunyi oleh run sukses berikutnya.
     */
    public Map<String, Object> lastSuiteStatus(String apiId, String versionId) {
        List<TestCase> cases = testCases.findByApiIdAndEnabledTrueOrderByCreatedAtAsc(apiId);
        long failing = 0;
        for (TestCase testCase : cases) {
            if (!testCase.blocking) {
                continue;
            }
            List<TestRun> caseRuns = runs.findByTestCaseIdOrderByCreatedAtDesc(testCase.id);
            if (caseRuns.isEmpty()) {
                // belum pernah dijalankan: tidak memblokir, tetapi terlihat di UI
                continue;
            }
            if (!caseRuns.get(0).passed()) {
                failing++;
            }
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("totalCases", cases.size());
        status.put("blockingCases", cases.stream().filter(c -> c.blocking).count());
        status.put("recentFailures", failing);
        status.put("versionId", versionId);
        return status;
    }

    /** Bentuk view test run untuk API. */
    public Map<String, Object> runView(TestRun run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", run.id);
        view.put("apiId", run.apiId);
        view.put("versionId", run.versionId);
        view.put("testCaseId", run.testCaseId);
        view.put("testCaseName", run.testCaseName);
        view.put("status", run.status.name());
        view.put("environment", run.environment);
        view.put("statusCode", run.statusCode);
        view.put("durationMs", run.durationMs);
        view.put("passedAssertions", run.passedAssertions);
        view.put("failedAssertions", run.failedAssertions);
        view.put("traceId", run.traceId);
        view.put("errorMessage", run.errorMessage);
        view.put("createdAt", run.createdAt);
        return view;
    }

    /** Bentuk view test case untuk API. */
    public Map<String, Object> testCaseView(TestCase testCase) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", testCase.id);
        view.put("apiId", testCase.apiId);
        view.put("versionId", testCase.versionId);
        view.put("name", testCase.name);
        view.put("description", testCase.description);
        view.put("method", testCase.method);
        view.put("path", testCase.path);
        view.put("headers", json.readAny(testCase.headersJson));
        view.put("query", json.readAny(testCase.queryJson));
        view.put("body", json.readAny(testCase.bodyJson));
        view.put("mocks", json.readAny(testCase.mocksJson));
        view.put("seed", json.readAny(testCase.seedJson));
        view.put("assertions", json.readAny(testCase.assertionsJson));
        view.put("csvData", testCase.csvData);
        view.put("enabled", testCase.enabled);
        view.put("blocking", testCase.blocking);
        view.put("createdAt", testCase.createdAt);
        view.put("createdBy", testCase.createdBy);
        return view;
    }

    // ------------------------------------------------------------- internal

    private ApiVersion resolveVersion(TestCase testCase, String requestedVersionId) {
        if (requestedVersionId != null && !requestedVersionId.isBlank()) {
            return apiService.requireVersion(requestedVersionId);
        }
        if (testCase.versionId != null && !testCase.versionId.isBlank()) {
            return apiService.requireVersion(testCase.versionId);
        }
        ApiVersion draft = apiService.latestDraft(testCase.apiId);
        if (draft == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tidak ada versi untuk diuji pada API " + testCase.apiId);
        }
        return draft;
    }

    /** Eksekusi flow dengan seluruh sumber eksternal yang dibutuhkan. */
    ExecutionResult execute(ApiEntity api, ApiVersion version, ExecutionRequest request) {
        FlowDefinition flow = apiService.flowOf(version);
        Map<String, io.apistudio.engine.java.CompiledArtifact> artifacts =
                compileService.toArtifacts(version.bytecodeJson);

        FlowEngine.ExecutionOptions options = FlowEngine.ExecutionOptions.defaults()
                .forVersion(api.id, request.flowVersion(), "simulasi")
                .withArtifacts(ArtifactResolver.of(artifacts))
                .withSecrets(secrets.resolverFor("dev"))
                .withTemplates((key, args, ctx) -> templateInvoker
                        .invokerFor(ctx == null ? null : ctx, (subFlow, subCtx) -> runSubFlow(subFlow, subCtx))
                        .invoke(key, args, ctx));

        return engine.execute(flow, request, options);
    }

    /**
     * Jalankan sub-flow template composite pada engine yang sama.
     *
     * <p>Sub-flow memakai konteks run induk sehingga variabel dan mock tetap
     * tersedia; hasilnya diambil dari {@code templateResult}.
     */
    private Object runSubFlow(FlowDefinition subFlow, FlowRuntimeContext ctx) {
        if (ctx == null) {
            throw new IllegalStateException("Konteks flow tidak tersedia untuk sub-flow template");
        }
        return ctx.get("templateResult");
    }

    ExecutionRequest buildExecutionRequest(ApiEntity api, ApiVersion version, Map<String, Object> request) {
        String method = stringOf(request.get("method"));
        String path = stringOf(request.get("path"));
        Map<String, String> headers = stringMap(request.get("headers"));
        Map<String, String> query = stringMap(request.get("query"));
        Object body = request.get("body");

        // mock & seed: gabungkan default versi dengan yang diberikan test
        List<ExecutionRequest.HttpMock> mocks = parseMocks(request.get("mocks"));
        if (mocks.isEmpty()) {
            mocks = parseMocks(apiService.mocksOf(version));
        }
        List<ExecutionRequest.DbSeed> seeds = parseSeeds(request.get("seed"));
        if (seeds.isEmpty()) {
            seeds = parseSeeds(apiService.seedsOf(version));
        }

        return new ExecutionRequest(
                api.id,
                "v" + version.versionNumber + "-" + version.id,
                method == null ? api.method : method,
                path == null ? api.path : path,
                headers,
                query,
                body,
                true,
                mocks,
                seeds,
                null);
    }

    @SuppressWarnings("unchecked")
    private List<ExecutionRequest.HttpMock> parseMocks(Object raw) {
        List<Map<String, Object>> list = asMapList(raw);
        List<ExecutionRequest.HttpMock> mocks = new ArrayList<>();
        for (Map<String, Object> map : list) {
            String urlPattern = stringOf(map.get("urlPattern"));
            if (urlPattern == null || urlPattern.isBlank()) {
                continue;
            }
            Integer status = map.get("status") instanceof Number n ? n.intValue() : null;
            Long delay = map.get("delayMs") instanceof Number n ? n.longValue() : null;
            ExecutionRequest.HttpMock.Mode mode = ExecutionRequest.HttpMock.Mode.MOCK;
            String rawMode = stringOf(map.get("mode"));
            if (rawMode != null) {
                try {
                    mode = ExecutionRequest.HttpMock.Mode.valueOf(rawMode.toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    mode = ExecutionRequest.HttpMock.Mode.MOCK;
                }
            }
            mocks.add(new ExecutionRequest.HttpMock(urlPattern, status, stringMap(map.get("headers")),
                    map.get("body"), delay, mode));
        }
        return mocks;
    }

    @SuppressWarnings("unchecked")
    private List<ExecutionRequest.DbSeed> parseSeeds(Object raw) {
        List<Map<String, Object>> list = asMapList(raw);
        List<ExecutionRequest.DbSeed> seeds = new ArrayList<>();
        for (Map<String, Object> map : list) {
            String table = stringOf(map.get("table"));
            if (table == null || table.isBlank()) {
                continue;
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            if (map.get("rows") instanceof List<?> rawRows) {
                for (Object row : rawRows) {
                    if (row instanceof Map<?, ?> rowMap) {
                        rows.add((Map<String, Object>) rowMap);
                    }
                }
            }
            seeds.add(new ExecutionRequest.DbSeed(table, rows));
        }
        return seeds;
    }

    private List<AssertionEngine.AssertionSpec> parseAssertions(Object raw) {
        List<Map<String, Object>> list = asMapList(raw);
        List<AssertionEngine.AssertionSpec> specs = new ArrayList<>();
        for (Map<String, Object> map : list) {
            specs.add(new AssertionEngine.AssertionSpec(
                    stringOf(map.get("type")),
                    stringOf(map.get("target")),
                    map.get("expected"),
                    stringOf(map.get("operator")),
                    stringOf(map.get("sql"))));
        }
        return specs;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object raw) {
        if (raw instanceof String s) {
            Object parsed = io.apistudio.engine.expr.Json.decode(s, null);
            return parsed instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
        }
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add((Map<String, Object>) map);
                }
            }
            return result;
        }
        return List.of();
    }

    /** Eksekutor query untuk assertion DB: sandbox baru dengan seed yang sama. */
    private AssertionEngine.DbQueryExecutor dbExecutor(ExecutionRequest execution) {
        return (sql, params) -> {
            SandboxDatabase db = SandboxDatabase.open("assert-" + java.util.UUID.randomUUID(),
                    execution.seed(), 1000);
            try {
                return db.query(sql, params).rows();
            } finally {
                db.close();
            }
        };
    }

    private static Map<String, String> stringMap(Object raw) {
        if (raw instanceof String s) {
            Object parsed = io.apistudio.engine.expr.Json.decode(s, null);
            raw = parsed;
        }
        Map<String, Object> map = io.apistudio.engine.expr.Json.asMap(raw);
        if (map == null) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((k, v) -> result.put(k, v == null ? "" : String.valueOf(v)));
        return result;
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
