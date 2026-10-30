package io.apistudio.contracts.exec;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Permintaan eksekusi flow (BRD Bagian 5.3 langkah 3).
 *
 * <p>Versi flow menjadi bagian dari setiap request (Bagian 5.2 poin 5) sehingga
 * beberapa versi dapat hidup bersamaan saat drain, canary, dan rollback.
 *
 * @param apiId       id API pemilik flow
 * @param flowVersion versi flow yang harus dieksekusi
 * @param method      method HTTP
 * @param path        path request
 * @param headers     header request
 * @param query       query string terurai
 * @param body        body request (Map/List/primitif hasil parsing JSON, atau String)
 * @param debug       mode debug: menambah observasi tanpa mengubah perilaku (Bagian 5.2 poin 4)
 * @param mocks       mock dependensi (FR-SIM-04)
 * @param seed        data awal database sandbox (FR-SIM-05)
 * @param traceId     id korelasi; dibuat engine bila null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExecutionRequest(
        String apiId,
        String flowVersion,
        String method,
        String path,
        Map<String, String> headers,
        Map<String, String> query,
        Object body,
        boolean debug,
        List<HttpMock> mocks,
        List<DbSeed> seed,
        String traceId
) {

    public ExecutionRequest {
        headers = headers == null ? Map.of() : new LinkedHashMap<>(headers);
        query = query == null ? Map.of() : new LinkedHashMap<>(query);
        mocks = mocks == null ? List.of() : List.copyOf(mocks);
        seed = seed == null ? List.of() : List.copyOf(seed);
    }

    /** Request sederhana untuk unit test. */
    public static ExecutionRequest simple(String method, String path, Object body) {
        return new ExecutionRequest(null, null, method, path, Map.of(), Map.of(), body, true,
                List.of(), List.of(), null);
    }

    /** Mock untuk satu pemanggilan HTTP keluar (FR-SIM-04). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HttpMock(
            String urlPattern,
            Integer status,
            Map<String, String> headers,
            Object body,
            Long delayMs,
            Mode mode
    ) {

        public HttpMock {
            headers = headers == null ? Map.of() : new LinkedHashMap<>(headers);
            mode = mode == null ? Mode.MOCK : mode;
        }

        public enum Mode {
            /** Selalu balas dengan konfigurasi mock. */
            MOCK,
            /** Teruskan ke jaringan sungguhan. */
            REAL,
            /** Simulasikan timeout. */
            ERROR_TIMEOUT,
            /** Simulasikan kegagalan 5xx. */
            ERROR_5XX
        }
    }

    /** Data awal untuk tabel sandbox, diisi sebelum flow berjalan lalu di-rollback. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DbSeed(String table, List<Map<String, Object>> rows) {

        public DbSeed {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }
}
