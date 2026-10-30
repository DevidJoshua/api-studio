# STATUS PROYEK — HANDOFF (diperbarui 30 Okt 2026)

Catatan titik henti pekerjaan. **Bukan dokumentasi teknis**; untuk detail
arsitektur lihat [`CONTEXT.MD`](CONTEXT.MD), untuk cara menjalankan lihat
[`README.md`](README.md).

---

## 1. Ringkasan

Implementasi **Platform API Management Low-Code** berdasarkan
`brd/BRD Platform API Management Low-Code.md` v0.1, dibangun ulang dari nol.

| Aspek | Keputusan |
| --- | --- |
| Cakupan | Monorepo MVP end-to-end (Studio + Control Plane + Flow Engine) |
| Backend | Java 17, Spring Boot 3.5.16, Maven multi-modul |
| Penyimpanan | H2 in-memory, skema JPA agar mudah pindah ke PostgreSQL |
| UI | React 18 + TypeScript strict + Vite 5 + Tailwind 3, tema gelap enterprise |
| Editor kode | Dibangun sendiri (textarea + tokenizer), tanpa dependensi eksternal |

---

## 2. Status verifikasi

| Cek | Hasil |
| --- | --- |
| `mvn test` | **148 lulus** (flow-engine 81, control-plane 67), BUILD SUCCESS |
| `npm run typecheck` | lulus |
| `npm run build` | lulus (tanpa warning) |
| Smoke test end-to-end | login → kompilasi → test suite → preflight → publish → deploy → **gateway HTTP 200** |

---

## 3. Test penting

- `ExpressionEngineTest` (20) — path, template, aritmetika presisi,
  kriptografi (SHA-256/HMAC nilai acuan RFC 4231), waktu deterministik.
- `FlowEngineTest` (30) — linear, kondisi, switch, try/catch, loop, paralel,
  retry, `onError` CONTINUE/FALLBACK, HTTP mock, sandbox DB + rollback,
  masking, node disabled, timeout, template invoker.
- `SandboxClassLoaderTest` (12) — child-first, parent-first, daftar blokir.
- `FlowValidatorTest` (19) — trigger/response wajib, port valid, siklus,
  ekspresi invalid, SQL non-SELECT, fallback wajib.
- `JavaSourceCompilerTest` (18) — bytecode, **pemetaan baris error**,
  validasi koordinat Maven, filter dependensi, **nama biner kelas**,
  deteksi API terlarang, `ContractClasspath`.
- `AssertionEngineTest` (18) — status/header/JSONPath/schema/DB/trace/operator.
- `ControlPlaneFlowTest` (31) — login (+ **daftar izin**), 401, 403 RBAC,
  katalog template, alur lengkap publish/deploy/gateway/rollback,
  approval production, secret masked, **node Java kompilasi + eksekusi
  end-to-end**.

---

## 4. Cacat yang ditemukan & diperbaiki (dicatat sebagai regresi)

1. **Rute tidak dikenal mengembalikan 500** → dipetakan ke `404`/`405`.
2. **Gateway tidak memvalidasi API key** → kini mencocokkan hash consumer
   (401/403) dan menegakkan `allowedApiIds`.
3. **Gateway mengabaikan seed versi** → flow yang membaca tabel sandbox selalu
   gagal; kini seed versi/release diteruskan ke engine.
4. **Login tidak menyertakan daftar izin** → UI tidak dapat menegakkan RBAC;
   kini `user.permissions` disertakan.
5. **Kompilasi gagal saat dijalankan dari jar Spring Boot** → `ContractClasspath`
   mengekstrak kontrak/engine ke cache lalu memakainya sebagai classpath.
6. **Bytecode disimpan dengan nama path, bukan nama biner** → kode tidak dapat
   dimuat saat runtime; kini memakai nama biner.
7. **Editor fullscreen & panel dirender bersamaan** → ref saling menimpa;
   kini hanya satu instance dirender pada satu waktu.
8. **Esc saling bentrok antar overlay** → editor fullscreen menandai body,
   overlay induk tidak ikut menutup.

---

## 5. Yang tersisa / bisa dilanjutkan

Semua kebutuhan **M** (Must) BRD sudah diimplementasikan. Sisa pekerjaan
bersifat opsional (prioritas S/C) atau peningkatan di luar MVP:

| Item | Catatan |
| --- | --- |
| Debugger interaktif (FR-SIM-11) | breakpoint, step, ubah variabel |
| Data-driven test CSV (FR-SIM-10) | field tersimpan, eksekusi multi-baris |
| Mock `record/replay` (FR-SIM-04) | selain MOCK/REAL/ERROR_* |
| Template `JAVA` | kompilasi template terpisah dari node Java |
| Integrasi Vault/KMS | saat ini AES-GCM dengan kunci lokal |
| Gateway nyata (APISIX/Kong/Envoy) | `/gw/**` masih simulasi in-process |
| Pisah Flow Engine ke proses sendiri | kontrak sudah bersih, tinggal transport |

---

## 6. Perintah penting

```powershell
cd D:\project_hustle\api_manager

mvn test                                     # 148 test
mvn -q install -DskipTests
mvn -pl control-plane spring-boot:run         # port 8080

cd frontend
npm run typecheck
npm run build
npm run dev                                   # port 5173
```

---

## 7. Aturan kerja yang harus dijaga

- **Gaya visual hanya di `src/ui/`.** Halaman mengimpor komponen dari `../ui`,
  tidak menulis kelas Tailwind mentah untuk kontrol berulang.
- **Editor kode hanya dari `src/ui/editor/`.** Jangan menambah dependensi
  editor eksternal (Monaco/CodeMirror) tanpa alasan kuat.
- Backend: jangan menambah dependensi baru tanpa alasan kuat.
- Semua perubahan backend harus tetap membuat `mvn test` hijau.
- Bahasa komentar dan pesan pengguna: Bahasa Indonesia (mengikuti BRD).
