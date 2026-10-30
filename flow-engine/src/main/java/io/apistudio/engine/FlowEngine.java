package io.apistudio.engine;

import io.apistudio.contracts.exec.ExecutionRequest;
import io.apistudio.contracts.exec.ExecutionResult;
import io.apistudio.contracts.exec.TraceStep;
import io.apistudio.contracts.flow.FlowDefinition;
import io.apistudio.contracts.flow.NodeType;
import io.apistudio.engine.java.ArtifactResolver;
import io.apistudio.engine.java.ClassLoaderCache;
import io.apistudio.engine.java.JavaCodeRunner;
import io.apistudio.engine.nodes.ConditionExecutor;
import io.apistudio.engine.nodes.DatabaseQueryExecutor;
import io.apistudio.engine.nodes.DelayExecutor;
import io.apistudio.engine.nodes.FailExecutor;
import io.apistudio.engine.nodes.HttpCallExecutor;
import io.apistudio.engine.nodes.JavaCodeExecutor;
import io.apistudio.engine.nodes.LogExecutor;
import io.apistudio.engine.nodes.LoopExecutor;
import io.apistudio.engine.nodes.ParallelExecutor;
import io.apistudio.engine.nodes.ResponseExecutor;
import io.apistudio.engine.nodes.SetVariableExecutor;
import io.apistudio.engine.nodes.TemplateCallExecutor;
import io.apistudio.engine.nodes.TriggerExecutor;
import io.apistudio.engine.nodes.TryCatchExecutor;
import io.apistudio.engine.runtime.EngineSettings;
import io.apistudio.engine.runtime.FlowRuntimeContext;
import io.apistudio.engine.runtime.FlowWalker;
import io.apistudio.engine.runtime.MockRegistry;
import io.apistudio.engine.runtime.NodeExecutorRegistry;
import io.apistudio.engine.runtime.SandboxDatabase;
import io.apistudio.engine.runtime.SecretResolver;
import io.apistudio.engine.runtime.TemplateInvoker;
import io.apistudio.engine.runtime.TraceRecorder;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Titik masuk Flow Engine (BRD Bagian 5.1, 5.3).
 *
 * <p>Engine ini murni library: tidak bergantung pada Spring maupun kelas
 * Control Plane, sehingga dapat dipindah menjadi service sendiri tanpa
 * menulis ulang logika eksekusi. Semua kebutuhan eksternal (artifact, secret,
 * template, mock, sandbox) disuntikkan lewat {@link ExecutionOptions}.
 *
 * <p>Engine yang sama dipakai untuk simulasi dan produksi (Bagian 5.2 poin 4);
 * mode debug hanya menambah observasi.
 */
public final class FlowEngine {

    private final EngineSettings settings;
    private final ClassLoaderCache classLoaderCache;
    private final JavaCodeRunner javaCodeRunner;

    public FlowEngine(EngineSettings settings) {
        this.settings = settings == null ? EngineSettings.defaults() : settings;
        this.classLoaderCache = new ClassLoaderCache();
        this.javaCodeRunner = new JavaCodeRunner(classLoaderCache);
    }

    public EngineSettings settings() {
        return settings;
    }

    /** Cache classloader; dipakai Control Plane untuk melepas versi setelah drain. */
    public ClassLoaderCache classLoaderCache() {
        return classLoaderCache;
    }

    /** Eksekusi flow dengan opsi default. */
    public ExecutionResult execute(FlowDefinition flow, ExecutionRequest request) {
        return execute(flow, request, ExecutionOptions.defaults());
    }

