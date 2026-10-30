package io.apistudio.contracts.node;

/**
 * Kontrak formal untuk kode Java pengguna (BRD FR-JAVA-02).
 *
 * <p>Pada praktiknya, kode pengguna cukup menulis isi method {@code run}:
 * kompiler membungkusnya menjadi kelas yang memenuhi antarmuka ini. Antarmuka
 * disediakan agar kontraknya eksplisit dan dapat dipakai untuk kasus lanjutan
 * (mis. kelas implementasi penuh yang diunggah sebagai JAR).
 */
public interface JavaNode {

    /**
     * Eksekusi logika node.
     *
     * @param ctx konteks flow
     * @return nilai yang menjadi output node; boleh {@code null}
     * @throws Exception bila logika pengguna gagal
     */
    Object run(NodeContext ctx) throws Exception;
}
