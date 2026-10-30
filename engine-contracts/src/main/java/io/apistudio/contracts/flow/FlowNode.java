package io.apistudio.contracts.flow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Satu node pada graph flow (BRD FR-FLOW-01..03).
 *
 * <p>{@code config} bersifat bebas (map) dan tafsirnya ditentukan oleh
 * eksekutor node terkait. Dengan begitu menambah jenis node tidak mengubah
 * skema graph yang sudah tersimpan.
 *
 * @param id       id unik dalam satu flow
 * @param type     jenis node
 * @param label    judul yang ditampilkan di kanvas
 * @param config   konfigurasi node
 * @param position posisi kanvas (tidak dipakai engine)
 * @param onError  kebijakan error; {@code null} dianggap default
 * @param disabled true bila node dilewati saat eksekusi
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlowNode(
        String id,
        NodeType type,
        String label,
        Map<String, Object> config,
        Position position,
        ErrorPolicy onError,
        boolean disabled
) {

    public FlowNode {
        config = config == null ? new LinkedHashMap<>() : new LinkedHashMap<>(config);
        position = position == null ? Position.origin() : position;
    }

    public static FlowNode of(String id, NodeType type, String label, Map<String, Object> config) {
        return new FlowNode(id, type, label, config, Position.origin(), ErrorPolicy.defaults(), false);
    }

    /** Ambil nilai konfigurasi mentah. */
    public Object cfg(String key) {
        return config.get(key);
    }

    /** Ambil nilai konfigurasi sebagai String; {@code fallback} bila tidak ada. */
    public String cfgString(String key, String fallback) {
        Object value = config.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    /** Ambil nilai konfigurasi sebagai Integer; {@code fallback} bila tidak valid. */
    public Integer cfgInt(String key, Integer fallback) {
        Object value = config.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    /** Ambil nilai konfigurasi sebagai boolean. */
    public boolean cfgBool(String key, boolean fallback) {
        Object value = config.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        return fallback;
    }

    public ErrorPolicy errorPolicy() {
        return onError == null ? ErrorPolicy.defaults() : onError;
    }
}
