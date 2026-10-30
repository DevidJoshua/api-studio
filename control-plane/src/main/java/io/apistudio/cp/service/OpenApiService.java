package io.apistudio.cp.service;

import io.apistudio.contracts.flow.FlowDefinition;
import io.apistudio.contracts.flow.FlowEdge;
import io.apistudio.contracts.flow.FlowNode;
import io.apistudio.contracts.flow.NodeType;
import io.apistudio.cp.domain.ApiEntity;
import io.apistudio.cp.domain.ApiVersion;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generator dokumentasi OpenAPI dari definisi API (BRD FR-API-06).
 *
 * <p>Dokumen diturunkan dari graph flow: body request dibangun dari ekspresi
 * {@code =request.body.*} yang dipakai node, dan response diambil dari node
 * RESPONSE. Karena bersumber dari definisi yang sama dengan runtime,
 * dokumentasi tidak dapat menyimpang dari implementasi.
 */
@Service
public class OpenApiService {

    private final JsonStore json;

    public OpenApiService(JsonStore json) {
        this.json = json;
    }

    public Map<String, Object> generate(ApiEntity api, ApiVersion version, FlowDefinition flow) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("openapi", "3.0.3");

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", api.name);
        info.put("description", api.description == null ? "" : api.description);
        info.put("version", version == null ? "1" : "v" + version.versionNumber);
        doc.put("info", info);
        doc.put("servers", List.of(Map.of("url", "/gw", "description", "API Engine gateway")));

        Map<String, Object> pathItem = new LinkedHashMap<>();
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operationId", api.id);
        operation.put("summary", api.name);
        operation.put("description", api.description == null ? "" : api.description);
        operation.put("tags", api.tags == null || api.tags.isBlank()
                ? List.of("default")
                : List.of(api.tags.split(",")));

        // parameter query dari node TRIGGER
        List<Map<String, Object>> parameters = new ArrayList<>();
        flow.trigger().ifPresent(trigger -> {
            if (trigger.cfg("queryParameters") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        Map<String, Object> parameter = new LinkedHashMap<>();
                        parameter.put("name", map.get("name"));
                        parameter.put("in", "query");
                        parameter.put("required", Boolean.TRUE.equals(map.get("required")));
                        Object type = map.get("type");
                        parameter.put("schema", Map.of("type", type == null ? "string" : String.valueOf(type)));
                        parameters.add(parameter);
                    }
                }
            }
        });
        if (!parameters.isEmpty()) {
            operation.put("parameters", parameters);
        }

        // request body untuk method yang membawa body
        boolean hasBody = !"GET".equalsIgnoreCase(api.method) && !"HEAD".equalsIgnoreCase(api.method);
        if (hasBody) {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("required", true);
            Map<String, Object> media = new LinkedHashMap<>();
            media.put("schema", Map.of("type", "object"));
            Map<String, Object> sample = sampleBody(flow);
            if (!sample.isEmpty()) {
                media.put("example", sample);
            }
            requestBody.put("content", Map.of("application/json", media));
            operation.put("requestBody", requestBody);
        }

        // responses dari node RESPONSE
        Map<String, Object> responses = new LinkedHashMap<>();
        List<FlowNode> responseNodes = flow.nodes().stream()
                .filter(n -> n.type() == NodeType.RESPONSE)
                .toList();
        if (responseNodes.isEmpty()) {
            responses.put("200", Map.of("description", "Response dari flow " + flow.name()));
        } else {
            for (FlowNode node : responseNodes) {
                int status = node.cfgInt("status", 200);
                Map<String, Object> responseSpec = new LinkedHashMap<>();
                responseSpec.put("description", node.label() == null
                        ? "Response flow"
                        : node.label());
                Map<String, Object> media = new LinkedHashMap<>();
                media.put("schema", Map.of("type", "object"));
                Object bodyConfig = node.cfg("body");
                if (bodyConfig != null) {
                    media.put("example", json.readAny(json.write(bodyConfig)));
                }
                responseSpec.put("content", Map.of("application/json", media));
                responses.put(String.valueOf(status), responseSpec);
            }
        }
        responses.putIfAbsent("500", Map.of("description", "Kegagalan eksekusi flow"));
        operation.put("responses", responses);

        pathItem.put(api.method.toLowerCase(java.util.Locale.ROOT), operation);
        doc.put("paths", Map.of(api.path, pathItem));

        // ekstensi transparan untuk developer: ringkasan flow
        Map<String, Object> flowDoc = new LinkedHashMap<>();
        flowDoc.put("name", flow.name());
        flowDoc.put("nodes", flow.nodes().stream().map(n -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", n.id());
            item.put("type", n.type().name());
            item.put("label", n.label() == null ? "" : n.label());
            return item;
        }).toList());
        flowDoc.put("edges", flow.edges().stream().map((FlowEdge e) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("from", e.from());
            item.put("to", e.to());
            item.put("port", e.portOrDefault());
            return item;
        }).toList());
        doc.put("x-flow", flowDoc);

        return doc;
    }

    /**
     * Bangun contoh body dari referensi {@code =request.body.*} yang dipakai flow.
     *
     * <p>Struktur bersarang dibentuk sesuai path sehingga contoh konsisten
     * dengan yang dibaca node.
     */
    Map<String, Object> sampleBody(FlowDefinition flow) {
        Set<String> paths = new LinkedHashSet<>();
        for (FlowNode node : flow.nodes()) {
            collectPaths(node.config(), paths);
        }
        Map<String, Object> sample = new LinkedHashMap<>();
        for (String path : paths) {
            putPath(sample, path);
        }
        return sample;
    }

    @SuppressWarnings("unchecked")
    private void putPath(Map<String, Object> root, String path) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map)) {
                Map<String, Object> created = new LinkedHashMap<>();
                current.put(parts[i], created);
                current = created;
            } else {
                current = (Map<String, Object>) next;
            }
        }
        String leaf = parts[parts.length - 1];
        current.putIfAbsent(leaf, inferExample(leaf));
    }

    /** Contoh nilai yang masuk akal berdasarkan nama field (membantu developer). */
    private static String inferExample(String field) {
        String lower = field.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("amount") || lower.contains("total") || lower.contains("value")) {
            return "15000.00";
        }
        if (lower.contains("currency")) {
            return "IDR";
        }
        if (lower.contains("email")) {
            return "user@example.com";
        }
        if (lower.contains("account") || lower.contains("rekening")) {
            return "1234567890";
        }
        if (lower.contains("name")) {
            return "Nama Contoh";
        }
        if (lower.equals("id") || lower.endsWith("id")) {
            return "ID-0001";
        }
        return "string";
    }

    private void collectPaths(Object value, Set<String> paths) {
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.startsWith("=request.body.")) {
                paths.add(trimmed.substring("=request.body.".length()));
            }
            // dukungan template ${request.body.x}
            int index = 0;
            while (true) {
                int start = trimmed.indexOf("${request.body.", index);
                if (start < 0) {
                    break;
                }
                int end = trimmed.indexOf('}', start);
                if (end < 0) {
                    break;
                }
                paths.add(trimmed.substring(start + "${request.body.".length(), end));
                index = end + 1;
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> collectPaths(v, paths));
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(v -> collectPaths(v, paths));
        }
    }
}
