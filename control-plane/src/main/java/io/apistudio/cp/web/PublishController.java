package io.apistudio.cp.web;

import io.apistudio.cp.domain.Approval;
import io.apistudio.cp.domain.Deployment;
import io.apistudio.cp.domain.ReleaseBundle;
import io.apistudio.cp.security.AuthService;
import io.apistudio.cp.security.CurrentUser;
import io.apistudio.cp.security.Permission;
import io.apistudio.cp.service.JsonStore;
import io.apistudio.cp.service.PublishService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Publish, approval, deploy, canary, dan rollback (BRD FR-DEP-01..08).
 */
@RestController
@RequestMapping("/api")
public class PublishController {

    private final PublishService publishService;
    private final AuthService auth;
    private final JsonStore json;

    public PublishController(PublishService publishService, AuthService auth, JsonStore json) {
        this.publishService = publishService;
        this.auth = auth;
        this.json = json;
    }

    /** Checklist pra-publish. */
    @GetMapping("/versions/{versionId}/preflight")
    public Map<String, Object> preflight(HttpServletRequest request,
                                        @PathVariable String versionId,
                                        @RequestParam(required = false, defaultValue = "dev")
                                        String environment) {
        auth.require(request, Permission.API_READ);
        return publishService.preflight(versionId, environment);
    }

    /** Ajukan approval publish. */
    @PostMapping("/versions/{versionId}/request-publish")
    public Map<String, Object> requestPublish(HttpServletRequest request,
                                              @PathVariable String versionId,
                                              @RequestBody(required = false) Map<String, Object> body) {
        CurrentUser actor = auth.require(request, Permission.PUBLISH_REQUEST);
        Approval approval = publishService.requestPublish(versionId,
                body == null ? "dev" : stringOf(body.get("environment")),
                body == null ? null : stringOf(body.get("reason")),
                actor);
        return approvalView(approval);
    }

    /** Daftar approval; {@code all=true} menampilkan riwayat. */
    @GetMapping("/approvals")
    public List<Map<String, Object>> approvals(HttpServletRequest request,
                                               @RequestParam(required = false, defaultValue = "false")
                                               boolean all) {
        auth.require(request, Permission.API_READ);
        return publishService.approvals(all).stream().map(this::approvalView).toList();
    }

    /** Setujui/tolak approval (FR-DEP-02). */
    @PostMapping("/approvals/{approvalId}/decide")
    public Map<String, Object> decide(HttpServletRequest request,
                                      @PathVariable String approvalId,
                                      @RequestBody Map<String, Object> body) {
        CurrentUser actor = auth.require(request, Permission.PUBLISH_APPROVE);
        boolean approve = body.get("approve") instanceof Boolean value ? value
                : "APPROVED".equalsIgnoreCase(stringOf(body.get("decision")));
        Approval approval = publishService.decide(approvalId, approve,
                stringOf(body.get("note")), actor);
        return approvalView(approval);
    }

    /** Publish versi menjadi release bundle immutable (FR-DEP-01). */
    @PostMapping("/versions/{versionId}/publish")
    public Map<String, Object> publish(HttpServletRequest request,
                                       @PathVariable String versionId,
                                       @RequestBody(required = false) Map<String, Object> body) {
        CurrentUser actor = auth.require(request, Permission.PUBLISH_REQUEST);
        ReleaseBundle bundle = publishService.publish(versionId,
                body == null ? "dev" : stringOf(body.get("environment")),
                body == null ? null : stringOf(body.get("approvalId")),
                actor);
        return releaseView(bundle);
    }

    /** Riwayat release satu API. */
    @GetMapping("/apis/{apiId}/releases")
    public List<Map<String, Object>> releases(HttpServletRequest request, @PathVariable String apiId) {
        auth.require(request, Permission.API_READ);
        return publishService.releasesOf(apiId).stream().map(this::releaseView).toList();
    }

    /** Deploy release ke environment (FR-DEP-03/04/07). */
    @PostMapping("/releases/{releaseId}/deploy")
    public Map<String, Object> deploy(HttpServletRequest request,
                                      @PathVariable String releaseId,
                                      @RequestBody Map<String, Object> body) {
        CurrentUser actor = auth.require(request, Permission.DEPLOY);
        Deployment deployment = publishService.deploy(releaseId,
                stringOf(body.get("environment")),
                stringOf(body.get("strategy")),
                body.get("canaryPercent") instanceof Number n ? n.intValue() : null,
                actor);
        return deploymentView(deployment);
    }

