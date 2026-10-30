package io.apistudio.cp.web;

import io.apistudio.cp.domain.AuditLog;
import io.apistudio.cp.domain.Consumer;
import io.apistudio.cp.domain.Environment;
import io.apistudio.cp.domain.RequestLog;
import io.apistudio.cp.repo.ConsumerRepository;
import io.apistudio.cp.security.ApiKeys;
import io.apistudio.cp.security.AuthService;
import io.apistudio.cp.security.CurrentUser;
import io.apistudio.cp.security.Permission;
import io.apistudio.cp.service.AuditService;
import io.apistudio.cp.service.EnvironmentService;
import io.apistudio.cp.service.JsonStore;
import io.apistudio.cp.service.ObservabilityService;
import io.apistudio.cp.service.SecretService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoint administrasi: environment, secret, consumer, audit, dan
 * observability (BRD FR-SEC-02..06, FR-API-05, Bagian 10).
 */
@RestController
@RequestMapping("/api")
public class AdminController {

    private final EnvironmentService environmentService;
    private final SecretService secrets;
    private final ObservabilityService observability;
    private final AuditService audit;
    private final ConsumerRepository consumers;
    private final AuthService auth;
    private final JsonStore json;

    public AdminController(EnvironmentService environmentService,
                           SecretService secrets,
                           ObservabilityService observability,
                           AuditService audit,
                           ConsumerRepository consumers,
                           AuthService auth,
                           JsonStore json) {
        this.environmentService = environmentService;
        this.secrets = secrets;
        this.observability = observability;
        this.audit = audit;
        this.consumers = consumers;
        this.auth = auth;
        this.json = json;
    }

    // ------------------------------------------------------- environment

    @GetMapping("/environments")
    public List<Map<String, Object>> environments(HttpServletRequest request) {
        auth.require(request, Permission.API_READ);
        return environmentService.all().stream().map(environmentService::view).toList();
    }

    @PutMapping("/environments/{code}")
    public Map<String, Object> updateEnvironment(HttpServletRequest request,
                                                 @PathVariable String code,
                                                 @RequestBody Map<String, Object> body) {
        CurrentUser actor = auth.require(request, Permission.ENVIRONMENT_WRITE);
        Environment environment = environmentService.update(code, body, actor);
        return environmentService.view(environment);
    }

    // ----------------------------------------------------------- secret

    @GetMapping("/secrets")
    public List<Map<String, Object>> listSecrets(HttpServletRequest request,
                                                 @RequestParam(required = false) String environment) {
        auth.require(request, Permission.SECRET_READ_REFERENCE);
        return secrets.list(environment).stream().map(secrets::view).toList();
    }

    @PostMapping("/secrets")
    public Map<String, Object> putSecret(HttpServletRequest request,
                                         @RequestBody Map<String, Object> body) {
        CurrentUser actor = auth.require(request, Permission.SECRET_WRITE);
        return secrets.view(secrets.put(
                stringOf(body.get("name")),
                stringOf(body.get("environment")),
                stringOf(body.get("value")),
                stringOf(body.get("description")),
                actor));
    }

    @DeleteMapping("/secrets/{secretId}")
    public Map<String, Object> deleteSecret(HttpServletRequest request, @PathVariable String secretId) {
        CurrentUser actor = auth.require(request, Permission.SECRET_WRITE);
        secrets.delete(secretId, actor);
        return Map.of("ok", true);
    }

    // --------------------------------------------------------- consumer

    @GetMapping("/consumers")
    public List<Map<String, Object>> listConsumers(HttpServletRequest request) {
        auth.require(request, Permission.API_READ);
        return consumers.findAll().stream().map(this::consumerView).toList();
    }

    /**
     * Buat consumer baru; API key hanya ditampilkan sekali pada respons ini.
     */
    @PostMapping("/consumers")
    public Map<String, Object> createConsumer(HttpServletRequest request,
                                              @RequestBody Map<String, Object> body) {
        CurrentUser actor = auth.require(request, Permission.API_WRITE);
        Consumer consumer = new Consumer(
                String.valueOf(body.getOrDefault("name", "Consumer Baru")),
                stringOf(body.get("description")),
                actor.username());

        String apiKey = ApiKeys.generate();
        consumer.apiKeyHash = ApiKeys.hash(apiKey);
        consumer.apiKeyHint = ApiKeys.hint(apiKey);
        consumer.allowedApiIds = String.valueOf(body.getOrDefault("allowedApiIds", ""));
        if (body.get("dailyQuota") instanceof Number n) {
            consumer.dailyQuota = n.longValue();
        }
        Consumer saved = consumers.save(consumer);

        Map<String, Object> view = consumerView(saved);
        // nilai asli hanya ditampilkan sekali, tidak pernah disimpan
        view.put("apiKey", apiKey);
        audit.record(actor, "CONSUMER_CREATED", "Consumer", saved.id,
                "Consumer " + saved.name + " dibuat");
        return view;
    }

    private Map<String, Object> consumerView(Consumer consumer) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", consumer.id);
        view.put("name", consumer.name);
        view.put("description", consumer.description);
        view.put("apiKeyHint", consumer.apiKeyHint);
        view.put("allowedApiIds", consumer.allowedApiIds);
        view.put("dailyQuota", consumer.dailyQuota);
        view.put("enabled", consumer.enabled);
        view.put("createdAt", consumer.createdAt);
        view.put("createdBy", consumer.createdBy);
        return view;
    }

    // ------------------------------------------------------------ audit

    @GetMapping("/audit")
    public List<Map<String, Object>> auditLogs(HttpServletRequest request,
                                               @RequestParam(required = false) String entityType,
                                               @RequestParam(required = false) String entityId) {
        auth.require(request, Permission.AUDIT_READ);
        List<AuditLog> logs = (entityType == null || entityType.isBlank())
                ? audit.recent()
                : audit.forEntity(entityType, entityId);
        return logs.stream().map(audit::view).toList();
    }

    // ---------------------------------------------------- observability

    @GetMapping("/observability/logs")
    public List<Map<String, Object>> logs(HttpServletRequest request,
                                          @RequestParam(required = false) String apiId) {
        auth.require(request, Permission.OBSERVE);
        List<RequestLog> logs = apiId == null || apiId.isBlank()
                ? observability.recentLogs()
                : observability.logsForApi(apiId);
        return logs.stream().map(log -> observability.logView(log, false)).toList();
    }

    @GetMapping("/observability/trace/{traceId}")
    public Map<String, Object> trace(HttpServletRequest request, @PathVariable String traceId) {
        auth.require(request, Permission.OBSERVE);
        RequestLog log = observability.byTraceId(traceId);
        if (log == null) {
            return Map.of("traceId", traceId, "found", false);
        }
        Map<String, Object> view = observability.logView(log, true);
        view.put("found", true);
        return view;
    }

    @GetMapping("/observability/metrics")
    public Map<String, Object> metrics(HttpServletRequest request,
                                       @RequestParam(required = false) String apiId,
                                       @RequestParam(required = false) String environment,
                                       @RequestParam(required = false, defaultValue = "60") int minutes) {
        auth.require(request, Permission.OBSERVE);
        return observability.series(apiId, environment, minutes);
    }

    @GetMapping("/observability/alerts")
    public List<Map<String, Object>> alerts(HttpServletRequest request,
                                            @RequestParam(required = false) String apiId,
                                            @RequestParam(required = false, defaultValue = "5")
                                            double errorRate,
                                            @RequestParam(required = false, defaultValue = "2000")
                                            long latency) {
        auth.require(request, Permission.OBSERVE);
        return observability.alerts(apiId, errorRate, latency);
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
