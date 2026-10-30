package io.apistudio.cp.service;

import io.apistudio.contracts.flow.FlowDefinition;
import io.apistudio.contracts.flow.FlowNode;
import io.apistudio.contracts.flow.NodeType;
import io.apistudio.cp.domain.ApiVersion;
import io.apistudio.cp.domain.FunctionTemplate;
import io.apistudio.cp.domain.TemplateVersion;
import io.apistudio.cp.repo.ApiVersionRepository;
import io.apistudio.cp.repo.FunctionTemplateRepository;
import io.apistudio.cp.repo.TemplateVersionRepository;
import io.apistudio.cp.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manajemen function template dan versinya (BRD FR-TPL-01..07).
 *
 * <p>Template di-lock by version: flow memanggil {@code key@versi} sehingga
 * perubahan template tidak mengubah perilaku flow yang berjalan (FR-TPL-04).
 */
@Service
public class TemplateService {

    private final FunctionTemplateRepository templates;
    private final TemplateVersionRepository versions;
    private final ApiVersionRepository apiVersions;
    private final JsonStore json;
    private final AuditService audit;

    public TemplateService(FunctionTemplateRepository templates, TemplateVersionRepository versions,
                           ApiVersionRepository apiVersions, JsonStore json, AuditService audit) {
        this.templates = templates;
        this.versions = versions;
        this.apiVersions = apiVersions;
        this.json = json;
        this.audit = audit;
    }

    public List<FunctionTemplate> all() {
        return templates.findAllByOrderByCategoryAscNameAsc();
    }

    /** Daftar kategori yang tersedia (untuk palette dan filter). */
    public List<String> categories() {
        return all().stream().map(t -> t.category).distinct().sorted().toList();
    }

