package io.apistudio.cp.service;

import io.apistudio.contracts.flow.FlowDefinition;
import io.apistudio.cp.domain.ApiEntity;
import io.apistudio.cp.domain.ApiVersion;
import io.apistudio.cp.domain.Approval;
import io.apistudio.cp.domain.Deployment;
import io.apistudio.cp.domain.Environment;
import io.apistudio.cp.domain.ReleaseBundle;
import io.apistudio.cp.repo.ApiRepository;
import io.apistudio.cp.repo.ApiVersionRepository;
import io.apistudio.cp.repo.ApprovalRepository;
import io.apistudio.cp.repo.DeploymentRepository;
import io.apistudio.cp.repo.EnvironmentRepository;
import io.apistudio.cp.repo.ReleaseBundleRepository;
import io.apistudio.cp.security.CurrentUser;
import io.apistudio.engine.validation.FlowValidator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Publish, approval, deploy, canary, dan rollback (BRD FR-DEP-01..08).
 *
 * <p>Alur sesuai BRD Bagian 5.4:
 * <pre>
 * DRAFT --preflight--> [validasi]+[kompilasi]+[test suite]+[approval?]
 *       --publish----> ReleaseBundle immutable (artifactHash)
 *       --deploy-----> Deployment ACTIVE (DIRECT/CANARY), versi lama DRAINING
 *       --rollback---> Deployment sebelumnya ACTIVE kembali
 * </pre>
 *
 * <p>Prinsip yang dijaga:
 * <ul>
 *   <li>Bundle immutable: seluruh isi disalin saat publish.</li>
 *   <li>Aktivasi atomik: semua instance harus siap sebelum routing dialihkan (FR-DEP-07).</li>
 *   <li>Versi lama di-drain, bukan diputus (FR-DEP-04).</li>
 *   <li>Rollback tersedia karena versi lama tidak dihapus.</li>
 * </ul>
 */
@Service
public class PublishService {

    private final ApiRepository apis;
    private final ApiVersionRepository versions;
    private final ReleaseBundleRepository releases;
    private final DeploymentRepository deployments;
    private final ApprovalRepository approvals;
    private final EnvironmentRepository environments;
    private final ApiService apiService;
    private final TestRunnerService testRunner;
    private final FlowValidator validator;
    private final JsonStore json;
    private final AuditService audit;

    public PublishService(ApiRepository apis,
                          ApiVersionRepository versions,
                          ReleaseBundleRepository releases,
                          DeploymentRepository deployments,
                          ApprovalRepository approvals,
                          EnvironmentRepository environments,
                          ApiService apiService,
                          TestRunnerService testRunner,
                          FlowValidator validator,
                          JsonStore json,
                          AuditService audit) {
        this.apis = apis;
        this.versions = versions;
        this.releases = releases;
        this.deployments = deployments;
        this.approvals = approvals;
        this.environments = environments;
        this.apiService = apiService;
        this.testRunner = testRunner;
        this.validator = validator;
        this.json = json;
        this.audit = audit;
    }

    // -------------------------------------------------------- preflight

