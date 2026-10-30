package io.apistudio.cp.service;

import io.apistudio.cp.domain.Environment;
import io.apistudio.cp.repo.EnvironmentRepository;
import io.apistudio.cp.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manajemen environment dan instance Flow Engine (BRD FR-API-07, Bagian 10.1).
 *
 * <p>Environment menyimpan instance yang terdaftar dan flag kebijakan. Flag
 * {@code requiresApproval} menentukan apakah publish ke environment tersebut
 * wajib melalui persetujuan (FR-DEP-02).
 */
@Service
public class EnvironmentService {

    private final EnvironmentRepository environments;
    private final JsonStore json;
    private final AuditService audit;

    public EnvironmentService(EnvironmentRepository environments, JsonStore json, AuditService audit) {
        this.environments = environments;
        this.json = json;
        this.audit = audit;
    }

    public List<Environment> all() {
        return environments.findAllByOrderByCodeAsc();
    }

    public Environment require(String code) {
        return environments.findByCode(code).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Environment tidak ditemukan: " + code));
    }

    /** Perbarui environment: flag dan instance. */
    @Transactional
    public Environment update(String code, Map<String, Object> body, CurrentUser actor) {
        Environment environment = require(code);

        if (body.get("name") != null) {
            environment.name = String.valueOf(body.get("name"));
        }
        if (body.get("description") != null) {
            environment.description = String.valueOf(body.get("description"));
        }
        if (body.get("requiresApproval") instanceof Boolean value) {
            environment.requiresApproval = value;
        }
        if (body.get("simulationEnabled") instanceof Boolean value) {
            environment.simulationEnabled = value;
        }
        if (body.get("canaryEnabled") instanceof Boolean value) {
            environment.canaryEnabled = value;
        }
        if (body.get("instances") != null) {
            environment.instancesJson = json.write(body.get("instances"));
        }
        if (body.get("variables") != null) {
            environment.variablesJson = json.write(body.get("variables"));
        }

        Environment saved = environments.save(environment);
        audit.record(actor, "ENVIRONMENT_UPDATED", "Environment", saved.id,
                "Environment " + code + " diperbarui");
        return saved;
    }

    /** Variabel environment sebagai map. */
    public Map<String, String> variablesOf(Environment environment) {
        Map<String, Object> raw = json.readMap(environment.variablesJson);
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((k, v) -> result.put(k, v == null ? "" : String.valueOf(v)));
        return result;
    }

    /** Daftar instance terdaftar. */
    public List<Map<String, Object>> instancesOf(Environment environment) {
        return json.readMapList(environment.instancesJson);
    }

    /** View environment untuk API. */
    public Map<String, Object> view(Environment environment) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", environment.id);
        view.put("code", environment.code);
        view.put("name", environment.name);
        view.put("description", environment.description);
        view.put("requiresApproval", environment.requiresApproval);
        view.put("simulationEnabled", environment.simulationEnabled);
        view.put("canaryEnabled", environment.canaryEnabled);
        view.put("instances", json.readAny(environment.instancesJson));
        view.put("variables", json.readAny(environment.variablesJson));
        return view;
    }
}
