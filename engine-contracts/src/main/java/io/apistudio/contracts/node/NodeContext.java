package io.apistudio.contracts.node;

import java.util.Map;

/**
 * API terkontrol yang tersedia untuk kode Java pengguna (BRD FR-JAVA-06).
 *
 * <p>Kode pengguna tidak mendapat akses langsung ke runtime; semua interaksi
 * dengan flow dilakukan lewat antarmuka ini. Implementasi sebenarnya ada di
 * flow-engine ({@code FlowRuntimeContext}), sehingga kontrak ini tetap bersih
 * dan tidak bergantung pada engine.
 */
public interface NodeContext {

    /** Nilai variabel flow; {@code null} bila belum ada. */
    Object get(String name);

    /** Set variabel flow yang dapat dibaca node berikutnya. */
    void set(String name, Object value);

    /**
     * Ambil field dari body request berdasarkan nama.
     *
     * @return nilai field, atau {@code null} bila tidak ada
     */
    Object input(String field);

    /** Seluruh body request sebagai map (kosong bila bukan objek). */
    Map<String, Object> inputMap();

    /** Header request. */
    Map<String, String> headers();

    /** Query string request. */
    Map<String, String> query();

    /** Evaluasi ekspresi flow (mis. {@code request.body.id}). */
    Object eval(String expression);

    /** Tulis log yang akan tampil di trace bersama nomor baris kode pengguna. */
    void log(String message);

    /** Panggil function template lain dari dalam kode Java (FR-JAVA-06). */
    Object callTemplate(String templateKey, Map<String, Object> args);

    /** Nama node yang sedang dieksekusi. */
    String nodeId();
}