    public FunctionTemplate requireTemplate(String templateId) {
        return templates.findById(templateId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Template tidak ditemukan: " + templateId));
    }

    public List<TemplateVersion> versionsOf(String templateId) {
        return versions.findByTemplateIdOrderByVersionNumberDesc(templateId);
    }

    /** Versi aktif (terbaru) sebuah template. */
    public TemplateVersion latestVersion(String templateKey) {
        return versions.findFirstByTemplateKeyOrderByVersionNumberDesc(templateKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Template belum memiliki versi: " + templateKey));
    }

    /** Ambil versi tertentu. */
    public TemplateVersion findVersion(String templateKey, int versionNumber) {
        return versions.findByTemplateKeyAndVersionNumber(templateKey, versionNumber)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Versi template tidak ditemukan: " + templateKey + "@" + versionNumber));
    }

    /**
     * Buat template baru beserta versi pertamanya.
     */
    @Transactional
    public FunctionTemplate create(String templateKey, String name, String category, String description,
                                   String tags, FunctionTemplate.ImplType implType, String implementation,
                                   String inputsJson, String outputJson, CurrentUser actor) {
        if (templateKey == null || templateKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kunci template wajib diisi");
        }
        if (templates.findByTemplateKey(templateKey).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Kunci template sudah dipakai: " + templateKey);
        }
        FunctionTemplate template = new FunctionTemplate(templateKey.trim(), name, category,
                description, false, actor.username());
        if (tags != null) {
            template.tags = tags;
        }
        FunctionTemplate saved = templates.save(template);

        TemplateVersion version = new TemplateVersion(saved.id, saved.templateKey, 1, implType,
                implementation, actor.username());
        version.changeNote = "Versi awal";
        version.inputsJson = inputsJson == null ? "[]" : inputsJson;
        version.outputJson = outputJson == null ? "{}" : outputJson;
        version.latest = true;
        versions.save(version);

        audit.record(actor, "TEMPLATE_CREATED", "FunctionTemplate", saved.id,
                "Template " + saved.templateKey + " dibuat (" + implType + ")");
        return saved;
    }

    /**
     * Buat versi baru dari template yang ada (FR-TPL-04).
     *
     * <p>Versi lama tetap tersimpan sehingga flow yang mengunci versi lama tidak
     * terpengaruh.
     */
    @Transactional
    public TemplateVersion createVersion(String templateId, FunctionTemplate.ImplType implType,
                                        String implementation, String inputsJson, String outputJson,
                                        String changeNote, CurrentUser actor) {
        FunctionTemplate template = requireTemplate(templateId);
        int next = versions.findByTemplateIdOrderByVersionNumberDesc(templateId).stream()
                .mapToInt(v -> v.versionNumber).max().orElse(0) + 1;

        versions.findByTemplateIdOrderByVersionNumberDesc(templateId)
                .forEach(existing -> existing.latest = false);

        TemplateVersion version = new TemplateVersion(template.id, template.templateKey, next,
                implType, implementation, actor.username());
        version.inputsJson = inputsJson == null ? "[]" : inputsJson;
        version.outputJson = outputJson == null ? "{}" : outputJson;
        version.changeNote = changeNote;
        version.latest = true;
        TemplateVersion saved = versions.save(version);

        audit.record(actor, "TEMPLATE_VERSION_CREATED", "TemplateVersion", saved.id,
                "Versi " + next + " untuk " + template.templateKey + " dibuat");
        return saved;
    }

    /**
     * Daftar flow yang memakai template tertentu (FR-TPL-05).
     *
     * <p>Pemakaian dideteksi dari node TEMPLATE_CALL pada definisi flow setiap
     * versi API. Pemindaian dilakukan di sisi aplikasi karena kolom flow
     * disimpan sebagai JSON; jumlah versi pada MVP masih kecil sehingga biaya
     * pemindaian dapat diterima. Pada skala lebih besar, hasilnya dapat
     * diindeks ke tabel khusus saat flow disimpan.
     */
    public List<Map<String, Object>> dependencies(String templateKey) {
        if (templateKey == null || templateKey.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (ApiVersion version : apiVersions.findAll()) {
            FlowDefinition flow = json.read(version.flowJson, FlowDefinition.class, null);
            if (flow == null) {
                continue;
            }
            for (FlowNode node : flow.nodes()) {
                if (node.type() != NodeType.TEMPLATE_CALL) {
                    continue;
                }
                String key = node.cfgString("templateKey", "");
                Integer nodeVersion = node.cfgInt("version", null);
                String used = nodeVersion == null ? key : key + "@" + nodeVersion;
                if (used.equals(templateKey) || used.startsWith(templateKey + "@")) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("apiId", version.apiId);
                    item.put("versionId", version.id);
                    item.put("versionNumber", version.versionNumber);
                    item.put("versionStatus", version.status.name());
                    item.put("nodeId", node.id());
                    item.put("usedKey", used);
                    result.add(item);
                }
            }
        }
        return result;
    }

    /** View template untuk API. */
    public Map<String, Object> view(FunctionTemplate template) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", template.id);
        view.put("templateKey", template.templateKey);
        view.put("name", template.name);
        view.put("category", template.category);
        view.put("description", template.description);
        view.put("tags", template.tags);
        view.put("builtin", template.builtin);
        view.put("createdAt", template.createdAt);
        view.put("createdBy", template.createdBy);

        List<TemplateVersion> list = versionsOf(template.id);
        view.put("latestVersion", list.stream().filter(v -> v.latest)
                .map(v -> v.versionNumber).findFirst().orElse(null));
        view.put("versionCount", list.size());
        return view;
    }

    /** View versi template. */
    public Map<String, Object> versionView(TemplateVersion version) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", version.id);
        view.put("templateId", version.templateId);
        view.put("templateKey", version.templateKey);
        view.put("versionNumber", version.versionNumber);
        view.put("qualifiedKey", version.qualifiedKey());
        view.put("implType", version.implType.name());
        view.put("implementation", version.implementation);
        view.put("inputs", json.readAny(version.inputsJson));
        view.put("output", json.readAny(version.outputJson));
        view.put("changeNote", version.changeNote);
        view.put("latest", version.latest);
        view.put("createdAt", version.createdAt);
        view.put("createdBy", version.createdBy);
        return view;
    }

    /** Daftar kategori dengan jumlah template (untuk palet). */
    public List<Map<String, Object>> categorySummary() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (FunctionTemplate template : all()) {
            counts.merge(template.category, 1, Integer::sum);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        counts.forEach((category, count) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", category);
            item.put("count", count);
            result.add(item);
        });
        return result;
    }
}
