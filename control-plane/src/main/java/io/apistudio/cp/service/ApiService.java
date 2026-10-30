package io.apistudio.cp.service;

import io.apistudio.contracts.flow.FlowDefinition;
import io.apistudio.cp.domain.ApiEntity;
import io.apistudio.cp.domain.ApiGroup;
import io.apistudio.cp.domain.ApiVersion;
import io.apistudio.cp.repo.ApiGroupRepository;
import io.apistudio.cp.repo.ApiRepository;
import io.apistudio.cp.repo.ApiVersionRepository;
import io.apistudio.cp.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Manajemen API dan versinya (BRD FR-API-01/02/04/07).
 *
 * <p>Aturan penting:
 * <ul>
 *   <li>Path+method unik; duplikat ditolak dengan 409.</li>
 *   <li>Setiap API baru langsung mendapat versi DRAFT dengan node TRIGGER.</li>
 *   <li>Versi PUBLISHED immutable: perubahan harus lewat versi baru.</li>
 *   <li>Nomor versi monoton per API.</li>
 * </ul>
 */
@Service
public class ApiService {

    /** Method HTTP yang diizinkan. */
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD");

    private final ApiRepository apis;
    private final ApiVersionRepository versions;
    private final ApiGroupRepository groups;
    private final JsonStore json;
    private final AuditService audit;

    public ApiService(ApiRepository apis, ApiVersionRepository versions, ApiGroupRepository groups,
                      JsonStore json, AuditService audit) {
        this.apis = apis;
        this.versions = versions;
        this.groups = groups;
        this.json = json;
        this.audit = audit;
    }

    // ------------------------------------------------------------- grup

    public List<ApiGroup> groups() {
        return groups.findAllByOrderByNameAsc();
    }