    /**
     * Eksekusi flow.
     *
     * @param flow    definisi graph
     * @param request permintaan dan konteksnya
     * @param options sumber eksternal (artifact, secret, template, dll.)
     */
    public ExecutionResult execute(FlowDefinition flow, ExecutionRequest request, ExecutionOptions options) {
        ExecutionOptions opts = options == null ? ExecutionOptions.defaults() : options;
        String traceId = request.traceId() == null || request.traceId().isBlank()
                ? UUID.randomUUID().toString()
                : request.traceId();
        long started = System.currentTimeMillis();

        TraceRecorder trace = new TraceRecorder(settings.maxTraceSteps());
        SandboxDatabase sandbox = settings.sandboxEnabled()
                ? SandboxDatabase.open(traceId, request.seed(), settings.sandboxMaxRows())
                : SandboxDatabase.disabled();

        Clock clock = opts.clock() == null ? Clock.systemUTC() : opts.clock();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
                .withZone(clock.getZone() == null ? ZoneOffset.UTC : clock.getZone());

        FlowRuntimeContext ctx = new FlowRuntimeContext(
                flow,
                request,
                new MockRegistry(request.mocks()),
                sandbox,
                opts.templateInvoker(),
                opts.secretResolver(),
                () -> formatter.format(clock.instant())
        );

        NodeExecutorRegistry registry = buildRegistry(opts.artifactResolver(), trace);
        FlowWalker walker = new FlowWalker(registry, trace, settings.maxSteps());

        try {
            runWithGlobalTimeout(ctx, walker);
        } catch (Throwable failure) {
            long duration = System.currentTimeMillis() - started;
            String message = failure.getMessage() == null
                    ? failure.getClass().getSimpleName()
                    : failure.getMessage();
            return new ExecutionResult(500, "Internal Server Error",
                    Map.of("Content-Type", "application/json"),
                    Map.of("error", "FLOW_EXECUTION_FAILED", "message", message,
                            "traceId", traceId),
                    traceId, trace.steps(), duration, ctx.logs(), sandbox.touchedTables());
        } finally {
            sandbox.close();
        }

        long duration = System.currentTimeMillis() - started;
        Object response = ctx.get("response");
        if (response instanceof Map<?, ?> map) {
            Object statusValue = map.get("status");
            int status = statusValue instanceof Number n ? n.intValue() : 200;
            Map<String, String> headers = toStringMap(map.get("headers"));
            Object body = map.get("body");
            return new ExecutionResult(status, "", headers, body, traceId, trace.steps(),
                    duration, ctx.logs(), sandbox.touchedTables());
        }

        // Flow tanpa node RESPONSE: kembalikan variabel sebagai body agar
        // hasil tetap terlihat saat simulasi.
        Map<String, Object> body = ctx.snapshotVariables();
        return new ExecutionResult(200, "OK", Map.of("Content-Type", "application/json"),
                body, traceId, trace.steps(), duration, ctx.logs(), sandbox.touchedTables());
    }

    /** Jalankan walker dengan batas waktu global (Bagian 5.2 poin 4, NFR-05). */
    private void runWithGlobalTimeout(FlowRuntimeContext ctx, FlowWalker walker) {
        long timeout = settings.flowTimeoutMs();
        if (timeout <= 0) {
            walker.walk(ctx);
            return;
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable, "flow-run");
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            java.util.concurrent.Future<?> future = pool.submit(() -> walker.walk(ctx));
            future.get(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("Eksekusi flow melewati batas " + timeout + " ms");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Eksekusi flow diinterupsi");
        } finally {
            pool.shutdownNow();
        }
    }

    /** Susun registry lengkap 16 jenis node. */
    private NodeExecutorRegistry buildRegistry(ArtifactResolver artifacts, TraceRecorder trace) {
        // Node komposit (LOOP/PARALLEL/TRY_CATCH) perlu menjalankan sub-graph,
        // sehingga walker dibuat lebih dulu dan disuntikkan lewat referensi
        // yang stabil. Walker memakai registry yang sama agar semua jenis node
        // selalu dapat dieksekusi, termasuk di dalam sub-graph.
        NodeExecutorRegistry registry = new NodeExecutorRegistry();
        FlowRunner[] runnerRef = new FlowRunner[1];
        FlowRunner subWalker = (ctx, nodeId) -> runnerRef[0].walkFrom(ctx, nodeId);

        FlowWalker walker = new FlowWalker(registry, trace, settings.maxSteps());
        runnerRef[0] = walker::walkFrom;

        registry.register(NodeType.TRIGGER, new TriggerExecutor());
        registry.register(NodeType.SET_VARIABLE, new SetVariableExecutor());
        registry.register(NodeType.TRANSFORM, new SetVariableExecutor());
        registry.register(NodeType.CONDITION, new ConditionExecutor());
        registry.register(NodeType.SWITCH, new ConditionExecutor());
        registry.register(NodeType.LOOP, new LoopExecutor(subWalker));
        registry.register(NodeType.PARALLEL, new ParallelExecutor(subWalker));
        registry.register(NodeType.TRY_CATCH, new TryCatchExecutor(subWalker));
        registry.register(NodeType.HTTP_CALL, new HttpCallExecutor());
        registry.register(NodeType.DATABASE_QUERY, new DatabaseQueryExecutor());
        registry.register(NodeType.TEMPLATE_CALL, new TemplateCallExecutor());
        registry.register(NodeType.JAVA_CODE, new JavaCodeExecutor(javaCodeRunner, artifacts));
        registry.register(NodeType.RESPONSE, new ResponseExecutor());
        registry.register(NodeType.LOG, new LogExecutor());
        registry.register(NodeType.DELAY, new DelayExecutor());
        registry.register(NodeType.FAIL, new FailExecutor());
        return registry;
    }

