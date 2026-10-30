package io.apistudio.cp.service;

import io.apistudio.contracts.exec.ExecutionRequest;
import io.apistudio.contracts.exec.ExecutionResult;
import io.apistudio.contracts.flow.FlowDefinition;
import io.apistudio.cp.domain.ApiEntity;
import io.apistudio.cp.domain.ApiVersion;
import io.apistudio.cp.domain.Consumer;
import io.apistudio.cp.domain.ReleaseBundle;
import io.apistudio.cp.repo.ApiRepository;
import io.apistudio.cp.repo.ApiVersionRepository;
import io.apistudio.cp.repo.ConsumerRepository;
import io.apistudio.cp.repo.ReleaseBundleRepository;
import io.apistudio.cp.security.ApiKeys;
import io.apistudio.engine.FlowEngine;
import io.apistudio.engine.java.ArtifactResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulasi API Engine / gateway pada MVP (BRD Bagian 5.3, 14.1).
 *
 * <p>Di produksi, komponen ini digantikan gateway nyata (APISIX/Kong/Envoy)
 * yang memanggil Flow Engine via gRPC/HTTP. Pada MVP peran gateway dijalankan
 * Control Plane agar alur end-to-end dapat diuji tanpa infrastruktur tambahan.
 *
 * <p>Tanggung jawab yang sama dengan gateway:
 * <ol>
 *   <li>Routing berdasarkan method + path.</li>
 *   <li>Rate limit per API per menit.</li>
 *   <li>IP whitelist sesuai kebijakan API.</li>
 *   <li>Autentikasi API key terhadap consumer terdaftar.</li>
 *   <li>Meneruskan ke Flow Engine dengan versi aktif environment.</li>
 *   <li>Mencatat log dan metrik.</li>
 * </ol>
 */
@Service
public class GatewayService {

    private final ApiRepository apis;
    private final ApiVersionRepository versions;
    private final ReleaseBundleRepository releases;
    private final ConsumerRepository consumers;
    private final ApiService apiService;
    private final CompileService compileService;
    private final SecretService secrets;
    private final TemplateInvokerService templateInvoker;
    private final ObservabilityService observability;
    private final FlowEngine engine;
    private final JsonStore json;
    private final RateLimiter rateLimiter = new RateLimiter();

    public GatewayService(ApiRepository apis,
                          ApiVersionRepository versions,
                          ReleaseBundleRepository releases,
                          ConsumerRepository consumers,
                          ApiService apiService,
                          CompileService compileService,
                          SecretService secrets,
                          TemplateInvokerService templateInvoker,
                          ObservabilityService observability,
                          FlowEngine engine,
                          JsonStore json) {
        this.apis = apis;
        this.versions = versions;
        this.releases = releases;
        this.consumers = consumers;
        this.apiService = apiService;
        this.compileService = compileService;
        this.secrets = secrets;
        this.templateInvoker = templateInvoker;
        this.observability = observability;
        this.engine = engine;
        this.json = json;
    }