    /** Ubah bobot canary (FR-DEP-06). */
    @PutMapping("/deployments/{deploymentId}/canary")
    public Map<String, Object> updateCanary(HttpServletRequest request,
                                            @PathVariable String deploymentId,
                                            @RequestBody Map<String, Object> body) {
        CurrentUser actor = auth.require(request, Permission.DEPLOY);
        int percent = body.get("canaryPercent") instanceof Number n ? n.intValue() : 10;
        return deploymentView(publishService.updateCanary(deploymentId, percent, actor));
    }

    /** Riwayat deployment satu API. */
    @GetMapping("/apis/{apiId}/deployments")
    public List<Map<String, Object>> deployments(HttpServletRequest request,
                                                 @PathVariable String apiId,
                                                 @RequestParam(required = false) String environment) {
        auth.require(request, Permission.API_READ);
        return publishService.deploymentsOf(apiId, environment).stream()
                .map(this::deploymentView).toList();
    }

    /** Seluruh deployment terbaru. */
    @GetMapping("/deployments")
    public List<Map<String, Object>> allDeployments(HttpServletRequest request) {
        auth.require(request, Permission.OBSERVE);
        return publishService.allDeployments().stream().map(this::deploymentView).toList();
    }

    /** Rollback ke versi sebelumnya (FR-DEP-05, UC-06). */
    @PostMapping("/apis/{apiId}/rollback")
    public Map<String, Object> rollback(HttpServletRequest request,
                                        @PathVariable String apiId,
                                        @RequestBody Map<String, Object> body) {
        CurrentUser actor = auth.require(request, Permission.ROLLBACK);
        return deploymentView(publishService.rollback(apiId, stringOf(body.get("environment")), actor));
    }

    // ------------------------------------------------------------- view

    private Map<String, Object> approvalView(Approval approval) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("id", approval.id);
        view.put("apiId", approval.apiId);
        view.put("versionId", approval.versionId);
        view.put("environment", approval.environment);
        view.put("status", approval.status.name());
        view.put("reason", approval.reason);
        view.put("flowDiff", json.readAny(approval.flowDiffJson));
        view.put("javaDiff", json.readAny(approval.javaDiffJson));
        view.put("requestedBy", approval.requestedBy);
        view.put("createdAt", approval.createdAt);
        view.put("decidedBy", approval.decidedBy);
        view.put("decidedAt", approval.decidedAt);
        view.put("decisionNote", approval.decisionNote);
        return view;
    }

    private Map<String, Object> releaseView(ReleaseBundle bundle) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("id", bundle.id);
        view.put("apiId", bundle.apiId);
        view.put("versionId", bundle.versionId);
        view.put("versionNumber", bundle.versionNumber);
        view.put("artifactHash", bundle.artifactHash);
        view.put("createdAt", bundle.createdAt);
        view.put("createdBy", bundle.createdBy);
        view.put("approvalId", bundle.approvalId);
        view.put("testSummary", json.readAny(bundle.testSummaryJson));
        view.put("hasBytecode", !json.readMap(bundle.bytecodeJson).isEmpty());
        view.put("seedCount", json.readMapList(bundle.seedsJson).size());
        return view;
    }

    private Map<String, Object> deploymentView(Deployment deployment) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("id", deployment.id);
        view.put("apiId", deployment.apiId);
        view.put("versionId", deployment.versionId);
        view.put("releaseId", deployment.releaseId);
        view.put("environment", deployment.environment);
        view.put("status", deployment.status.name());
        view.put("strategy", deployment.strategy.name());
        view.put("canaryPercent", deployment.canaryPercent);
        view.put("versionNumber", deployment.versionNumber);
        view.put("readiness", json.readAny(deployment.readinessJson));
        view.put("message", deployment.message);
        view.put("createdAt", deployment.createdAt);
        view.put("createdBy", deployment.createdBy);
        view.put("activatedAt", deployment.activatedAt);
        view.put("drainedAt", deployment.drainedAt);
        return view;
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
