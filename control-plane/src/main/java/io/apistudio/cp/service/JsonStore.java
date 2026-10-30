package io.apistudio.cp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper konversi JSON yang toleran terhadap data rusak.
 *
 * <p>Semua kolom JSON pada entity disimpan sebagai String agar skema database
 * sederhana dan mudah dipindah ke PostgreSQL (JSONB). Helper ini memusatkan
 * penanganan error parsing sehingga service dan controller tidak perlu
 * try/catch berulang.
 */
@Component
public class JsonStore {

    private final ObjectMapper mapper;

    public JsonStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Mapper bersama (mis. untuk convertValue). */
    public ObjectMapper mapper() {
        return mapper;
    }

    /** Serialisasi; kegagalan ditandai sebagai IllegalStateException. */
    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Gagal menulis JSON: " + e.getMessage(), e);
        }
    }

    /** Serialisasi dengan fallback bila gagal. */
    public String writeOr(Object value, String fallback) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    public <T> T read(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            return fallback;
        }
    }

    public <T> T read(String json, Class<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Map dari JSON; kosong bila gagal. */
    public Map<String, Object> readMap(String json) {
        Map<String, Object> result = read(json, new TypeReference<Map<String, Object>>() {
        }, null);
        return result == null ? new LinkedHashMap<>() : result;
    }

    /** List dari JSON; kosong bila gagal. */
    public List<Object> readList(String json) {
        List<Object> result = read(json, new TypeReference<List<Object>>() {
        }, null);
        return result == null ? new ArrayList<>() : result;
    }

    /** List of Map dari JSON; kosong bila gagal. */
    public List<Map<String, Object>> readMapList(String json) {
        List<Map<String, Object>> result = read(json, new TypeReference<List<Map<String, Object>>>() {
        }, null);
        return result == null ? new ArrayList<>() : result;
    }

    /** Nilai JSON apa adanya; String dikembalikan bila bukan JSON valid. */
    public Object readAny(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    /** Pretty-print untuk ditampilkan di UI; mengembalikan input bila gagal. */
    public String pretty(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            Object value = mapper.readValue(json, Object.class);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return json;
        }
    }

    /** Konversi objek bebas menjadi tipe tertentu (mis. FlowDefinition). */
    public <T> T convert(Object value, Class<T> type) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.convertValue(value, type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
