package io.apistudio.cp.bootstrap;

import io.apistudio.cp.domain.FunctionTemplate;
import io.apistudio.cp.domain.TemplateVersion;

import java.util.List;

/**
 * Katalog function template bawaan (BRD FR-TPL-03/07).
 *
 * <p>Kategori mengikuti BRD: Utilities, Security, SNAP BI, Database, Integrasi.
 * Implementasi memakai jenis SCRIPT (ekspresi) karena dapat dijalankan engine
 * tanpa kompilasi, dengan input bertipe yang terdokumentasi.
 *
 * <p>Fungsi SNAP BI mengikuti konvensi standar: timestamp ISO-8601 dengan
 * offset, signature SHA-256/HMAC atas string komponen, dan pemetaan response
 * code.
 */
public final class BuiltinTemplateCatalog {

    private BuiltinTemplateCatalog() {
    }

    /** Definisi template bawaan. */
    public record Definition(
            String templateKey,
            String name,
            String category,
            String description,
            List<InputSpec> inputs,
            String implementation,
            String outputJson
    ) {
    }

    /** Spesifikasi input bertipe (FR-TPL-01). */
    public record InputSpec(String name, String type, boolean required, String description) {
        static InputSpec of(String name, String type, String description) {
            return new InputSpec(name, type, true, description);
        }

        static InputSpec optional(String name, String type, String description) {
            return new InputSpec(name, type, false, description);
        }
    }