    @Transactional
    public ApiGroup createGroup(String name, String description, String color, CurrentUser actor) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nama grup wajib diisi");
        }
        ApiGroup group = new ApiGroup(name.trim(), description, actor.username());
        if (color != null && !color.isBlank()) {
            group.color = color;
        }
        ApiGroup saved = groups.save(group);
        audit.record(actor, "GROUP_CREATED", "ApiGroup", saved.id,
                "Grup " + saved.name + " dibuat");
        return saved;
    }

    /** Grup bawaan bila pengguna tidak memilih (agar API selalu terkelompok). */
    @Transactional
    public ApiGroup defaultGroup(CurrentUser actor) {
        return groups.findAllByOrderByNameAsc().stream().findFirst()
                .orElseGet(() -> createGroup("Default", "Grup bawaan", "indigo", actor));
    }

    // -------------------------------------------------------------- API

    public List<ApiEntity> apis(String groupId, String search) {
        List<ApiEntity> all = groupId == null || groupId.isBlank()
                ? apis.findAllByOrderByNameAsc()
                : apis.findByGroupIdOrderByNameAsc(groupId);
        if (search == null || search.isBlank()) {
            return all;
        }
        String term = search.toLowerCase(Locale.ROOT);
        return all.stream()
                .filter(api -> api.name.toLowerCase(Locale.ROOT).contains(term)
                        || api.path.toLowerCase(Locale.ROOT).contains(term)
                        || (api.tags != null && api.tags.toLowerCase(Locale.ROOT).contains(term)))
                .toList();
    }

    public ApiEntity requireApi(String apiId) {
        return apis.findById(apiId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "API tidak ditemukan: " + apiId));
    }

    @Transactional
    public ApiEntity createApi(String groupId, String name, String path, String method,
                               String description, String tags, String policyJson, CurrentUser actor) {
        String normalizedPath = normalizePath(path);
        String normalizedMethod = normalizeMethod(method);
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nama API wajib diisi");
        }
        apis.findByPathAndMethod(normalizedPath, normalizedMethod).ifPresent(existing -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "API untuk " + normalizedMethod + " " + normalizedPath + " sudah ada");
        });

        String resolvedGroup = groupId == null || groupId.isBlank()
                ? defaultGroup(actor).id
                : groupId;

        ApiEntity api = new ApiEntity(resolvedGroup, name.trim(), normalizedPath, normalizedMethod,
                description, tags, actor.username());
        if (policyJson != null && !policyJson.isBlank()) {
            api.policyJson = policyJson;
        }
        ApiEntity saved = apis.save(api);

        // versi draft pertama dengan node TRIGGER (UC-01 langkah 2)
        ApiVersion draft = new ApiVersion(saved.id, 1, actor.username());
        draft.flowJson = json.write(FlowDefinition.withTrigger(saved.name));
        draft.policyJson = saved.policyJson;
        draft.changeNote = "Versi awal";
        versions.save(draft);

        audit.record(actor, "API_CREATED", "Api", saved.id,
                "API " + normalizedMethod + " " + normalizedPath + " dibuat");
        return saved;
    }

    @Transactional
    public ApiEntity updateApi(String apiId, String name, String path, String method,
                               String description, String tags, String policyJson,
                               Boolean disabled, CurrentUser actor) {
        ApiEntity api = requireApi(apiId);
        Map<String, Object> before = snapshot(api);

        if (name != null && !name.isBlank()) {
            api.name = name.trim();
        }
        if (path != null && !path.isBlank()) {
            String normalizedPath = normalizePath(path);
            String methodToCheck = method != null ? normalizeMethod(method) : api.method;
            apis.findByPathAndMethod(normalizedPath, methodToCheck).ifPresent(existing -> {
                if (!existing.id.equals(api.id)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "API untuk " + methodToCheck + " " + normalizedPath + " sudah ada");
                }
            });
            api.path = normalizedPath;
        }
        if (method != null && !method.isBlank()) {
            api.method = normalizeMethod(method);
        }
        if (description != null) {
            api.description = description;
        }
        if (tags != null) {
            api.tags = tags;
        }
        if (policyJson != null && !policyJson.isBlank()) {
            api.policyJson = policyJson;
        }
        if (disabled != null) {
            api.disabled = disabled;
        }
        api.updatedAt = Instant.now();
        api.updatedBy = actor.username();
        ApiEntity saved = apis.save(api);

        audit.record(actor, "API_UPDATED", "Api", saved.id,
                "API " + saved.method + " " + saved.path + " diperbarui",
                Map.of("before", before, "after", snapshot(saved)));
        return saved;
    }

    @Transactional
    public void deleteApi(String apiId, CurrentUser actor) {
        ApiEntity api = requireApi(apiId);
        versions.findByApiIdOrderByVersionNumberDesc(apiId).forEach(versions::delete);
        apis.delete(api);
        audit.record(actor, "API_DELETED", "Api", apiId,
                "API " + api.method + " " + api.path + " dihapus");
    }

    // ------------------------------------------------------------ versi

    public List<ApiVersion> versionsOf(String apiId) {
        requireApi(apiId);
        return versions.findByApiIdOrderByVersionNumberDesc(apiId);
    }

    public ApiVersion requireVersion(String versionId) {
        return versions.findById(versionId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Versi tidak ditemukan: " + versionId));
    }

    /** Versi draft terbaru, atau {@code null} bila tidak ada. */
    public ApiVersion latestDraft(String apiId) {
        return versions.findByApiIdAndStatusOrderByVersionNumberDesc(apiId, ApiVersion.Status.DRAFT)
                .stream().findFirst().orElse(null);
    }

    /** Versi published terbaru, atau {@code null}. */
    public Optional<ApiVersion> latestPublished(String apiId) {
        return versions.findByApiIdAndStatusOrderByVersionNumberDesc(apiId, ApiVersion.Status.PUBLISHED)
                .stream().findFirst();
    }

    /**
     * Buat versi draft baru.
     *
     * <p>Isi versi baru disalin dari versi terakhir agar perubahan bersifat
     * inkremental (developer tidak memulai dari kanvas kosong).
     */
    @Transactional
    public ApiVersion createVersion(String apiId, String changeNote, CurrentUser actor) {
        ApiEntity api = requireApi(apiId);
        int nextNumber = (int) versions.countByApiId(apiId) + 1;

        ApiVersion draft = new ApiVersion(apiId, nextNumber, actor.username());
        versions.findFirstByApiIdOrderByVersionNumberDesc(apiId).ifPresentOrElse(latest -> {
            draft.flowJson = latest.flowJson;
            draft.javaSourcesJson = latest.javaSourcesJson;
            draft.bytecodeJson = latest.bytecodeJson;
            draft.mocksJson = latest.mocksJson;
            draft.seedsJson = latest.seedsJson;
        }, () -> {
            draft.flowJson = json.write(FlowDefinition.withTrigger(api.name));
        });
        draft.policyJson = api.policyJson;
        draft.changeNote = changeNote;
        ApiVersion saved = versions.save(draft);

        audit.record(actor, "VERSION_CREATED", "ApiVersion", saved.id,
                "Draft v" + nextNumber + " dibuat untuk " + api.name);
        return saved;
    }

    /** Simpan flow pada versi DRAFT. */
    @Transactional
    public ApiVersion saveFlow(String versionId, FlowDefinition flow, CurrentUser actor) {
        ApiVersion version = requireMutableVersion(versionId);
        String before = version.flowJson;
        version.flowJson = json.write(flow);
        ApiVersion saved = versions.save(version);

        audit.record(actor, "FLOW_SAVED", "ApiVersion", versionId,
                "Flow disimpan (" + flow.nodes().size() + " node, " + flow.edges().size() + " edge)",
                Map.of("before", json.readAny(before), "after", json.readAny(saved.flowJson)));
        return saved;
    }

    /** Simpan kode Java per node pada versi DRAFT. */
    @Transactional
    public ApiVersion saveJavaSources(String versionId, Map<String, Object> javaSources, CurrentUser actor) {
        ApiVersion version = requireMutableVersion(versionId);
        String before = version.javaSourcesJson;
        version.javaSourcesJson = json.write(javaSources);
        ApiVersion saved = versions.save(version);

        audit.record(actor, "JAVA_SAVED", "ApiVersion", versionId,
                "Kode Java disimpan untuk " + javaSources.size() + " node",
                Map.of("nodes", javaSources.keySet()));
        return saved;
    }

    /** Simpan mock default versi (FR-SIM-04). */
    @Transactional
    public ApiVersion saveMocks(String versionId, List<Object> mocks, CurrentUser actor) {
        ApiVersion version = requireMutableVersion(versionId);
        version.mocksJson = json.write(mocks);
        return versions.save(version);
    }

    /** Simpan seed database sandbox default (FR-SIM-05). */
    @Transactional
    public ApiVersion saveSeeds(String versionId, List<Object> seeds, CurrentUser actor) {
        ApiVersion version = requireMutableVersion(versionId);
        version.seedsJson = json.write(seeds);
        return versions.save(version);
    }

    /** Versi harus DRAFT agar dapat diubah (immutability FR-API-04). */
    public ApiVersion requireMutableVersion(String versionId) {
        ApiVersion version = requireVersion(versionId);
        if (!version.isDraft()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Versi " + version.label() + " berstatus " + version.status
                            + " dan tidak dapat diubah. Buat versi baru.");
        }
        return version;
    }

    // -------------------------------------------------------- konversi JSON

    /** Flow dari versi, dengan fallback flow kosong. */
    public FlowDefinition flowOf(ApiVersion version) {
        FlowDefinition flow = json.read(version.flowJson, FlowDefinition.class, null);
        return flow == null ? FlowDefinition.empty("Flow") : flow;
    }

    /** Kode Java per node. */
    public Map<String, Object> javaSourcesOf(ApiVersion version) {
        return json.readMap(version.javaSourcesJson);
    }

    /** Mock default versi. */
    public List<Map<String, Object>> mocksOf(ApiVersion version) {
        return json.readMapList(version.mocksJson);
    }

    /** Seed default versi. */
    public List<Map<String, Object>> seedsOf(ApiVersion version) {
        return json.readMapList(version.seedsJson);
    }

    /** Kebijakan API sebagai map. */
    public Map<String, Object> policyOf(ApiEntity api) {
        return json.readMap(api.policyJson);
    }

    /** Snapshot ringkas API untuk audit diff. */
    public Map<String, Object> snapshot(ApiEntity api) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", api.name);
        map.put("path", api.path);
        map.put("method", api.method);
        map.put("description", api.description);
        map.put("tags", api.tags);
        map.put("disabled", api.disabled);
        map.put("policy", json.readAny(api.policyJson));
        return map;
    }

    // --------------------------------------------------------------- util

    static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Path API wajib diisi");
        }
        String trimmed = path.trim();
        String withSlash = trimmed.startsWith("/") ? trimmed : "/" + trimmed;
        // buang slash berlebih di akhir, kecuali root
        if (withSlash.length() > 1 && withSlash.endsWith("/")) {
            withSlash = withSlash.substring(0, withSlash.length() - 1);
        }
        return withSlash;
    }

    static String normalizeMethod(String method) {
        if (method == null || method.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Method API wajib diisi");
        }
        String upper = method.trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(upper)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Method tidak didukung: " + method + ". Pilihan: " + METHODS);
        }
        return upper;
    }
}
