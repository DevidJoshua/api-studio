package io.apistudio.engine.runtime;

import io.apistudio.contracts.exec.ExecutionRequest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Database sandbox untuk simulasi dan pengujian (BRD FR-SIM-05/07, Bagian 11).
 *
 * <p>Karakteristik:
 * <ul>
 *   <li>H2 in-memory baru per eksekusi ({@code jdbc:h2:mem:sandbox_<traceId>}).</li>
 *   <li>Seed dijalankan di awal; tabel dibuat dari bentuk baris pertama.</li>
 *   <li>Query dari flow <b>hanya SELECT</b>; DDL/DML dan komentar ditolak.</li>
 *   <li>Seluruh run berjalan pada satu koneksi yang di-rollback saat ditutup.</li>
 *   <li>Tidak pernah menunjuk ke database produksi: URL dibangun dari nol.</li>
 * </ul>
 */
public final class SandboxDatabase implements AutoCloseable {

    /** Hanya SELECT sederhana yang diizinkan. */
    private static final Pattern SELECT_ONLY = Pattern.compile(
            "^\\s*(?:with\\s+[\\w\\s,\\(\\)]+\\s+as\\s*\\([^;]*\\)\\s*)?select\\s.+$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Token berbahaya yang tidak boleh muncul di mana pun. */
    private static final List<String> FORBIDDEN_TOKENS = List.of(
            "insert ", "update ", "delete ", "drop ", "alter ", "create ", "truncate ",
            "merge ", "grant ", "revoke ", "call ", "exec ", "runscript", "shutdown",
            "attach ", "detach ", "link ", "backup ", "restore ", "user(", "set password"
    );

    private final String url;
    private final Connection connection;
    private final int maxRows;
    private final Set<String> touchedTables = new LinkedHashSet<>();

    private SandboxDatabase(String url, Connection connection, int maxRows) {
        this.url = url;
        this.connection = connection;
        this.maxRows = maxRows <= 0 ? 1000 : maxRows;
    }

    /**
     * Buka sandbox baru dengan seed.
     *
     * @param traceId id unik run, menjadi nama database
     * @param seeds   data awal
     * @param maxRows batas baris hasil query
     */
    public static SandboxDatabase open(String traceId, List<ExecutionRequest.DbSeed> seeds, int maxRows) {
        String safeId = (traceId == null ? "anon" : traceId).replaceAll("[^A-Za-z0-9_]", "");
        String unique = safeId + "_" + System.nanoTime();
        String url = "jdbc:h2:mem:sandbox_" + unique
                + ";DB_CLOSE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE";
        try {
            Connection connection = DriverManager.getConnection(url, "sa", "");
            connection.setAutoCommit(false);
            SandboxDatabase db = new SandboxDatabase(url, connection, maxRows);
            db.applySeeds(seeds);
            return db;
        } catch (SQLException e) {
            throw new IllegalStateException("Gagal membuat sandbox database: " + e.getMessage(), e);
        }
    }

    /** Sandbox nonaktif; node DATABASE_QUERY akan menolak berjalan. */
    public static SandboxDatabase disabled() {
        return new SandboxDatabase(null, null, 0);
    }

    public boolean enabled() {
        return connection != null;
    }

    /** Tabel yang dibaca/ditulis selama run berjalan. */
    public List<String> touchedTables() {
        return new ArrayList<>(touchedTables);
    }

    /** Jalankan query read-only. */
    public QueryResult query(String sql, Map<String, Object> params) {
        if (!enabled()) {
            throw new IllegalStateException("Database sandbox tidak aktif pada run ini");
        }
        validate(sql);
        long started = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setMaxRows(maxRows);
            int index = 1;
            if (params != null) {
                for (Object value : params.values()) {
                    statement.setObject(index++, value);
                }
            }
            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                List<String> columns = new ArrayList<>(columnCount);
                for (int i = 1; i <= columnCount; i++) {
                    columns.add(meta.getColumnLabel(i).toLowerCase(Locale.ROOT));
                }
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(columns.get(i - 1), rs.getObject(i));
                    }
                    rows.add(row);
                }
                registerTables(sql);
                return new QueryResult(columns, rows, System.currentTimeMillis() - started);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Query gagal: " + e.getMessage()
                    + "; SQL statement:\n" + sql + " [" + e.getErrorCode() + "]", e);
        }
    }

    /** Tolak query yang bukan SELECT. */
    void validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL kosong");
        }
        String normalized = sql.toLowerCase(Locale.ROOT);
        // tolak komentar agar tidak dapat dipakai menyembunyikan perintah
        if (normalized.contains("--") || normalized.contains("/*")) {
            throw new IllegalArgumentException("Komentar tidak diizinkan pada SQL sandbox");
        }
        // hanya boleh satu statement
        String withoutTrailing = sql.trim();
        if (withoutTrailing.endsWith(";")) {
            withoutTrailing = withoutTrailing.substring(0, withoutTrailing.length() - 1);
        }
        if (withoutTrailing.contains(";")) {
            throw new IllegalArgumentException("Hanya satu statement yang diizinkan");
        }
        if (!SELECT_ONLY.matcher(sql).matches()) {
            throw new IllegalArgumentException("Hanya pernyataan SELECT yang diizinkan pada sandbox");
        }
        for (String token : FORBIDDEN_TOKENS) {
            if (normalized.contains(token)) {
                throw new IllegalArgumentException("Perintah terlarang pada sandbox: " + token.trim());
            }
        }
    }

    private void applySeeds(List<ExecutionRequest.DbSeed> seeds) throws SQLException {
        if (seeds == null || seeds.isEmpty()) {
            return;
        }
        for (ExecutionRequest.DbSeed seed : seeds) {
            if (seed == null || seed.table() == null || seed.rows().isEmpty()) {
                continue;
            }
            String table = sanitizeIdentifier(seed.table());
            List<String> columns = new ArrayList<>();
            for (String column : seed.rows().get(0).keySet()) {
                columns.add(sanitizeIdentifier(column));
            }
            if (columns.isEmpty()) {
                continue;
            }
            StringBuilder ddl = new StringBuilder("create table if not exists ")
                    .append(table).append(" (");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    ddl.append(", ");
                }
                ddl.append(columns.get(i)).append(" varchar(4000)");
            }
            ddl.append(')');
            try (Statement statement = connection.createStatement()) {
                statement.execute(ddl.toString());
            }

            String placeholders = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
            String insert = "insert into " + table + " (" + String.join(", ", columns) + ") values ("
                    + placeholders + ")";
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                for (Map<String, Object> row : seed.rows()) {
                    for (int i = 0; i < columns.size(); i++) {
                        Object value = row.get(columns.get(i));
                        if (value == null) {
                            // cari berdasarkan nama asli (sanitasi bisa mengubah)
                            for (Map.Entry<String, Object> entry : row.entrySet()) {
                                if (sanitizeIdentifier(entry.getKey()).equals(columns.get(i))) {
                                    value = entry.getValue();
                                    break;
                                }
                            }
                        }
                        statement.setObject(i + 1, value == null ? null : String.valueOf(value));
                    }
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            touchedTables.add(table);
        }
    }

    /** Ekstrak nama tabel dari klausa FROM/JOIN untuk pelaporan. */
    private void registerTables(String sql) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)\\b(?:from|join)\\s+([A-Za-z_][A-Za-z0-9_]*)")
                .matcher(sql);
        while (matcher.find()) {
            touchedTables.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
    }

    static String sanitizeIdentifier(String raw) {
        String cleaned = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9_]", "");
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Nama identifier tidak valid: " + raw);
        }
        return cleaned.toLowerCase(Locale.ROOT);
    }

    /** Tutup sandbox: rollback seluruh perubahan lalu lepas koneksi. */
    @Override
    public void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // rollback bersifat best-effort
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // koneksi in-memory akan dilepas bersama JVM
        }
    }

    /** Hasil satu query. */
    public record QueryResult(List<String> columns, List<Map<String, Object>> rows, long durationMs) {

        public QueryResult {
            columns = columns == null ? List.of() : List.copyOf(columns);
            rows = rows == null ? List.of() : List.copyOf(rows);
        }

        public int rowCount() {
            return rows.size();
        }

        public Object firstValue(String column) {
            if (rows.isEmpty()) {
                return null;
            }
            return rows.get(0).get(column);
        }
    }
}