    /** Kontrak internal untuk mengeksekusi sub-graph dari node tertentu. */
    @FunctionalInterface
    public interface FlowRunner {
        io.apistudio.contracts.flow.FlowNode walkFrom(FlowRuntimeContext ctx, String nodeId);
    }

    private static Map<String, String> toStringMap(Object value) {
        Map<String, Object> map = io.apistudio.engine.expr.Json.asMap(value);
        if (map == null) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((k, v) -> result.put(k, v == null ? "" : String.valueOf(v)));
        return result;
    }

    /**
     * Sumber eksternal untuk satu eksekusi.
     *
     * @param apiId            id API (untuk log)
     * @param flowVersion      versi flow aktif
     * @param environment      environment target
     * @param secretResolver   penyedia secret
     * @param templateInvoker  pemanggil function template
     * @param artifactResolver penyedia bytecode node Java
     * @param clock            sumber waktu (deterministik pada pengujian)
     * @param environmentVariables variabel environment
     */
    public record ExecutionOptions(
            String apiId,
            String flowVersion,
            String environment,
            SecretResolver secretResolver,
            TemplateInvoker templateInvoker,
            ArtifactResolver artifactResolver,
            Clock clock,
            Map<String, String> environmentVariables
    ) {

        public ExecutionOptions {
            secretResolver = secretResolver == null ? SecretResolver.EMPTY : secretResolver;
            templateInvoker = templateInvoker == null ? TemplateInvoker.NONE : templateInvoker;
            artifactResolver = artifactResolver == null ? ArtifactResolver.EMPTY : artifactResolver;
            environmentVariables = environmentVariables == null ? Map.of() : Map.copyOf(environmentVariables);
        }

        public static ExecutionOptions defaults() {
            return new ExecutionOptions(null, null, "dev", SecretResolver.EMPTY, TemplateInvoker.NONE,
                    ArtifactResolver.EMPTY, Clock.systemUTC(), Map.of());
        }

        /** Opsi dengan versi dan environment tertentu (dipakai Control Plane). */
        public static ExecutionOptions forVersion(String apiId, String flowVersion, String environment) {
            return new ExecutionOptions(apiId, flowVersion, environment, SecretResolver.EMPTY,
                    TemplateInvoker.NONE, ArtifactResolver.EMPTY, Clock.systemUTC(), Map.of());
        }

        public ExecutionOptions withSecrets(SecretResolver resolver) {
            return new ExecutionOptions(apiId, flowVersion, environment, resolver, templateInvoker,
                    artifactResolver, clock, environmentVariables);
        }

        public ExecutionOptions withTemplates(TemplateInvoker invoker) {
            return new ExecutionOptions(apiId, flowVersion, environment, secretResolver, invoker,
                    artifactResolver, clock, environmentVariables);
        }

        public ExecutionOptions withArtifacts(ArtifactResolver resolver) {
            return new ExecutionOptions(apiId, flowVersion, environment, secretResolver,
                    templateInvoker, resolver, clock, environmentVariables);
        }

        public ExecutionOptions withClock(Clock newClock) {
            return new ExecutionOptions(apiId, flowVersion, environment, secretResolver,
                    templateInvoker, artifactResolver, newClock, environmentVariables);
        }

        public ExecutionOptions withEnvironment(String newEnvironment) {
            return new ExecutionOptions(apiId, flowVersion, newEnvironment, secretResolver,
                    templateInvoker, artifactResolver, clock, environmentVariables);
        }

        public ExecutionOptions withEnvironmentVariables(Map<String, String> variables) {
            return new ExecutionOptions(apiId, flowVersion, environment, secretResolver,
                    templateInvoker, artifactResolver, clock, variables);
        }
    }

    /** Daftar jenis node yang didukung engine ini. */
    public static Set<NodeType> supportedNodeTypes() {
        return Set.of(NodeType.values());
    }

    /** Ringkasan langkah trace untuk assertion (FR-SIM-08). */
    public static Map<String, Object> summarizeTrace(List<TraceStep> steps) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("steps", steps.size());
        summary.put("errors", steps.stream().filter(s -> s.status() == TraceStep.Status.ERROR).count());
        summary.put("nodes", steps.stream().map(TraceStep::nodeId).distinct().toList());
        return summary;
    }
}
