package io.apistudio.contracts.flow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Definisi flow lengkap: graph node dan edge (BRD Bagian 5.2 poin 2).
 *
 * <p>Format ini adalah satu-satunya representasi flow yang disimpan Control
 * Plane dan diterima Flow Engine. Pengguna tidak mengeditnya langsung; Studio
 * membangunnya dari kanvas.
 *
 * @param id      id versi flow (diisi Control Plane saat eksekusi)
 * @param name    nama flow
 * @param nodes   daftar node
 * @param edges   daftar edge
 * @param version versi skema graph
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlowDefinition(
        String id,
        String name,
        List<FlowNode> nodes,
        List<FlowEdge> edges,
        int version
) {

    /** Versi skema graph saat ini. */
    public static final int SCHEMA_VERSION = 1;

    public FlowDefinition {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        version = version <= 0 ? SCHEMA_VERSION : version;
    }

    /** Flow kosong dengan satu node TRIGGER, kondisi awal saat API dibuat. */
    public static FlowDefinition withTrigger(String name) {
        FlowNode trigger = FlowNode.of("trigger", NodeType.TRIGGER, "Request Masuk",
                Map.of("description", "Titik masuk request klien"));
        return new FlowDefinition(null, name, List.of(trigger), List.of(), SCHEMA_VERSION);
    }

    /** Flow kosong tanpa node. */
    public static FlowDefinition empty(String name) {
        return new FlowDefinition(null, name, List.of(), List.of(), SCHEMA_VERSION);
    }

    public Optional<FlowNode> trigger() {
        return nodes.stream().filter(n -> n.type() == NodeType.TRIGGER).findFirst();
    }

    public Optional<FlowNode> node(String nodeId) {
        return nodes.stream().filter(n -> n.id().equals(nodeId)).findFirst();
    }

    /** Edge keluar dari node, terurut sesuai deklarasi agar hasil deterministik. */
    public List<FlowEdge> outgoing(String nodeId) {
        List<FlowEdge> result = new ArrayList<>();
        for (FlowEdge edge : edges) {
            if (edge.from().equals(nodeId)) {
                result.add(edge);
            }
        }
        return result;
    }

    /** Edge keluar pada port tertentu. */
    public Optional<FlowEdge> outgoing(String nodeId, String port) {
        return outgoing(nodeId).stream()
                .filter(e -> e.portOrDefault().equals(port))
                .findFirst();
    }

    /** Edge masuk ke node. */
    public List<FlowEdge> incoming(String nodeId) {
        List<FlowEdge> result = new ArrayList<>();
        for (FlowEdge edge : edges) {
            if (edge.to().equals(nodeId)) {
                result.add(edge);
            }
        }
        return result;
    }

    /** Id node yang tidak punya edge masuk (kandidat titik awal). */
    public List<String> rootNodeIds() {
        List<String> roots = new ArrayList<>();
        for (FlowNode node : nodes) {
            if (incoming(node.id()).isEmpty()) {
                roots.add(node.id());
            }
        }
        return roots;
    }

    /** Salinan dengan nodes/edges baru; dipakai saat menormalisasi flow. */
    public FlowDefinition withGraph(List<FlowNode> newNodes, List<FlowEdge> newEdges) {
        return new FlowDefinition(id, name, newNodes, newEdges, version);
    }

    /** Salinan dengan id versi tertentu. */
    public FlowDefinition withId(String newId) {
        return new FlowDefinition(newId, name, nodes, edges, version);
    }

    /** Ringkasan untuk ditampilkan di UI. */
    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("nodes", nodes.size());
        summary.put("edges", edges.size());
        summary.put("hasTrigger", trigger().isPresent());
        summary.put("hasResponse", nodes.stream().anyMatch(n -> n.type() == NodeType.RESPONSE));
        return summary;
    }
}
