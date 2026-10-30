package io.apistudio.cp.web;

import io.apistudio.cp.domain.FunctionTemplate;
import io.apistudio.cp.domain.TemplateVersion;
import io.apistudio.cp.security.AuthService;
import io.apistudio.cp.security.CurrentUser;
import io.apistudio.cp.security.Permission;
import io.apistudio.cp.service.TemplateService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Function template dan katalognya (BRD FR-TPL-01..07).
 */
@RestController
@RequestMapping("/api")
public class TemplateController {

    private final TemplateService templateService;
    private final AuthService auth;

    public TemplateController(TemplateService templateService, AuthService auth) {
        this.templateService = templateService;
        this.auth = auth;
    }

    /** Daftar template, opsional difilter kategori. */
    @GetMapping("/templates")
    public List<Map<String, Object>> list(HttpServletRequest request,
                                          @RequestParam(required = false) String category) {
        auth.require(request, Permission.TEMPLATE_READ);
        return templateService.all().stream()
                .filter(t -> category == null || category.isBlank() || category.equalsIgnoreCase(t.category))
                .map(templateService::view)
                .toList();
    }

    /** Kategori beserta jumlah template (palet). */
    @GetMapping("/templates/categories")
    public List<Map<String, Object>> categories(HttpServletRequest request) {
        auth.require(request, Permission.TEMPLATE_READ);
        return templateService.categorySummary();
    }

    /** Detail template beserta seluruh versinya. */
    @GetMapping("/templates/{templateId}")
    public Map<String, Object> detail(HttpServletRequest request, @PathVariable String templateId) {
        auth.require(request, Permission.TEMPLATE_READ);
        FunctionTemplate template = templateService.requireTemplate(templateId);
        Map<String, Object> view = templateService.view(template);
        view.put("versions", templateService.versionsOf(templateId).stream()
                .map(templateService::versionView).toList());
        return view;
    }

    /** Daftar flow yang memakai template ini (FR-TPL-05). */
    @GetMapping("/templates/{templateId}/dependencies")
    public Map<String, Object> dependencies(HttpServletRequest request, @PathVariable String templateId) {
        auth.require(request, Permission.TEMPLATE_READ);
        FunctionTemplate template = templateService.requireTemplate(templateId);
        List<Map<String, Object>> used = templateService.dependencies(template.templateKey);
        return Map.of(
                "templateKey", template.templateKey,
                "usedBy", used,
                "count", used.size());
    }

    /** Buat template baru. */
    @PostMapping("/templates")
    public Map<String, Object> create(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        CurrentUser actor = auth.require(request, Permission.TEMPLATE_WRITE);
        FunctionTemplate template = templateService.create(
                stringOf(body.get("templateKey")),
                String.valueOf(body.getOrDefault("name", "Template Baru")),
                String.valueOf(body.getOrDefault("category", "Utilities")),
                stringOf(body.get("description")),
                stringOf(body.get("tags")),
                parseImplType(body.get("implType")),
                String.valueOf(body.getOrDefault("implementation", "")),
                body.get("inputs") == null ? "[]" : io.apistudio.engine.expr.Json.encode(body.get("inputs")),
                body.get("output") == null ? "{}" : io.apistudio.engine.expr.Json.encode(body.get("output")),
                actor);
        return templateService.view(template);
    }

    /** Buat versi baru template (FR-TPL-04). */
    @PostMapping("/templates/{templateId}/versions")
    public Map<String, Object> createVersion(HttpServletRequest request,
                                             @PathVariable String templateId,
                                             @RequestBody Map<String, Object> body) {
        CurrentUser actor = auth.require(request, Permission.TEMPLATE_WRITE);
        TemplateVersion version = templateService.createVersion(
                templateId,
                parseImplType(body.get("implType")),
                String.valueOf(body.getOrDefault("implementation", "")),
                body.get("inputs") == null ? null : io.apistudio.engine.expr.Json.encode(body.get("inputs")),
                body.get("output") == null ? null : io.apistudio.engine.expr.Json.encode(body.get("output")),
                stringOf(body.get("changeNote")),
                actor);
        return templateService.versionView(version);
    }

    private static FunctionTemplate.ImplType parseImplType(Object raw) {
        try {
            return FunctionTemplate.ImplType.valueOf(
                    String.valueOf(raw == null ? "SCRIPT" : raw).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return FunctionTemplate.ImplType.SCRIPT;
        }
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
