package io.apistudio.engine.runtime;

import io.apistudio.contracts.exec.ExecutionRequest;
import io.apistudio.contracts.flow.FlowDefinition;
import io.apistudio.engine.expr.ExpressionEngine;
import io.apistudio.engine.expr.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Status dan data satu eksekusi flow.
 *
 * <p>Menyediakan resolusi variabel untuk expression engine, penulisan variabel,
 * log per node, dan akses sandbox/mock. Semua node berbagi satu instans selama
 * satu run.
 */
public final class FlowRuntimeContext {

    private final FlowDefinition flow;
    private final ExecutionRequest request;
    private final MockRegistry mocks;
    private final SandboxDatabase sandbox;
    private final TemplateInvoker templateInvoker;
    private final SecretResolver secretResolver;
    private final Map<String, Object> variables = new LinkedHashMap<>();
    private final List<String> flowLogs = new ArrayList<>();
    private final ExpressionEngine expression;

    private String currentNodeId;
    private List<String> currentNodeLogs = new ArrayList<>();

    public FlowRuntimeContext(FlowDefinition flow,
                              ExecutionRequest request,
                              MockRegistry mocks,
                              SandboxDatabase sandbox,
                              TemplateInvoker templateInvoker,
                              SecretResolver secretResolver,
                              Supplier<String> timestampSupplier) {
        this.flow = flow;
        this.request = request;
        this.mocks = mocks;
        this.sandbox = sandbox;
        this.templateInvoker = templateInvoker == null ? TemplateInvoker.NONE : templateInvoker;
        this.secretResolver = secretResolver == null ? SecretResolver.EMPTY : secretResolver;
        this.expression = new ExpressionEngine(this::resolveVariable, timestampSupplier, null);
        this.variables.put("request", requestEnvelope());
        this.variables.put("vars", new LinkedHashMap<String, Object>());
        this.variables.put("flow", Map.of("name", flow.name() == null ? "" : flow.name(),
                "version", flow.version()));
    }

    public FlowDefinition flow() {
        return flow;
    }

    public ExecutionRequest request() {
        return request;
    }

    public ExpressionEngine expression() {
        return expression;
    }

    public MockRegistry mocks() {
        return mocks;
    }

    public SandboxDatabase sandbox() {
        return sandbox;
    }

    public TemplateInvoker templates() {
        return templateInvoker;
    }

    public SecretResolver secrets() {
        return secretResolver;
    }

    /** Evaluasi ekspresi dengan konteks ini. */
    public Object eval(String expressionText) {
        return expression.eval(expressionText);
    }

    /** Selesaikan nilai (ekspresi atau template string). */
    public Object valueOf(Object raw) {
        return expression.resolve(raw);
    }

    /** Akses variabel tingkat atas (mis. {@code request}, {@code vars}, {@code response}). */
    public Object get(String name) {
        return variables.get(name);
    }

    /** Set variabel tingkat atas. */
    public void set(String name, Object value) {
        variables.put(name, value);
    }

    /** Set field di dalam {@code vars}. */
    @SuppressWarnings("unchecked")
    public void setVar(String name, Object value) {
        Object vars = variables.computeIfAbsent("vars", k -> new LinkedHashMap<String, Object>());
        ((Map<String, Object>) vars).put(name, value);
    }

    /** Seluruh variabel (untuk trace). */
    public Map<String, Object> variables() {
        return new LinkedHashMap<>(variables);
    }

    /** Set variabel {@code response} setelah node RESPONSE. */
    public void setResponse(Map<String, Object> response) {
        variables.put("response", response);
    }

    /** Log tingkat flow. */
    public void log(String message) {
        flowLogs.add(message);
        currentNodeLogs.add(message);
    }

    /** Log yang terkait node yang sedang dieksekusi (kode Java pengguna). */
    public void logCurrentNode(String message) {
        log(message);
    }

    public List<String> logs() {
        return new ArrayList<>(flowLogs);
    }

    /** Tandai node yang sedang dieksekusi; log node dikumpulkan sejak titik ini. */
    public void enterNode(String nodeId) {
        this.currentNodeId = nodeId;
        this.currentNodeLogs = new ArrayList<>();
    }

    public String currentNodeId() {
        return currentNodeId;
    }

    /** Log yang dihasilkan node yang baru selesai. */
    public List<String> drainCurrentNodeLogs() {
        List<String> result = currentNodeLogs;
        currentNodeLogs = new ArrayList<>();
        return result;
    }

    /** Envelope {@code request} yang tersedia untuk ekspresi. */
    private Map<String, Object> requestEnvelope() {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("method", request.method());
        envelope.put("path", request.path());
        envelope.put("headers", request.headers());
        envelope.put("query", request.query());
        envelope.put("body", request.body() == null ? Map.of() : request.body());
        return envelope;
    }

    /** Resolver variabel untuk expression engine. */
    private Object resolveVariable(String name) {
        if (variables.containsKey(name)) {
            return variables.get(name);
        }
        // dukung akses `response.status` pada hasil ExecutionResult lewat vars
        return null;
    }

    /** Bentuk map untuk respons debug/simulasi. */
    public Map<String, Object> snapshotVariables() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        variables.forEach((k, v) -> snapshot.put(k, Masking.mask(v)));
        return snapshot;
    }

    /** Serialisasi variabel untuk disimpan pada trace. */
    String variablesJson() {
        return Json.encode(snapshotVariables());
    }
}