    /**
     * Proses request masuk seperti gateway.
     *
     * @return map berisi {@code status}, {@code headers}, {@code body}, dan {@code traceId}
     */
    public Map<String, Object> handle(String method, String path, String environment,
                                      Map<String, String> headers, Map<String, String> query,
                                      Object body) {
        String env = environment == null || environment.isBlank() ? "production" : environment;
        long started = System.currentTimeMillis();
        String normalizedPath = normalize(path);
        String normalizedMethod = method == null ? "GET" : method.toUpperCase(Locale.ROOT);

        Optional<ApiEntity> found = apis.findByPathAndMethod(normalizedPath, normalizedMethod);
        if (found.isEmpty()) {
            return notFound(method, path);
        }
        ApiEntity api = found.get();
        if (api.disabled) {
            return error(api, env, 503, "API_DISABLED", "API sedang dinonaktifkan", started);
        }

        Map<String, Object> policy = apiService.policyOf(api);

        // rate limit
        int rateLimit = intPolicy(policy, "rateLimitPerMinute", 0);
        if (rateLimit > 0 && !rateLimiter.allow(api.id, env, rateLimit)) {
            return error(api, env, 429, "RATE_LIMITED",
                    "Batas " + rateLimit + " request/menit terlampaui", started);
        }

        // IP whitelist
        List<String> whitelist = stringListPolicy(policy, "ipWhitelist");
        if (!whitelist.isEmpty()) {
            String clientIp = header(headers, "X-Forwarded-For");
            if (clientIp == null) {
                clientIp = header(headers, "X-Real-IP");
            }
            if (clientIp == null || whitelist.stream().noneMatch(clientIp::startsWith)) {
                return error(api, env, 403, "IP_NOT_ALLOWED", "Alamat IP tidak diizinkan", started);
            }
        }

        // autentikasi
        String authType = String.valueOf(policy.getOrDefault("auth", "none"));
        if (!"none".equalsIgnoreCase(authType)) {
            String provided = credentialOf(headers);
            if (provided == null || provided.isBlank()) {
                return error(api, env, 401, "UNAUTHORIZED",
                        "Autentikasi " + authType + " diperlukan", started);
            }
            String credential = ApiKeys.normalize(provided);
            Consumer consumer = authenticateConsumer(credential);
            if (consumer == null) {
                return error(api, env, 401, "UNAUTHORIZED",
                        "API key tidak dikenal atau tidak aktif", started);
            }
            if (!consumerAllows(consumer, api.id)) {
                return error(api, env, 403, "FORBIDDEN",
                        "Consumer tidak berhak mengakses API ini", started);
            }
        }

        String activeVersionId = activeVersionOf(api, env);
        if (activeVersionId == null) {
            return error(api, env, 503, "NO_ACTIVE_VERSION",
                    "Belum ada versi yang dideploy pada environment " + env, started);
        }
        ApiVersion version = versions.findById(activeVersionId).orElse(null);
        if (version == null) {
            return error(api, env, 503, "VERSION_MISSING", "Versi aktif tidak ditemukan", started);
        }

        // gunakan bundle immutable bila ada (FR-DEP-01)
        ReleaseBundle bundle = releases
                .findFirstByApiIdAndVersionIdOrderByCreatedAtDesc(api.id, version.id)
                .orElse(null);
        String flowJson = bundle != null ? bundle.flowJson : version.flowJson;
        String bytecodeJson = bundle != null ? bundle.bytecodeJson : version.bytecodeJson;
        String mocksJson = bundle != null ? bundle.mocksJson : version.mocksJson;
        String seedsJson = bundle != null ? bundle.seedsJson : version.seedsJson;

        FlowDefinition flow = json.read(flowJson, FlowDefinition.class, null);
        if (flow == null) {
            return error(api, env, 500, "FLOW_INVALID", "Definisi flow tidak dapat dibaca", started);
        }
        flow = flow.withId(version.id);

        // Runtime memakai mock dan seed default versi. Tanpa seed, flow yang
        // membaca tabel sandbox akan gagal pada setiap request gateway.
        List<ExecutionRequest.HttpMock> mocks = toMocks(json.readMapList(mocksJson));
        List<ExecutionRequest.DbSeed> seeds = toSeeds(json.readMapList(seedsJson));

        ExecutionRequest request = new ExecutionRequest(
                api.id,
                "v" + version.versionNumber + "-" + version.id,
                normalizedMethod,
                normalizedPath,
                headers,
                query,
                body,
                false,
                mocks,
                seeds,
                null);

        Map<String, io.apistudio.engine.java.CompiledArtifact> artifacts =
                compileService.toArtifacts(bytecodeJson);

        FlowEngine.ExecutionOptions options = FlowEngine.ExecutionOptions
                .forVersion(api.id, request.flowVersion(), env)
                .withArtifacts(ArtifactResolver.of(artifacts))
                .withSecrets(secrets.resolverFor(env))
                .withTemplates((key, args, ctx) -> templateInvoker
                        .invokerFor(ctx, (subFlow, subCtx) -> subCtx == null
                                ? null : subCtx.get("templateResult"))
                        .invoke(key, args, ctx));

        ExecutionResult result = engine.execute(flow, request, options);
        long latency = System.currentTimeMillis() - started;

        observability.recordRequest(api.id, version.id, env, normalizedMethod, normalizedPath,
                result.status(), latency, result.traceId(), result.trace(),
                result.status() >= 500 ? String.valueOf(result.body()) : null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", result.status());
        response.put("headers", result.headers());
        response.put("body", result.body());
        response.put("traceId", result.traceId());
        response.put("latencyMs", latency);
        response.put("versionId", version.id);
        response.put("versionNumber", version.versionNumber);
        response.put("apiId", api.id);
        return response;
    }

    /** Cari consumer aktif yang cocok dengan kredensial. */
    private Consumer authenticateConsumer(String credential) {
        if (credential == null || credential.isBlank()) {
            return null;
        }
        for (Consumer consumer : consumers.findAll()) {
            if (consumer.enabled && ApiKeys.matches(credential, consumer.apiKeyHash)) {
                return consumer;
            }
        }
        return null;
    }

    /** Daftar apiId kosong berarti consumer boleh mengakses semua API. */
    private static boolean consumerAllows(Consumer consumer, String apiId) {
        String allowed = consumer.allowedApiIds;
        if (allowed == null || allowed.isBlank()) {
            return true;
        }
        for (String candidate : allowed.split(",")) {
            if (candidate.trim().equals(apiId)) {
                return true;
            }
        }
        return false;
    }

    /** Ambil kredensial dari header yang didukung. */
    private static String credentialOf(Map<String, String> headers) {
        String apiKey = header(headers, "X-API-Key");
        return apiKey != null ? apiKey : header(headers, "Authorization");
    }

    /** Pencarian header tidak peka huruf besar/kecil. */
    private static String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String activeVersionOf(ApiEntity api, String env) {
        String active = PublishService.activeVersionOf(api.activeVersions, env);
        if (active != null) {
            return active;
        }
        // fallback: versi published terbaru (memudahkan demo di environment baru)
        return versions.findByApiIdAndStatusOrderByVersionNumberDesc(api.id, ApiVersion.Status.PUBLISHED)
                .stream().findFirst().map(v -> v.id).orElse(null);
    }

    private Map<String, Object> notFound(String method, String path) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", 404);
        response.put("headers", Map.of("Content-Type", "application/json"));
        response.put("body", Map.of("error", "API_NOT_FOUND",
                "message", "Tidak ada API terdaftar untuk " + method + " " + path));
        return response;
    }

