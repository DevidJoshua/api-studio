package io.apistudio.engine.runtime;

/**
 * Resolver secret untuk flow (BRD FR-SEC-02).
 *
 * <p>Flow hanya menyimpan nama secret; nilai sebenarnya disediakan Control
 * Plane (mode lokal AES-GCM atau vault). Engine tidak pernah menyimpan nilai
 * ke disk maupun ke trace.
 */
@FunctionalInterface
public interface SecretResolver {

    /** Resolver kosong: setiap nama mengembalikan {@code null}. */
    SecretResolver EMPTY = name -> null;

    /**
     * Ambil nilai secret.
     *
     * @return nilai, atau {@code null} bila tidak terdaftar
     */
    String value(String name);
}
