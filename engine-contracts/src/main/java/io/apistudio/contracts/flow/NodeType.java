package io.apistudio.contracts.flow;

/**
 * Jenis node yang didukung Flow Engine (BRD FR-FLOW-02).
 *
 * <p>Nilai enum ini adalah bagian dari kontrak graph yang disimpan Control
 * Plane dan dieksekusi Flow Engine, sehingga penambahan jenis node harus
 * disertai eksekutor baru di {@code flow-engine}.
 */
public enum NodeType {
    /** Titik masuk flow; satu-satunya node tanpa edge masuk. */
    TRIGGER,
    /** Menetapkan satu atau lebih variabel dari ekspresi. */
    SET_VARIABLE,
    /** Transformasi data dengan ekspresi (FR-FLOW-07). */
    TRANSFORM,
    /** Percabangan dua arah true/false. */
    CONDITION,
    /** Percabangan banyak arah berdasarkan case. */
    SWITCH,
    /** Iterasi koleksi. */
    LOOP,
    /** Eksekusi cabang paralel (fan-out lalu join). */
    PARALLEL,
    /** Blok try/catch dengan cabang {@code out} dan {@code error}. */
    TRY_CATCH,
    /** Pemanggilan HTTP ke dependensi eksternal, mendukung mock (FR-SIM-04). */
    HTTP_CALL,
    /** Query database read-only pada sandbox (FR-SIM-05, FR-SIM-07). */
    DATABASE_QUERY,
    /** Pemanggilan function template (FR-TPL-01..07). */
    TEMPLATE_CALL,
    /** Node kode Java pengguna (FR-JAVA-01..08). */
    JAVA_CODE,
    /** Menyusun response akhir. */
    RESPONSE,
    /** Mencatat log ke trace. */
    LOG,
    /** Menunda eksekusi (untuk menguji timeout/retry). */
    DELAY,
    /** Menggagalkan flow secara sengaja (untuk menguji jalur error). */
    FAIL
}
