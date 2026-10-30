package io.apistudio.contracts.flow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Edge penghubung antar node.
 *
 * <p>{@code port} menentukan cabang keluar node sumber. Nilai yang dikenal:
 * {@code out}, {@code true}, {@code false}, {@code error}, serta nama case
 * pada node SWITCH dan nama port pada PARALLEL/LOOP.
 *
 * @param id       id unik edge (boleh null untuk flow lama)
 * @param from     id node sumber
 * @param to       id node tujuan
 * @param fromPort port keluar node sumber
 * @param label    label tampilan (mis. "ya"/"tidak")
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlowEdge(
        String id,
        String from,
        String to,
        String fromPort,
        String label
) {

    public static final String PORT_OUT = "out";
    public static final String PORT_TRUE = "true";
    public static final String PORT_FALSE = "false";
    public static final String PORT_ERROR = "error";

    public FlowEdge {
        fromPort = fromPort == null || fromPort.isBlank() ? PORT_OUT : fromPort;
    }

    public static FlowEdge of(String from, String to) {
        return new FlowEdge(null, from, to, PORT_OUT, null);
    }

    public static FlowEdge of(String from, String to, String port) {
        return new FlowEdge(null, from, to, port, null);
    }

    public String portOrDefault() {
        return fromPort == null || fromPort.isBlank() ? PORT_OUT : fromPort;
    }
}