    /**
     * Checklist pra-publish: validasi, kompilasi, test suite, approval.
     *
     * <p>Dikembalikan sebagai daftar centang agar Studio dapat menampilkan
     * persis apa yang menghalangi publish.
     */
    public Map<String, Object> preflight(String versionId, String environment) {
        ApiVersion version = apiService.requireVersion(versionId);
        ApiEntity api = apiService.requireApi(version.apiId);
        String env = environment == null || environment.isBlank() ? "dev" : environment;
        Environment environmentEntity = environments.findByCode(env).orElse(null);

        List<Map<String, Object>> checks = new ArrayList<>();
        boolean ready = true;

        // 1. validasi flow
        FlowDefinition flow = apiService.flowOf(version);
        FlowValidator.Report report = validator.validate(flow);
        boolean validationOk = report.valid();
        ready &= validationOk;
        checks.add(check("validasi", validationOk,
                validationOk
                        ? "Flow valid (" + flow.nodes().size() + " node)"
                        : report.errorCount() + " error validasi harus diperbaiki",
                Map.of("errors", report.errorCount(), "warnings", report.warningCount(),
                        "issues", report.toMap().get("issues"))));

        // 2. kompilasi kode Java (bila ada node Java Code)
        boolean hasJavaNodes = flow.nodes().stream()
                .anyMatch(n -> n.type() == io.apistudio.contracts.flow.NodeType.JAVA_CODE);
        boolean bytecodePresent = !json.readMap(version.bytecodeJson).isEmpty();
        boolean compilationOk = !hasJavaNodes || bytecodePresent;
        ready &= compilationOk;
        checks.add(check("kompilasi", compilationOk,
                !hasJavaNodes ? "Tidak ada node Java Code"
                        : compilationOk ? "Bytecode tersedia untuk semua node"
                        : "Node Java Code belum dikompilasi (klik Check)",
                Map.of("hasJavaNodes", hasJavaNodes, "bytecodePresent", bytecodePresent)));

        // 3. test suite
        Map<String, Object> suite = testRunner.lastSuiteStatus(api.id, version.id);
        long failing = ((Number) suite.getOrDefault("recentFailures", 1L)).longValue();
        int totalCases = ((Number) suite.getOrDefault("totalCases", 0)).intValue();
        boolean testsOk = totalCases == 0 || failing == 0;
        ready &= testsOk;
        checks.add(check("test", testsOk,
                totalCases == 0 ? "Belum ada test case (tidak memblokir)"
                        : testsOk ? "Semua test blocking lulus"
                        : failing + " test terakhir gagal",
                suite));

        // 4. approval
        boolean requiresApproval = environmentEntity != null && environmentEntity.requiresApproval;
        Approval pending = approvals.findFirstByVersionIdAndEnvironmentAndStatus(
                version.id, env, Approval.Status.PENDING).orElse(null);
        Approval approved = approvals.findFirstByVersionIdAndEnvironmentAndStatus(
                version.id, env, Approval.Status.APPROVED).orElse(null);
        boolean approvalOk = !requiresApproval || approved != null;
        ready &= approvalOk;
        checks.add(check("approval", approvalOk,
                !requiresApproval ? "Environment tidak mewajibkan approval"
                        : approved != null ? "Sudah disetujui " + approved.decidedBy
                        : pending != null ? "Menunggu persetujuan reviewer"
                        : "Belum ada permintaan approval",
                Map.of("required", requiresApproval,
                        "pendingApprovalId", pending == null ? "" : pending.id,
                        "approvedApprovalId", approved == null ? "" : approved.id)));

        // 5. status versi
        boolean draft = version.isDraft();
        ready &= draft;
        checks.add(check("status", draft,
                draft ? "Versi berstatus DRAFT" : "Versi sudah " + version.status + " dan tidak dapat dipublish ulang",
                Map.of("status", version.status.name())));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ready", ready);
        result.put("versionId", version.id);
        result.put("versionLabel", version.label());
        result.put("apiId", api.id);
        result.put("apiName", api.name);
        result.put("environment", env);
        result.put("checks", checks);
        return result;
    }