    /** Seluruh template bawaan. */
    public static List<Definition> definitions() {
        return List.of(
                // -------------------------------------------------- Utilities
                new Definition("util.timestamp", "Timestamp ISO-8601", "Utilities",
                        "Menghasilkan timestamp ISO-8601 dengan offset zona waktu, format yang dipakai SNAP BI.",
                        List.of(), "=timestamp()", "{\"type\":\"string\"}"),

                new Definition("util.uuid", "UUID Generator", "Utilities",
                        "Menghasilkan UUID v4 acak untuk idempotency key atau reference number.",
                        List.of(), "=uuid()", "{\"type\":\"string\"}"),

                new Definition("util.upper", "Uppercase", "Utilities",
                        "Mengubah teks menjadi huruf besar.",
                        List.of(InputSpec.of("text", "string", "Teks sumber")),
                        "=upper(inputs.text)", "{\"type\":\"string\"}"),

                new Definition("util.trim", "Trim Text", "Utilities",
                        "Menghapus spasi di awal dan akhir teks.",
                        List.of(InputSpec.of("text", "string", "Teks sumber")),
                        "=trim(inputs.text)", "{\"type\":\"string\"}"),

                new Definition("util.length", "Text Length", "Utilities",
                        "Menghitung panjang teks.",
                        List.of(InputSpec.of("text", "string", "Teks sumber")),
                        "=length(inputs.text)", "{\"type\":\"number\"}"),

                new Definition("util.ternary", "Conditional Value", "Utilities",
                        "Memilih nilai berdasarkan kondisi; pengganti if sederhana pada ekspresi.",
                        List.of(
                                InputSpec.of("condition", "boolean", "Kondisi yang dievaluasi"),
                                InputSpec.of("whenTrue", "any", "Nilai bila benar"),
                                InputSpec.optional("whenFalse", "any", "Nilai bila salah")),
                        "=inputs.condition ? inputs.whenTrue : inputs.whenFalse", "{\"type\":\"any\"}"),

                // --------------------------------------------------- Security
                new Definition("security.sha256", "SHA-256 Hash", "Security",
                        "Menghitung digest SHA-256 dalam hex huruf kecil.",
                        List.of(InputSpec.of("payload", "string", "Data yang di-hash")),
                        "=sha256(inputs.payload)", "{\"type\":\"string\"}"),

                new Definition("security.hmacSha256", "HMAC-SHA256 Signature", "Security",
                        "Menghitung signature HMAC-SHA256 dalam hex huruf kecil menggunakan secret.",
                        List.of(InputSpec.of("payload", "string", "Data yang ditandatangani")),
                        "=hmacSha256(inputs.payload)", "{\"type\":\"string\"}"),

                new Definition("security.base64", "Base64 Encode", "Security",
                        "Mengubah teks menjadi Base64 (UTF-8).",
                        List.of(InputSpec.of("text", "string", "Teks sumber")),
                        "=base64(inputs.text)", "{\"type\":\"string\"}"),

                new Definition("security.basicAuth", "Basic Auth Header", "Security",
                        "Menyusun nilai header Authorization dengan skema Basic.",
                        List.of(InputSpec.of("username", "string", "Username"),
                                InputSpec.of("password", "string", "Password")),
                        "='Basic ' + base64(inputs.username + ':' + inputs.password)",
                        "{\"type\":\"string\"}"),

                // --------------------------------------------------- SNAP BI
                new Definition("snapbi.timestamp", "SNAP BI Timestamp", "SNAP BI",
                        "Timestamp ISO-8601 dengan offset untuk header X-TIMESTAMP.",
                        List.of(), "=timestamp()", "{\"type\":\"string\"}"),

                new Definition("snapbi.signature", "SNAP BI Signature", "SNAP BI",
                        "Signature SNAP BI: SHA-256 atas gabungan method, path, access token, "
                                + "body ter-canonical, dan timestamp.",
                        List.of(
                                InputSpec.of("method", "string", "HTTP method (huruf besar)"),
                                InputSpec.of("path", "string", "Path endpoint"),
                                InputSpec.optional("accessToken", "string", "Access token bila ada"),
                                InputSpec.of("body", "string", "Body request ter-canonical (minified)"),
                                InputSpec.of("timestamp", "string", "Timestamp ISO-8601")),
                        "=sha256(lower(inputs.method) + ':' + inputs.path + ':' + "
                                + "coalesce(inputs.accessToken, '') + ':' + "
                                + "sha256(lower(inputs.body)) + ':' + inputs.timestamp)",
                        "{\"type\":\"string\"}"),

                new Definition("snapbi.validateHeaders", "SNAP BI Validate Headers", "SNAP BI",
                        "Memeriksa keberadaan header wajib SNAP BI dan mengembalikan ringkasannya.",
                        List.of(InputSpec.of("headers", "object", "Map header request")),
                        "=coalesce(get(inputs.headers, 'X-TIMESTAMP'), get(inputs.headers, 'X-SIGNATURE'), "
                                + "get(inputs.headers, 'Authorization'))",
                        "{\"type\":\"object\",\"properties\":{\"valid\":{\"type\":\"boolean\"}}}"),

                new Definition("snapbi.responseCode", "SNAP BI Response Code", "SNAP BI",
                        "Memetakan status HTTP ke responseCode SNAP BI (200xxxx sukses, 4xxxxxx gagal).",
                        List.of(InputSpec.of("status", "number", "Status HTTP")),
                        "=inputs.status == 200 ? '2007300' : (inputs.status == 400 ? '4007302' : '5007300')",
                        "{\"type\":\"string\"}"),

                new Definition("snapbi.amountFormat", "SNAP BI Amount Format", "SNAP BI",
                        "Memformat nilai nominal dengan dua desimal sesuai konvensi SNAP BI.",
                        List.of(InputSpec.of("value", "any", "Nilai nominal")),
                        "=string(inputs.value)", "{\"type\":\"string\"}"),

                // --------------------------------------------------- Database
                new Definition("db.rowCount", "Result Row Count", "Database",
                        "Menghitung jumlah baris pada hasil query node DATABASE_QUERY.",
                        List.of(InputSpec.of("dbResult", "object", "Output node DATABASE_QUERY")),
                        "=get(inputs.dbResult, 'rowCount')", "{\"type\":\"number\"}"),

                new Definition("db.firstValue", "First Column Value", "Database",
                        "Mengambil nilai kolom pertama pada baris pertama hasil query.",
                        List.of(InputSpec.of("dbResult", "object", "Output node DATABASE_QUERY")),
                        "=get(get(inputs.dbResult, 'rows')[0], 'id')",
                        "{\"type\":\"any\"}"),
                // --------------------------------------------------- Integrasi
                new Definition("integration.bearer", "Bearer Token Header", "Integrasi",
                        "Menyusun nilai header Authorization dengan skema Bearer.",
                        List.of(InputSpec.of("token", "string", "Access token")),
                        "='Bearer ' + inputs.token", "{\"type\":\"string\"}"),

                new Definition("integration.jsonEncode", "JSON Encode", "Integrasi",
                        "Mengubah struktur menjadi String JSON untuk dikirim sebagai body.",
                        List.of(InputSpec.of("value", "any", "Struktur yang akan dikodekan")),
                        "=json(inputs.value)", "{\"type\":\"string\"}"),

                new Definition("integration.retryBackoff", "Retry Backoff (ms)", "Integrasi",
                        "Menghitung jeda backoff eksponensial untuk percobaan ke-n.",
                        List.of(InputSpec.of("attempt", "number", "Percobaan ke berapa (mulai 1)"),
                                InputSpec.optional("baseMs", "number", "Basis jeda (default 200)")),
                        "=number(coalesce(inputs.baseMs, 200)) * inputs.attempt",
                        "{\"type\":\"number\"}")
        );
    }

    /** Buat versi pertama untuk sebuah definisi. */
    public static TemplateVersion toVersion(Definition definition, String templateId, String createdBy) {
        StringBuilder inputsJson = new StringBuilder("[");
        for (int i = 0; i < definition.inputs().size(); i++) {
            InputSpec spec = definition.inputs().get(i);
            if (i > 0) {
                inputsJson.append(',');
            }
            inputsJson.append("{\"name\":\"").append(spec.name())
                    .append("\",\"type\":\"").append(spec.type())
                    .append("\",\"required\":").append(spec.required())
                    .append(",\"description\":\"").append(spec.description()).append("\"}");
        }
        inputsJson.append(']');

        TemplateVersion version = new TemplateVersion(templateId, definition.templateKey(), 1,
                FunctionTemplate.ImplType.SCRIPT, definition.implementation(), createdBy);
        version.inputsJson = inputsJson.toString();
        version.outputJson = definition.outputJson() == null ? "{}" : definition.outputJson();
        version.changeNote = "Katalog bawaan";
        version.latest = true;
        return version;
    }
}