    private Map<String, Object> error(ApiEntity api, String env, int status, String code,
                                      String message, long started) {
        long latency = System.currentTimeMillis() - started;
        observability.recordRequest(api.id, null, env, api.method, api.path, status, latency,
                java.util.UUID.randomUUID().toString(), List.of(), message);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status);
        response.put("headers", Map.of("Content-Type", "application/json"));
        response.put("body", Map.of("error", code, "message", message));
        response.put("latencyMs", latency);
        return response;
    }

    private static String normalize(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String trimmed = path.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private static int intPolicy(Map<String, Object> policy, String key, int fallback) {
        Object value = policy.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static List<String> stringListPolicy(Map<String, Object> policy, String key) {
        Object value = policy.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /** Konversi mock dari kolom JSON versi/release ke kontrak engine. */
    private static List<ExecutionRequest.HttpMock> toMocks(List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<ExecutionRequest.HttpMock> mocks = new ArrayList<>();
        for (Map<String, Object> map : raw) {
            if (map == null) {
                continue;
            }
            String urlPattern = map.get("urlPattern") == null ? null : String.valueOf(map.get("urlPattern"));
            if (urlPattern == null || urlPattern.isBlank()) {
                continue;
            }
            Integer status = map.get("status") instanceof Number n ? n.intValue() : null;
            Long delay = map.get("delayMs") instanceof Number n ? n.longValue() : null;
            ExecutionRequest.HttpMock.Mode mode = ExecutionRequest.HttpMock.Mode.MOCK;
            Object rawMode = map.get("mode");
            if (rawMode != null) {
                try {
                    mode = ExecutionRequest.HttpMock.Mode.valueOf(
                            String.valueOf(rawMode).toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    mode = ExecutionRequest.HttpMock.Mode.MOCK;
                }
            }
            Map<String, String> mockHeaders = new LinkedHashMap<>();
            if (map.get("headers") instanceof Map<?, ?> headerMap) {
                headerMap.forEach((k, v) -> mockHeaders.put(String.valueOf(k),
                        v == null ? null : String.valueOf(v)));
            }
            mocks.add(new ExecutionRequest.HttpMock(urlPattern, status, mockHeaders,
                    map.get("body"), delay, mode));
        }
        return mocks;
    }

    /** Konversi seed dari kolom JSON versi/release ke kontrak engine. */
    @SuppressWarnings("unchecked")
    private static List<ExecutionRequest.DbSeed> toSeeds(List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<ExecutionRequest.DbSeed> seeds = new ArrayList<>();
        for (Map<String, Object> map : raw) {
            if (map == null) {
                continue;
            }
            String table = map.get("table") == null ? null : String.valueOf(map.get("table"));
            if (table == null || table.isBlank()) {
                continue;
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            if (map.get("rows") instanceof List<?> list) {
                for (Object row : list) {
                    if (row instanceof Map<?, ?> rowMap) {
                        rows.add((Map<String, Object>) rowMap);
                    }
                }
            }
            seeds.add(new ExecutionRequest.DbSeed(table, rows));
        }
        return seeds;
    }

    /**
     * Rate limiter in-memory per API per environment (fixed window per menit).
     *
     * <p>Di produksi, tanggung jawab ini ada di gateway (plugin APISIX/Kong).
     * Implementasi in-memory hanya berlaku per instance Control Plane.
     */
    static final class RateLimiter {

        private final Map<String, long[]> windows = new ConcurrentHashMap<>();

        boolean allow(String apiId, String environment, int limitPerMinute) {
            long minute = System.currentTimeMillis() / 60_000;
            long[] slot = windows.computeIfAbsent(apiId + "|" + environment,
                    key -> new long[]{minute, 0});
            synchronized (slot) {
                if (slot[0] != minute) {
                    slot[0] = minute;
                    slot[1] = 0;
                }
                slot[1]++;
                return slot[1] <= limitPerMinute;
            }
        }

        /** Reset jendela untuk pengujian. */
        void reset() {
            windows.clear();
        }
    }
}