    private static Map<String, Object> check(String name, boolean passed, String message, Object detail) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("passed", passed);
        item.put("status", passed ? "OK" : "BLOCKED");
        item.put("message", message);
        item.put("detail", detail);
        return item;
    }

    // --------------------------------------------------------- approval

    /** Ajukan permintaan persetujuan publish (FR-DEP-02). */
    @Transactional
    public Approval requestPublish(String versionId, String environment, String reason, CurrentUser actor) {
        ApiVersion version = apiService.requireVersion(versionId);
        ApiEntity api = apiService.requireApi(version.apiId);
        String env = environment == null || environment.isBlank() ? "dev" : environment;

        if (!version.isDraft()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Versi " + version.label() + " sudah " + version.status);
        }

        Approval existing = approvals.findFirstByVersionIdAndEnvironmentAndStatus(
                version.id, env, Approval.Status.PENDING).orElse(null);
        if (existing != null) {
            return existing;
        }

        Approval approval = new Approval(api.id, version.id, env, actor.username());
        approval.reason = reason;
        approval.flowDiffJson = json.write(buildFlowDiff(api.id, version));
        approval.javaDiffJson = json.write(buildJavaDiff(version));
        Approval saved = approvals.save(approval);

        audit.record(actor, "PUBLISH_REQUESTED", "Approval", saved.id,
                "Permintaan publish " + api.name + " " + version.label() + " ke " + env);
        return saved;
    }

    /** Setujui atau tolak permintaan publish. */
    @Transactional
    public Approval decide(String approvalId, boolean approve, String note, CurrentUser actor) {
        Approval approval = approvals.findById(approvalId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Approval tidak ditemukan"));
        if (!approval.isPending()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Approval sudah diputuskan: " + approval.status);
        }
        approval.status = approve ? Approval.Status.APPROVED : Approval.Status.REJECTED;
        approval.decidedAt = Instant.now();
        approval.decidedBy = actor.username();
        approval.decisionNote = note;
        Approval saved = approvals.save(approval);

        audit.record(actor, approve ? "PUBLISH_APPROVED" : "PUBLISH_REJECTED", "Approval", approvalId,
                (approve ? "Disetujui" : "Ditolak") + ": " + approval.environment);
        return saved;
    }

    public List<Approval> approvals(boolean all) {
        return all
                ? approvals.findAllByOrderByCreatedAtDesc()
                : approvals.findByStatusOrderByCreatedAtDesc(Approval.Status.PENDING);
    }

    // ---------------------------------------------------------- publish

    /**
     * Publish versi menjadi release bundle immutable (FR-DEP-01).
     *
     * <p>Menjalankan preflight; publish ditolak bila ada pemeriksaan yang gagal.
     */
    @Transactional
    public ReleaseBundle publish(String versionId, String environment, String approvalId, CurrentUser actor) {
        ApiVersion version = apiService.requireVersion(versionId);
        ApiEntity api = apiService.requireApi(version.apiId);
        String env = environment == null || environment.isBlank() ? "dev" : environment;

        Map<String, Object> preflight = preflight(versionId, env);
        if (!Boolean.TRUE.equals(preflight.get("ready"))) {
            List<String> blockers = new ArrayList<>();
            if (preflight.get("checks") instanceof List<?> checks) {
                for (Object item : checks) {
                    if (item instanceof Map<?, ?> map && Boolean.FALSE.equals(map.get("passed"))) {
                        blockers.add(String.valueOf(map.get("name")) + ": " + map.get("message"));
                    }
                }
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Preflight gagal - " + String.join("; ", blockers));
        }

        ReleaseBundle bundle = new ReleaseBundle();
        bundle.apiId = api.id;
        bundle.versionId = version.id;
        bundle.versionNumber = version.versionNumber;
        bundle.flowJson = version.flowJson;
        bundle.javaSourcesJson = version.javaSourcesJson;
        bundle.bytecodeJson = version.bytecodeJson;
        bundle.policyJson = version.policyJson;
        bundle.mocksJson = version.mocksJson;
        bundle.seedsJson = version.seedsJson;
        bundle.createdBy = actor.username();

        // approval: pakai yang diberikan, atau yang sudah APPROVED
        String resolvedApproval = approvalId;
        if (resolvedApproval == null || resolvedApproval.isBlank()) {
            resolvedApproval = approvals.findFirstByVersionIdAndEnvironmentAndStatus(
                            version.id, env, Approval.Status.APPROVED)
                    .map(a -> a.id).orElse(null);
        }
        bundle.approvalId = resolvedApproval;

        Map<String, Object> suite = testRunner.lastSuiteStatus(api.id, version.id);
        bundle.testSummaryJson = json.write(suite);
        bundle.artifactHash = hashBundle(bundle);

        ReleaseBundle saved = releases.save(bundle);

        version.status = ApiVersion.Status.PUBLISHED;
        version.publishedAt = Instant.now();
        version.publishedBy = actor.username();
        versions.save(version);

        audit.record(actor, "VERSION_PUBLISHED", "ReleaseBundle", saved.id,
                "Versi " + version.label() + " dipublish untuk " + api.name
                        + " (hash " + saved.artifactHash.substring(0, 12) + ")");
        return saved;
    }

    /**
     * Hash artifact: SHA-256 atas seluruh isi bundle.
     *
     * <p>Dipakai untuk memverifikasi bahwa semua instance menjalankan artifact
     * identik (FR-DEP-07).
     */
    private static String hashBundle(ReleaseBundle bundle) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String payload = nullSafe(bundle.flowJson) + "|" + nullSafe(bundle.bytecodeJson) + "|"
                    + nullSafe(bundle.policyJson) + "|" + nullSafe(bundle.mocksJson) + "|"
                    + nullSafe(bundle.seedsJson) + "|" + nullSafe(bundle.javaSourcesJson);
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Gagal menghitung hash bundle", e);
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    public List<ReleaseBundle> releasesOf(String apiId) {
        return releases.findByApiIdOrderByCreatedAtDesc(apiId);
    }

    // ------------------------------------------------------------ deploy

    /**
     * Deploy release ke environment (FR-DEP-03/04/07).
     *
     * <p>Langkah:
     * <ol>
     *   <li>Buat deployment PENDING.</li>
     *   <li>Periksa kesiapan seluruh instance environment (FR-DEP-07).</li>
     *   <li>Aktifkan deployment; deployment lama dipindah ke DRAINING.</li>
     *   <li>Set routing environment ke versi baru pada API.</li>
     * </ol>
     */
    @Transactional
    public Deployment deploy(String releaseId, String environment, String strategy, Integer canaryPercent,
                             CurrentUser actor) {
        ReleaseBundle release = releases.findById(releaseId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Release tidak ditemukan"));
        ApiEntity api = apiService.requireApi(release.apiId);
        String env = environment == null || environment.isBlank() ? "dev" : environment;
        Environment environmentEntity = environments.findByCode(env).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Environment tidak dikenal: " + env));

        Deployment.Strategy resolved = "CANARY".equalsIgnoreCase(strategy)
                ? Deployment.Strategy.CANARY
                : Deployment.Strategy.DIRECT;
        if (resolved == Deployment.Strategy.CANARY && !environmentEntity.canaryEnabled) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Canary tidak diaktifkan pada environment " + env);
        }
        int percent = resolved == Deployment.Strategy.DIRECT
                ? 100
                : Math.max(1, Math.min(canaryPercent == null ? 10 : canaryPercent, 100));

        Deployment deployment = new Deployment(api.id, release.versionId, release.id, env,
                release.versionNumber, actor.username());
        deployment.strategy = resolved;
        deployment.canaryPercent = percent;
        deployment.status = Deployment.Status.PENDING;

        // FR-DEP-07: seluruh instance harus siap sebelum routing dialihkan
        Map<String, Object> readiness = evaluateReadiness(environmentEntity, release);
        deployment.readinessJson = json.write(readiness);
        boolean allReady = Boolean.TRUE.equals(readiness.get("allReady"));
        if (!allReady) {
            deployment.status = Deployment.Status.FAILED;
            deployment.message = "Sebagian instance belum siap";
            Deployment failed = deployments.save(deployment);
            audit.record(actor, "DEPLOY_FAILED", "Deployment", failed.id,
                    "Deploy gagal: sebagian instance belum siap pada " + env);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Deploy dibatalkan: sebagian instance belum siap. Detail: " + readiness);
        }

        // versi lama dipindah ke DRAINING (menyelesaikan request berjalan)
        List<Deployment> active = deployments.findByApiIdAndEnvironmentAndStatus(
                api.id, env, Deployment.Status.ACTIVE);
        for (Deployment previous : active) {
            previous.status = Deployment.Status.DRAINING;
            previous.drainedAt = Instant.now();
            deployments.save(previous);
        }

        deployment.status = Deployment.Status.ACTIVE;
        deployment.activatedAt = Instant.now();
        deployment.message = resolved == Deployment.Strategy.CANARY
                ? "Aktif dengan canary " + percent + "%"
                : "Aktif langsung (100%)";
        Deployment saved = deployments.save(deployment);

        // routing environment -> versi baru
        api.activeVersions = setActiveVersion(api.activeVersions, env, release.versionId);
        api.updatedAt = Instant.now();
        api.updatedBy = actor.username();
        apis.save(api);

        audit.record(actor, "DEPLOY_ACTIVATED", "Deployment", saved.id,
                "Versi " + release.versionNumber + " aktif di " + env
                        + " (" + resolved + (resolved == Deployment.Strategy.CANARY ? " " + percent + "%" : "") + ")");
        return saved;
    }

    /**
     * Periksa kesiapan instance environment.
     *
     * <p>Pada MVP, kesiapan berarti instance terdaftar dan tidak kosong. Di
     * produksi, pemeriksaan ini menjadi health check nyata ke setiap instance
     * Flow Engine (FR-DEP-07).
     */
    private Map<String, Object> evaluateReadiness(Environment environment, ReleaseBundle release) {
        List<Map<String, Object>> instances = json.readMapList(environment.instancesJson);
        List<Map<String, Object>> details = new ArrayList<>();
        boolean allReady = true;

        if (instances.isEmpty()) {
            // environment tanpa instance terdaftar: anggap siap untuk MVP lokal
            details.add(Map.of("instance", "local", "ready", true,
                    "note", "Tidak ada instance terdaftar; memakai engine in-process"));
        } else {
            for (Map<String, Object> instance : instances) {
                boolean ready = !Boolean.FALSE.equals(instance.get("disabled"));
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("instance", String.valueOf(instance.getOrDefault("id", "unknown")));
                item.put("baseUrl", instance.getOrDefault("baseUrl", ""));
                item.put("zone", instance.getOrDefault("zone", ""));
                item.put("ready", ready);
                details.add(item);
                allReady &= ready;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allReady", allReady);
        result.put("artifactHash", release.artifactHash);
        result.put("instances", details);
        result.put("checkedAt", Instant.now().toString());
        return result;
    }

    /** Ubah canary percent untuk deployment aktif (FR-DEP-06). */
    @Transactional
    public Deployment updateCanary(String deploymentId, int canaryPercent, CurrentUser actor) {
        Deployment deployment = deployments.findById(deploymentId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Deployment tidak ditemukan"));
        if (deployment.status != Deployment.Status.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Hanya deployment ACTIVE yang dapat diubah bobotnya");
        }
        if (deployment.strategy != Deployment.Strategy.CANARY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Deployment ini memakai strategi DIRECT");
        }
        deployment.canaryPercent = Math.max(1, Math.min(canaryPercent, 100));
        deployment.message = "Canary " + deployment.canaryPercent + "%";
        Deployment saved = deployments.save(deployment);
        audit.record(actor, "CANARY_UPDATED", "Deployment", deploymentId,
                "Bobot canary diubah menjadi " + deployment.canaryPercent + "%");
        return saved;
    }

    /**
     * Rollback ke deployment sebelumnya (FR-DEP-05, UC-06).
     *
     * <p>Deployment lama diaktifkan kembali; yang sekarang dipindah ke DRAINING.
     * Versi sebelumnya tetap tersedia karena tidak pernah dihapus.
     */
    @Transactional
    public Deployment rollback(String apiId, String environment, CurrentUser actor) {
        ApiEntity api = apiService.requireApi(apiId);
        String env = environment == null || environment.isBlank() ? "dev" : environment;

        Deployment current = deployments.findFirstByApiIdAndEnvironmentAndStatusOrderByCreatedAtDesc(
                api.id, env, Deployment.Status.ACTIVE).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT, "Tidak ada deployment aktif pada environment " + env));

        // kandidat rollback: deployment lain yang pernah aktif
        Deployment previous = deployments.findByApiIdAndEnvironmentOrderByCreatedAtDesc(api.id, env).stream()
                .filter(d -> !d.id.equals(current.id))
                .filter(d -> d.status == Deployment.Status.DRAINING || d.status == Deployment.Status.INACTIVE)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Belum ada versi sebelumnya untuk rollback pada environment " + env));

        current.status = Deployment.Status.INACTIVE;
        current.message = "Digantikan rollback ke versi " + previous.versionNumber;
        deployments.save(current);

        previous.status = Deployment.Status.ACTIVE;
        previous.activatedAt = Instant.now();
        previous.message = "Rollback dari versi " + current.versionNumber;
        Deployment saved = deployments.save(previous);

        api.activeVersions = setActiveVersion(api.activeVersions, env, previous.versionId);
        api.updatedAt = Instant.now();
        api.updatedBy = actor.username();
        apis.save(api);

        audit.record(actor, "ROLLBACK", "Deployment", saved.id,
                "Rollback dari v" + current.versionNumber + " ke v" + previous.versionNumber + " di " + env);
        return saved;
    }

    /** Deployment untuk API, opsional difilter environment. */
    public List<Deployment> deploymentsOf(String apiId, String environment) {
        return environment == null || environment.isBlank()
                ? deployments.findByApiIdOrderByCreatedAtDesc(apiId)
                : deployments.findByApiIdAndEnvironmentOrderByCreatedAtDesc(apiId, environment);
    }

    /** Seluruh deployment terbaru. */
    public List<Deployment> allDeployments() {
        return deployments.findAllByOrderByCreatedAtDesc();
    }

    // --------------------------------------------------- diff untuk approval

    /** Diff ringkas flow terhadap versi published terakhir. */
    Map<String, Object> buildFlowDiff(String apiId, ApiVersion version) {
        FlowDefinition candidate = apiService.flowOf(version);
        FlowDefinition base = apiService.latestPublished(apiId)
                .map(apiService::flowOf)
                .orElse(FlowDefinition.empty("baru"));

        Set<String> baseNodes = new LinkedHashSet<>(base.nodes().stream().map(n -> n.id()).toList());
        Set<String> candidateNodes = new LinkedHashSet<>(candidate.nodes().stream().map(n -> n.id()).toList());

        List<String> added = candidateNodes.stream().filter(id -> !baseNodes.contains(id)).toList();
        List<String> removed = baseNodes.stream().filter(id -> !candidateNodes.contains(id)).toList();

        Set<String> baseEdges = new LinkedHashSet<>(base.edges().stream()
                .map(e -> e.from() + "->" + e.to() + ":" + e.portOrDefault()).toList());
        Set<String> candidateEdges = new LinkedHashSet<>(candidate.edges().stream()
                .map(e -> e.from() + "->" + e.to() + ":" + e.portOrDefault()).toList());

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("baseVersion", apiService.latestPublished(apiId).map(ApiVersion::label).orElse("(belum ada)"));
        diff.put("candidateVersion", version.label());
        diff.put("nodesAdded", added);
        diff.put("nodesRemoved", removed);
        diff.put("edgesAdded", candidateEdges.stream().filter(e -> !baseEdges.contains(e)).toList());
        diff.put("edgesRemoved", baseEdges.stream().filter(e -> !candidateEdges.contains(e)).toList());
        diff.put("nodeCountBefore", base.nodes().size());
        diff.put("nodeCountAfter", candidate.nodes().size());
        return diff;
    }

    /** Diff ringkas kode Java terhadap versi published terakhir. */
    Map<String, Object> buildJavaDiff(ApiVersion version) {
        Map<String, Object> candidate = json.readMap(version.javaSourcesJson);
        Map<String, Object> base = apiService.latestPublished(version.apiId)
                .map(apiService::javaSourcesOf)
                .orElse(Map.of());

        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();

        for (String nodeId : candidate.keySet()) {
            if (!base.containsKey(nodeId)) {
                added.add(nodeId);
            } else if (!json.write(base.get(nodeId)).equals(json.write(candidate.get(nodeId)))) {
                changed.add(nodeId);
            }
        }
        for (String nodeId : base.keySet()) {
            if (!candidate.containsKey(nodeId)) {
                removed.add(nodeId);
            }
        }

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("nodesAdded", added);
        diff.put("nodesRemoved", removed);
        diff.put("nodesChanged", changed);
        return diff;
    }

    // --------------------------------------------------------------- util

    /** Baca versi aktif untuk environment dari kolom {@code env=versionId;...}. */
    public static String activeVersionOf(String activeVersions, String environment) {
        if (activeVersions == null || activeVersions.isBlank() || environment == null) {
            return null;
        }
        for (String entry : activeVersions.split(";")) {
            String[] parts = entry.split("=", 2);
            if (parts.length == 2 && parts[0].trim().equals(environment)) {
                String value = parts[1].trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    /** Tulis versi aktif untuk environment. */
    static String setActiveVersion(String activeVersions, String environment, String versionId) {
        Map<String, String> map = new LinkedHashMap<>();
        if (activeVersions != null && !activeVersions.isBlank()) {
            for (String entry : activeVersions.split(";")) {
                String[] parts = entry.split("=", 2);
                if (parts.length == 2) {
                    map.put(parts[0].trim(), parts[1].trim());
                }
            }
        }
        map.put(environment, versionId);
        StringBuilder sb = new StringBuilder();
        map.forEach((env, ver) -> {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(env).append('=').append(ver);
        });
        return sb.toString();
    }
}
