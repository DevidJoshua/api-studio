package io.apistudio.cp.web;

import io.apistudio.contracts.flow.FlowDefinition;
import io.apistudio.contracts.flow.NodeType;
import io.apistudio.cp.domain.ApiEntity;
import io.apistudio.cp.domain.ApiGroup;
import io.apistudio.cp.domain.ApiVersion;
import io.apistudio.cp.repo.ApiRepository;
import io.apistudio.cp.security.AuthService;
import io.apistudio.cp.security.CurrentUser;
import io.apistudio.cp.security.Permission;
import io.apistudio.cp.service.ApiService;
import io.apistudio.cp.service.CompileService;
import io.apistudio.cp.service.JsonStore;
import io.apistudio.cp.service.OpenApiService;
import io.apistudio.engine.validation.FlowValidator;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoint manajemen API, versi, flow, kode Java, dan dokumentasi
 * (BRD FR-API-01..07, FR-FLOW-01..07, FR-JAVA-01..03).
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final ApiService apiService;
    private final CompileService compileService;
    private final OpenApiService openApiService;
    private final FlowValidator validator;
    private final ApiRepository apis;
    private final AuthService auth;
    private final JsonStore json;

    public ApiController(ApiService apiService,
                         CompileService compileService,
                         OpenApiService openApiService,
                         FlowValidator validator,
                         ApiRepository apis,
                         AuthService auth,
                         JsonStore json) {
        this.apiService = apiService;
        this.compileService = compileService;
        this.openApiService = openApiService;
        this.validator = validator;
        this.apis = apis;
        this.auth = auth;
        this.json = json;
    }

    // -------------------------------------------------------------- grup

    @GetMapping("/groups")
    public List<Map<String, Object>> listGroups(HttpServletRequest request) {
        auth.require(request, Permission.API_READ);
        return apiService.groups().stream().map(this::groupView).toList();
    }

    @PostMapping("/groups")
    public Map<String, Object> createGroup(HttpServletRequest request,
                                           @RequestBody Map<String, Object> body) {
        CurrentUser actor = auth.require(request, Permission.API_WRITE);
        ApiGroup group = apiService.createGroup(
                stringOf(body.get("name")),
                stringOf(body.get("description")),
                stringOf(body.get("color")),
                actor);
        return groupView(group);
    }

    // --------------------------------------------------------------- API

    @GetMapping("/apis")
    public List<Map<String, Object>> listApis(HttpServletRequest request,
                                              @RequestParam(required = false) String groupId,
                                              @RequestParam(required = false) String search) {
        auth.require(request, Permission.API_READ);
        return apiService.apis(groupId, search).stream().map(this::apiView).toList();
    }

    @PostMapping("/apis")
    public Map<String, Object> createApi(HttpServletRequest request,
                                         @RequestBody Map<String, Object> body) {
        CurrentUser actor = auth.require(request, Permission.API_WRITE);
        ApiEntity api = apiService.createApi(
                stringOf(body.get("groupId")),
                stringOf(body.get("name")),
                stringOf(body.get("path")),
                stringOf(body.get("method")),
                stringOf(body.get("description")),
                stringOf(body.get("tags")),
                body.get("policyJson") == null ? null : json.write(body.get("policyJson")),
                actor);
        return detailView(api);
    }

    @GetMapping("/apis/{apiId}")
    public Map<String, Object> getApi(HttpServletRequest request, @PathVariable String apiId) {
        auth.require(request, Permission.API_READ);
        return detailView(apiService.requireApi(apiId));
    }

    @PutMapping("/apis/{apiId}")
    public Map<String, Object> updateApi(HttpServletRequest request,
                                         @PathVariable String apiId,
                                         @RequestBody Map<String, Object> body) {
        CurrentUser actor = auth.require(request, Permission.API_WRITE);
        ApiEntity api = apiService.updateApi(
                apiId,
                stringOf(body.get("name")),
                stringOf(body.get("path")),
                stringOf(body.get("method")),
                stringOf(body.get("description")),
                stringOf(body.get("tags")),
                body.get("policyJson") == null ? null : json.write(body.get("policyJson")),
                body.get("disabled") instanceof Boolean disabled ? disabled : null,
                actor);
        return detailView(api);
    }

    @DeleteMapping("/apis/{apiId}")
    public Map<String, Object> deleteApi(HttpServletRequest request, @PathVariable String apiId) {
        CurrentUser actor = auth.require(request, Permission.API_WRITE);
        apiService.deleteApi(apiId, actor);
        return Map.of("ok", true);
    }

    /** Dokumentasi OpenAPI (FR-API-06). */
    @GetMapping("/apis/{apiId}/openapi")
    public Map<String, Object> openApi(HttpServletRequest request,
                                       @PathVariable String apiId,
                                       @RequestParam(required = false) String versionId) {
        auth.require(request, Permission.API_READ);
        ApiEntity api = apiService.requireApi(apiId);
        ApiVersion version = versionId == null || versionId.isBlank()
                ? apiService.latestPublished(apiId).orElseGet(() ->
                        apiService.versionsOf(apiId).stream().findFirst().orElse(null))
                : apiService.requireVersion(versionId);
        FlowDefinition flow = version == null
                ? FlowDefinition.empty(api.name)
                : apiService.flowOf(version);
        return openApiService.generate(api, version, flow);
    }

    // -------------------------------------------------------------- versi

    @GetMapping("/apis/{apiId}/versions")
    public List<Map<String, Object>> listVersions(HttpServletRequest request,
                                                  @PathVariable String apiId) {
        auth.require(request, Permission.API_READ);
        return apiService.versionsOf(apiId).stream().map(this::versionView).toList();
    }

    @PostMapping("/apis/{apiId}/versions")
    public Map<String, Object> createVersion(HttpServletRequest request,
                                             @PathVariable String apiId,
                                             @RequestBody(required = false) Map<String, Object> body) {
        CurrentUser actor = auth.require(request, Permission.FLOW_WRITE);
        ApiVersion version = apiService.createVersion(apiId,
                body == null ? null : stringOf(body.get("changeNote")), actor);
        return versionView(version);
    }

    @GetMapping("/versions/{versionId}")
    public Map<String, Object> getVersion(HttpServletRequest request, @PathVariable String versionId) {
        auth.require(request, Permission.FLOW_READ);
        ApiVersion version = apiService.requireVersion(versionId);
        Map<String, Object> view = versionView(version);
        view.put("flow", apiService.flowOf(version));
        view.put("javaSources", apiService.javaSourcesOf(version));
        view.put("mocks", apiService.mocksOf(version));
        view.put("seeds", apiService.seedsOf(version));
        view.put("bytecode", compileService.summarize(version.bytecodeJson).get("nodes"));
        return view;
    }

    /** Simpan flow (draft). */
    @PutMapping("/versions/{versionId}/flow")
    public Map<String, Object> saveFlow(HttpServletRequest request,
                                        @PathVariable String versionId,
                                        @RequestBody Map<String, Object> body) {
        CurrentUser actor = auth.require(request, Permission.FLOW_WRITE);
        FlowDefinition flow = parseFlow(body);
        ApiVersion saved = apiService.saveFlow(versionId, flow, actor);
        Map<String, Object> view = versionView(saved);
        view.put("flow", apiService.flowOf(saved));
        return view;
    }

    /** Validasi flow (FR-FLOW-04). */
    @PostMapping("/versions/{versionId}/validate")
    public Map<String, Object> validateFlow(HttpServletRequest request,
                                           @PathVariable String versionId,
                                           @RequestBody(required = false) Map<String, Object> body) {
        auth.require(request, Permission.FLOW_READ);
        ApiVersion version = apiService.requireVersion(versionId);
        FlowDefinition flow = body != null && body.get("flow") != null
                ? parseFlow(body)
                : apiService.flowOf(version);
        return validator.validate(flow).toMap();
    }

    /** Simpan kode Java per node. */
    @PutMapping("/versions/{versionId}/java")
    public Map<String, Object> saveJava(HttpServletRequest request,
                                        @PathVariable String versionId,
                                        @RequestBody Map<String, Object> body) {
        CurrentUser actor = auth.require(request, Permission.JAVA_WRITE);
        Map<String, Object> sources = json.convert(body.get("javaSources"), Map.class);
        ApiVersion saved = apiService.saveJavaSources(versionId,
                sources == null ? Map.of() : sources, actor);
        Map<String, Object> view = versionView(saved);
        view.put("javaSources", apiService.javaSourcesOf(saved));
        return view;
    }

    /** Kompilasi kode Java (tombol Check, FR-JAVA-01/03). */
    @PostMapping("/versions/{versionId}/compile")
    public Map<String, Object> compile(HttpServletRequest request, @PathVariable String versionId) {
        CurrentUser actor = auth.require(request, Permission.JAVA_WRITE);
        ApiVersion version = apiService.requireVersion(versionId);
        // pastikan versi masih dapat diubah sebelum mengompilasi
        apiService.requireMutableVersion(version.id);

        CompileService.VersionCompilation compilation = compileService.compileVersion(versionId, actor);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", compilation.success());
        result.put("nodes", compilation.nodes());
        result.put("compiled", compilation.compiled());
        result.put("failedNodeIds", compilation.failedNodeIds());
        result.put("results", compilation.results().stream().map(node -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nodeId", node.nodeId());
            item.put("success", node.success());
            item.put("className", node.className());
            item.put("problems", node.problems());
            return item;
        }).toList());
        return result;
    }

    /** Simpan mock default versi (FR-SIM-04). */
    @PutMapping("/versions/{versionId}/mocks")
    public Map<String, Object> saveMocks(HttpServletRequest request,
                                         @PathVariable String versionId,
                                         @RequestBody Map<String, Object> body) {
        CurrentUser actor = auth.require(request, Permission.FLOW_WRITE);
        List<Object> mocks = toObjectList(body.get("mocks"));
        ApiVersion saved = apiService.saveMocks(versionId, mocks, actor);
        Map<String, Object> view = versionView(saved);
        view.put("mocks", apiService.mocksOf(saved));
        return view;
    }

    /** Simpan seed database sandbox versi (FR-SIM-05). */
    @PutMapping("/versions/{versionId}/seeds")
    public Map<String, Object> saveSeeds(HttpServletRequest request,
                                         @PathVariable String versionId,
                                         @RequestBody Map<String, Object> body) {
        CurrentUser actor = auth.require(request, Permission.FLOW_WRITE);
        List<Object> seeds = toObjectList(body.get("seeds"));
        ApiVersion saved = apiService.saveSeeds(versionId, seeds, actor);
        Map<String, Object> view = versionView(saved);
        view.put("seeds", apiService.seedsOf(saved));
        return view;
    }

    // ------------------------------------------------------------- helper

    private Map<String, Object> groupView(ApiGroup group) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", group.id);
        view.put("name", group.name);
        view.put("description", group.description);
        view.put("color", group.color);
        view.put("apiCount", apis.findByGroupIdOrderByNameAsc(group.id).size());
        view.put("createdAt", group.createdAt);
        return view;
    }

    private Map<String, Object> apiView(ApiEntity api) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", api.id);
        view.put("groupId", api.groupId);
        view.put("name", api.name);
        view.put("path", api.path);
        view.put("method", api.method);
        view.put("description", api.description);
        view.put("tags", api.tags);
        view.put("disabled", api.disabled);
        view.put("policy", json.readAny(api.policyJson));
        view.put("activeVersions", api.activeVersions);
        view.put("createdAt", api.createdAt);
        view.put("createdBy", api.createdBy);
        view.put("updatedAt", api.updatedAt);
        return view;
    }

    /** Detail API dilengkapi ringkasan versi (UC-01). */
    private Map<String, Object> detailView(ApiEntity api) {
        Map<String, Object> view = apiView(api);
        List<ApiVersion> versions = apiService.versionsOf(api.id);
        view.put("versions", versions.stream().map(this::versionView).toList());

        ApiVersion latest = versions.stream().findFirst().orElse(null);
        if (latest != null) {
            view.put("latestVersionId", latest.id);
            view.put("latestVersionNumber", latest.versionNumber);
            view.put("latestStatus", latest.status.name());
            view.put("latestLabel", latest.label());
        }
        view.put("draftVersionId", versions.stream()
                .filter(ApiVersion::isDraft).map(v -> v.id).findFirst().orElse(null));
        view.put("publishedVersionId", versions.stream()
                .filter(ApiVersion::isPublished).map(v -> v.id).findFirst().orElse(null));
        return view;
    }

    private Map<String, Object> versionView(ApiVersion version) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", version.id);
        view.put("apiId", version.apiId);
        view.put("versionNumber", version.versionNumber);
        view.put("label", version.label());
        view.put("status", version.status.name());
        view.put("changeNote", version.changeNote);
        view.put("createdAt", version.createdAt);
        view.put("createdBy", version.createdBy);
        view.put("publishedAt", version.publishedAt);
        view.put("publishedBy", version.publishedBy);

        FlowDefinition flow = apiService.flowOf(version);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("nodes", flow.nodes().size());
        summary.put("edges", flow.edges().size());
        summary.put("hasTrigger", flow.nodes().stream().anyMatch(n -> n.type() == NodeType.TRIGGER));
        summary.put("hasResponse", flow.nodes().stream().anyMatch(n -> n.type() == NodeType.RESPONSE));
        view.put("flowSummary", summary);
        return view;
    }

    /** Parse body flow: menerima {@code {flow:{...}}} atau graph langsung. */
    private FlowDefinition parseFlow(Map<String, Object> body) {
        Object raw = body.get("flow") != null ? body.get("flow") : body;
        FlowDefinition flow = json.convert(raw, FlowDefinition.class);
        if (flow == null) {
            throw new IllegalArgumentException("Definisi flow tidak valid");
        }
        return flow;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> toObjectList(Object raw) {
        if (raw instanceof List<?> list) {
            return new ArrayList<>((List<Object>) list);
        }
        return new ArrayList<>();
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
