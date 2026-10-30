# CONTEXT.MD — Dokumentasi Teknis Platform API Management Low-Code

Dokumen ini menjelaskan implementasi nyata (bukan rencana) dari
`brd/BRD Platform API Management Low-Code.md` v0.1. Semua pernyataan di sini
mengacu pada kode di repositori dan dapat diverifikasi dengan menjalankan
`mvn test`.

- Verifikasi terakhir: `mvn test` → **148 test lulus** (flow-engine 81,
  control-plane 67), BUILD SUCCESS.
- Frontend: `npm run typecheck` dan `npm run build` lulus (Vite 5, React 18,
  Tailwind 3).
- Smoke test end-to-end terhadap proses hidup: login → kompilasi Java →
  test suite → preflight → publish → deploy → gateway **HTTP 200**.

---

## 1. Struktur repositori

Maven multi-modul (Java 17, Spring Boot 3.5.16):

| Modul | Tanggung jawab | Dependensi |
| --- | --- | --- |
| `engine-contracts` | Kontrak murni: skema graph, kontrak eksekusi, SPI node. Tanpa Spring. | Jackson |
| `flow-engine` | Interpreter flow, ekspresi, sandbox Java, sandbox database, validator. | `engine-contracts` |
| `control-plane` | REST API, persistensi JPA, compiler, publish/deploy, gateway MVP. | `flow-engine` |
| `frontend/` | Studio React + TypeScript (Vite), bukan modul Maven. | — |
| `brd/` | Dokumen persyaratan (di-ignore Git). | — |

Arah dependensi satu arah: **control-plane → flow-engine → engine-contracts**.
`flow-engine` **tidak pernah** mengimpor kelas `io.apistudio.cp.*`, sehingga
pemisahan menjadi service terpisah tidak menuntut penulisan ulang engine.

```
pom.xml
├── engine-contracts/src/main/java/io/apistudio/contracts/
│   ├── flow/   FlowDefinition, FlowNode, FlowEdge, NodeType, ErrorPolicy, Position
│   ├── exec/   ExecutionRequest, ExecutionResult, TraceStep
│   └── node/   JavaNode, NodeContext            (SPI untuk kode Java pengguna)
├── flow-engine/src/main/java/io/apistudio/engine/
│   ├── FlowEngine.java
│   ├── expr/   ExpressionEngine, Crypto, Json
│   ├── nodes/  16 eksekutor node
│   ├── runtime/ FlowWalker, FlowRuntimeContext, SandboxDatabase, MockRegistry,
│   │            TraceRecorder, Masking, EngineSettings, SecretResolver
│   ├── java/   JavaCodeRunner, SandboxClassLoader, ClassLoaderCache, ArtifactResolver
│   └── validation/ FlowValidator, ValidationIssue
├── control-plane/src/main/java/io/apistudio/cp/
│   ├── web/      Controller per domain (auth, api, publish, test, template, admin, gateway)
│   ├── service/  ApiService, PublishService, TestRunnerService, GatewayService,
│   │             CompileService, SecretService, ObservabilityService, AssertionEngine,
│   │             OpenApiService, AuditService, TemplateService, TemplateInvokerService
│   ├── domain/   17 entity JPA
│   ├── repo/     18 repository Spring Data
│   ├── compiler/ JavaSourceCompiler, MavenLibraryResolver, ContractClasspath
│   ├── security/ AuthService, ApiKeys, Role, Permission, CurrentUser
│   ├── bootstrap/PlatformBootstrap, BuiltinTemplateCatalog
│   └── config/   EngineConfig, WebConfig
└── frontend/src/
    ├── ui/          komponen dasar + editor/ (CodeEditor, Java/Sql/Json/ExpressionEditor, tokenizer)
    ├── api/         client (token, error), endpoints, types
    ├── app/         AuthContext, hooks, icons, AppShell
    ├── lib/         format (tanggal/JSON/error), flowCode, snippets
    ├── features/designer/  FlowCanvas, NodePalette, NodeConfigPanel, SimulationPanel,
    │                       useFlowEditor, nodeCatalog
    └── pages/       ApiArchive, WorkflowCanvas, DataSources, Templates, Playground,
                     Deployments, Approvals, Observability, Audit, Login
```

---

## 2. Pemetaan BRD → implementasi

| Kebutuhan BRD | Implementasi |
| --- | --- |
| FR-API-01/02 — CRUD API & grup | `ApiController` (`/api/apis`, `/api/groups`), `ApiService`, entity `ApiEntity`, `ApiGroup` |
| FR-API-03 — kebijakan (auth, rate limit, IP whitelist, CORS) | `ApiEntity.policyJson`; ditegakkan di `GatewayService` (lihat Bab 7) |
| FR-API-04 — versi API, immutable setelah publish | `ApiVersion` + `Status`; guard `ApiService.requireMutableVersion` |
| FR-API-05 — consumer & API key | entity `Consumer`, `/api/consumers`, `security/ApiKeys` (SHA-256), validasi di gateway |
| FR-API-06 — dokumentasi OpenAPI | `OpenApiService`, `GET /api/apis/{id}/openapi` |
| FR-API-07 — nonaktifkan API & environment | `ApiEntity.disabled` → `503`; `EnvironmentService`, instance per environment |
| FR-FLOW-01..07 — designer visual | `FlowDefinition`/`FlowNode`/`FlowEdge`; kanvas `FlowCanvas` + `useFlowEditor` |
| FR-FLOW-06 — kebijakan error per node | `ErrorPolicy` (FAIL/CONTINUE/FALLBACK, retry, timeout, backoff), diterapkan `FlowWalker` |
| FR-FLOW-07 — ekspresi | `ExpressionEngine` (path, template, aritmetika, kriptografi, fungsi waktu deterministik) |
| FR-TPL-01..07 — function template | `FunctionTemplate`/`TemplateVersion`, `BuiltinTemplateCatalog` (20 template termasuk SNAP BI), `TemplateInvokerService` |
| FR-JAVA-01..08 — kode Java & library | `JavaSourceCompiler` (in-memory + pemetaan baris), `MavenLibraryResolver`, `SandboxClassLoader`, `JavaCodeRunner` |
| FR-SIM-01..09 — simulasi & test | `TestRunnerService`, `AssertionEngine`, `SimulationPanel`, gate di `PublishService` |
| FR-SIM-04 — mock dependensi | `MockRegistry`, `ExecutionRequest.HttpMock` (MOCK/REAL/ERROR_TIMEOUT/ERROR_5XX) |
| FR-SIM-05/07 — sandbox DB read-only | `SandboxDatabase` (H2 per-run, seed, rollback, SELECT-only) |
| FR-DEP-01..08 — publish/deploy/canary/rollback | `PublishService`, `ReleaseBundle`, `Deployment`, `PublishController` |
| FR-SEC-01..06 — RBAC, secret, audit, observability | `Role`/`Permission`, `SecretService` (AES-GCM), `AuditService`, `ObservabilityService` |
| NFR-06 — kebocoran metaspace | `SandboxClassLoader.close()`, `ClassLoaderCache.evict*` |

---

## 3. Arsitektur: control plane vs data plane

```
       ┌─────────────────────────────┐         ┌──────────────────────────┐
       │        STUDIO (UI)          │         │   KONSUMEN API EKSTERNAL │
       │  React + TS + Tailwind      │         │   (partner SNAP BI, dsb.)│
       └──────────────┬──────────────┘         └────────────┬─────────────┘
                      │ /api/** (sesi + RBAC)               │ /gw/** (API key)
       ┌──────────────▼──────────────────────────────────────▼─────────────┐
       │                     CONTROL PLANE (Spring Boot)                   │
       │  AuthService · ApiService · PublishService · TestRunnerService    │
       │  CompileService · SecretService · ObservabilityService · Audit    │
       │  GatewayService  ── (pada MVP menjalankan engine in-process)      │
       └──────────────────────────────┬────────────────────────────────────┘
                                      │ memanggil kontrak ExecutionRequest/Result
       ┌──────────────────────────────▼────────────────────────────────────┐
       │                      FLOW ENGINE (library murni)                  │
       │  FlowWalker · NodeExecutorRegistry · ExpressionEngine             │
       │  SandboxDatabase · SandboxClassLoader · TraceRecorder · Masking   │
       └───────────────────────────────────────────────────────────────────┘
```

**Control plane**: siapa boleh mengubah apa, versi mana yang dipublikasikan,
rilis mana yang aktif, audit, secret, metrik.

**Data plane**: mengeksekusi flow pada setiap request.

Pada MVP keduanya berada di satu proses Spring Boot. Batasnya dijaga di
tingkat tipe, sehingga memindahkan engine ke proses sendiri hanya memerlukan
transport (gRPC/HTTP) yang membungkus `ExecutionRequest` → `ExecutionResult`.

---

## 4. Kontrak

### 4.1 Skema graph

`FlowDefinition` adalah satu-satunya format yang disimpan pada
`api_version.flowJson` dan `release_bundle.flowJson`.

```jsonc
{
  "name": "Transfer Antar Bank",
  "version": 1,
  "nodes": [
    { "id": "trigger", "type": "TRIGGER", "label": "Request Masuk",
      "config": {}, "position": { "x": 80, "y": 240 },
      "onError": { "timeoutMs": null, "retries": 0, "backoffMs": 0,
                   "onError": "FAIL", "fallbackNodeId": null },
      "disabled": false }
  ],
  "edges": [
    { "id": "e1", "from": "trigger", "to": "node_validate",
      "fromPort": "out", "label": null }
  ]
}
```

- **Port**: `out`, `true`, `false`, `error`, serta port bernama untuk
  `SWITCH`/`PARALLEL`/`LOOP`.
- **16 `NodeType`**: `TRIGGER`, `SET_VARIABLE`, `TRANSFORM`, `CONDITION`,
  `SWITCH`, `LOOP`, `PARALLEL`, `TRY_CATCH`, `HTTP_CALL`, `DATABASE_QUERY`,
  `TEMPLATE_CALL`, `JAVA_CODE`, `RESPONSE`, `LOG`, `DELAY`, `FAIL`.
- `Position` hanya untuk tata letak kanvas; engine mengabaikannya.

### 4.2 Kontrak eksekusi

```java
ExecutionRequest(apiId, flowVersion, method, path,
                 Map<String,String> headers, Map<String,String> query, Object body,
                 boolean debug, List<HttpMock> mocks, List<DbSeed> seed, String traceId)
```

```java
ExecutionResult(int status, String statusText, Map<String,String> headers, Object body,
                String traceId, List<TraceStep> trace, long durationMs,
                List<String> logs, List<String> touchedTables)
```

`TraceStep` adalah unit observability per node: `nodeId`, `nodeType`, `label`,
`status` (OK/ERROR/RETRIED/DISABLED/SKIPPED), `port`, input, output,
`durationMs`, `error`, `logs`, `attempt`.

### 4.3 SPI untuk kode Java pengguna

Kode Java **tidak** mengimplementasikan antarmuka apa pun; pengguna menulis isi
method `run()`, kompiler membungkusnya:

```java
String id = ctx.input("id") == null ? "-" : ctx.input("id").toString();
ctx.set("hasild", id);
Object ts = ctx.callTemplate("snapbi.timestamp@1", java.util.Map.of());
return java.util.Map.of("id", id, "processedAt", ts);
```

`NodeContext` menyediakan `get/set`, `input`, `inputMap`, `headers`, `query`,
`eval`, `log`, `callTemplate`, `nodeId`. `JavaNode` disediakan sebagai kontrak
formal untuk kasus lanjutan (kelas penuh dari JAR).

### 4.4 Format release bundle

| Kolom | Isi |
| --- | --- |
| `flowJson` | definisi graph |
| `javaSourcesJson` | source Java per node |
| `bytecodeJson` | bytecode Base64 + JAR per node |
| `policyJson` | snapshot kebijakan API |
| `mocksJson`, `seedsJson` | mock & seed default versi |
| `artifactHash` | SHA-256 atas `flow\|bytecode\|policy\|mocks\|seeds\|javaSources` |
| `testSummaryJson` | ringkasan test suite saat gate publish |

`artifactHash` dipakai memverifikasi semua instance menjalankan artifact
identik (FR-DEP-07).

---

## 5. Runtime

### 5.1 `FlowWalker`

1. Mulai dari node `TRIGGER` (atau node tanpa edge masuk).
2. `NodeExecutorRegistry` memilih eksekutor berdasarkan `NodeType`.
3. Eksekutor mengembalikan `NodeResult` (port keluar + output).
4. Pilih edge pertama pada port yang cocok, lanjut ke target.
5. Berhenti saat `RESPONSE` (port `null`) atau tidak ada edge keluar.

Penanganan khusus: **Condition/Switch** memilih port sesuai hasil;
**Loop** mengeksekusi sub-graph per item; **Parallel** menjalankan cabang
dengan *join* deterministik sesuai urutan deklarasi edge; **TryCatch**
menangkap kegagalan sub-graph dan menjalankan port `error`; **retry/timeout/
backoff/fallback** dibaca dari `ErrorPolicy`.

Batas pengaman: `maxSteps` (mencegah siklus tak berujung) dan
`flowTimeoutMs` (global). Validator menolak graf bersiklus sebelum eksekusi.

### 5.2 Kebijakan error

| `onError` | Perilaku |
| --- | --- |
| `FAIL` (default) | flow berhenti; hasil membawa error |
| `CONTINUE` | lanjut ke edge berikutnya; error tetap di trace |
| `FALLBACK` | lompat ke `fallbackNodeId` |

### 5.3 Trace & masking

`TraceRecorder` mencatat setiap node (dibatasi `maxTraceSteps`) dan `Masking`
menutupi nilai sensitif berdasarkan nama kunci (`password`, `token`, `secret`,
`authorization`, `signature`, `account_no`, `nik`, …) sebelum disimpan.

### 5.4 Sandbox database

- H2 in-memory **baru per eksekusi** (`jdbc:h2:mem:sandbox_<traceId>`).
- Seed versi/release dimuat di awal; tabel dibuat dari bentuk baris pertama.
- Hanya `SELECT` yang diizinkan; DDL/DML/komentar/multi-statement ditolak.
- Run berjalan dalam satu transaksi lalu **di-rollback** saat ditutup.
- `maxRows` membatasi ukuran hasil; kolom dinormalkan huruf kecil.

Sandbox ini untuk **pengujian**, bukan koneksi database produksi.

---

## 6. Isolasi kode Java

### 6.1 Kompilasi

`JavaSourceCompiler` memakai compiler JDK in-memory (`javax.tools`), tanpa
menulis `.java` ke disk:

1. Bungkus source menjadi kelas `io.apistudio.user.Node_<nodeId>` yang
   mengimplementasikan `JavaNode`.
2. Kompilasi dengan classpath `ContractClasspath` + JAR dari
   `MavenLibraryResolver`.
3. **Pemetaan baris error** ke nomor baris kode pengguna (offset pembungkus).
4. Validasi koordinat Maven, filter dependensi `test`/`provided`/`optional`,
   deteksi API terlarang (termasuk bentuk tanpa kualifikasi paket).
5. Bytecode disimpan dengan **nama biner kelas** agar dapat dimuat
   `SandboxClassLoader`.

**Classpath kompilasi (`ContractClasspath`)** — masalah nyata yang
diselesaikan: saat aplikasi berjalan dari **jar Spring Boot**, kelas berada di
arsip bersarang (`BOOT-INF/lib/*.jar`) dan `java.class.path` hanya berisi
`boot-launcher`, sehingga javac gagal dengan *"package
io.apistudio.contracts.node does not exist"*. Solusinya mengekstrak artefak
kontrak (`engine-contracts`, `flow-engine`, Jackson) ke direktori cache lalu
memakainya sebagai classpath. Jar aplikasi ditemukan dari
`sun.java.command` lalu `CodeSource` kelas.

### 6.2 Classloader

`SandboxClassLoader` per versi flow:

- **Child-first** untuk JAR pengguna (mencegah konflik versi).
- **Parent-first** untuk `java.*`, `javax.*`, `io.apistudio.contracts.*`,
  Jackson, SLF4J.
- **Blokir prefiks**: `java.io.`, `java.nio.file.`, `java.net.`,
  `java.lang.reflect.`, `javax.naming.`, `javax.script.`, `jdk.internal.`,
  `sun.misc.`.
- **Blokir kelas**: `Runtime`, `ProcessBuilder`, `System`, `Thread`,
  `ClassLoader`, `SecurityManager`, `DriverManager`, `Executors`,
  `ForkJoinPool`, `CompletableFuture`, dll.
- **Pengecualian**: `StringConcatFactory`, `LambdaMetafactory`,
  `MethodHandles*` (dibutuhkan bytecode Java 9+, tanpa akses I/O).

### 6.3 Siklus hidup (NFR-06)

`ClassLoaderCache` menyimpan classloader per versi + node. Setelah versi
di-drain, `evict`/`evictByPrefix` memanggil `close()` sehingga kelas dapat
di-GC dan metaspace tidak bocor.

### 6.4 Batasan keamanan — jujur

Ini **bukan sandbox proses**. Isolasi hanya classloader terpisah, daftar
class terlarang, dan validasi string. Perlindungan menyeluruh menuntut
proses/container terpisah dengan seccomp/AppArmor dan jaringan tertutup.
Sesuai BRD Bagian 11, MVP menyasar **tim internal tepercaya**, bukan eksekusi
kode multi-tenant dari pihak tak dikenal.

---

## 7. Gateway (data plane) & kebijakan

`GatewayController` melayani `/gw/**`. Urutan pemeriksaan:

1. **Routing** `method + path` (tidak dikenal → `404`).
2. **API dinonaktifkan** → `503 API_DISABLED`.
3. **Rate limit** fixed window per menit per API per environment → `429`.
4. **IP whitelist** → `403 IP_NOT_ALLOWED`.
5. **Autentikasi** (bila `policy.auth != none`):
   - tanpa kredensial → `401`;
   - **tidak cocok dengan consumer** → `401`;
   - consumer tidak berhak → `403`.
6. **Versi aktif** environment (fallback PUBLISHED terbaru) → `503` bila kosong.
7. **Prefer release bundle** (immutable) alih-alih draft.
8. Eksekusi flow dengan **mock & seed versi**.
9. Catat log + metrik; kembalikan body, header, `X-Trace-Id`.

### 7.1 Consumer dan API key

- Dibuat di `POST /api/consumers`; nilai asli dikembalikan **sekali**.
- Disimpan hanya `apiKeyHash` (SHA-256) dan `apiKeyHint` (`****abcd`).
- Verifikasi memakai `MessageDigest.isEqual` (perbandingan waktu konstan).
- Klien boleh mengirim lewat `X-API-Key` atau `Authorization: Bearer <key>`.
- `Consumer.allowedApiIds` (dipisah koma); kosong berarti semua API.

---

## 8. Publish, deploy, canary, rollback

```
DRAFT --preflight--> [validasi]+[kompilasi]+[test suite]+[approval?]
      --publish----> ReleaseBundle immutable (artifactHash)
      --deploy-----> Deployment ACTIVE (DIRECT | CANARY); versi lama DRAINING
      --rollback---> Deployment sebelumnya ACTIVE kembali
```

- **Preflight** mengembalikan checklist: validasi flow, status kompilasi,
  hasil test suite, kebutuhan approval, status versi.
- **Approval**: environment ber-flag `requiresApproval` menghasilkan `Approval`
  dengan diff flow & kode Java; reviewer menyetujui/menolak.
- **Publish** menghitung `artifactHash`, menyalin snapshot lengkap, menandai
  versi `PUBLISHED` (immutable). Ditolak bila preflight gagal.
- **Deploy** memeriksa kesiapan seluruh instance (FR-DEP-07); versi lama
  dipindah ke `DRAINING`, routing environment diperbarui.
- **Canary**: bobot disimpan dan diubah bertahap; pembagian traffic nyata ada
  di gateway (APISIX/Kong/Envoy) — pada MVP `/gw/**` tidak memecah traffic.
- **Rollback** mengaktifkan kembali deployment sebelumnya; request berjalan
  diselesaikan dahulu (drain).

---

## 9. Keamanan

### 9.1 RBAC

Hak akses granular (`Permission`) dipetakan ke peran (`Role`) dan ditegakkan
`AuthService.require(request, permission)`.

| Peran | Ringkas |
| --- | --- |
| `ADMIN` | semua izin |
| `DESIGNER` | tulis API/flow/Java/test, minta publish, deploy |
| `REVIEWER` | baca, jalankan test, **menyetujui publish** |
| `QA` | baca, tulis & jalankan test (tidak mengubah API) |
| `OPERATOR` | deploy, rollback, environment, observability |
| `AUDITOR` | baca + audit + observability (read-only) |

Login menghasilkan daftar `permissions` agar Studio dapat menyembunyikan tab
dan tombol tanpa menebak dari nama peran. Sesi in-memory (MVP); produksi
memakai JWT/SSO.

### 9.2 Secret

`SecretService` menyimpan nilai terenkripsi **AES-GCM** (kunci 256-bit
diturunkan SHA-256 dari konfigurasi). API hanya mengembalikan petunjuk masked;
flow merujuk secret berdasarkan nama. Field `vaultPath` tersedia untuk jalur
Vault/KMS (integrasi belum aktif).

### 9.3 Audit

`AuditService` mencatat aksi penting (API/version dibuat, flow/Java disimpan,
test dijalankan, publish, deploy, rollback, secret, consumer, user) beserta
aktor dan diff. Dibaca lewat `GET /api/audit`.

---

## 10. Observability

- `RequestLog` per request runtime: method, path, status, latency, traceId,
  trace, pesan error.
- `ApiMetric` agregat **per API per environment per menit**: jumlah request,
  error (`status >= 500`), total & maksimum latensi.

`GET /api/observability/metrics` mengembalikan seri waktu + ringkasan
(`totalRequests`, `totalErrors`, `avgLatencyMs`, `maxLatencyMs`, `errorRate`)
dan alert berbasis ambang. Grafik Studio digambar dengan SVG tanpa library
chart. Jalur produksi: ekspor ke Prometheus/OTel.

---

## 11. Frontend (Studio)

- React 18 + TypeScript strict + Vite 5 + Tailwind 3. Tema gelap enterprise.
- **Lapisan UI terpusat** di `src/ui/` — halaman tidak menulis kelas Tailwind
  mentah untuk kontrol berulang. Mengganti tema berarti mengubah folder ini.
- **Editor kode khusus** di `src/ui/editor/`, dibangun tanpa dependensi
  eksternal (bundle tetap React saja):
  - `CodeEditor` — fondasi: textarea transparan + lapisan highlight, nomor
    baris, auto-indent, auto-pair, indent blok (Tab/Shift+Tab),
    **mode layar penuh** (Esc), skrip sinkron scroll.
  - `JavaEditor` — gaya IntelliJ: autocomplete `ctx.*`, snippet, kelas JDK,
    method umum String/Map/List.
  - `SqlEditor` — keyword SQL + saran **tabel/kolom dari seed versi**.
  - `JsonEditor` — validasi langsung, tombol Rapikan, saran kunci lazim.
  - `ExpressionEditor` — variabel `request`/`vars`, **variabel flow yang
    dihasilkan node lain**, fungsi engine.
  - `tokenizer.ts` — tokenizer bersama (Java/SQL/JSON/ekspresi);
    `CodeBlock` read-only ikut ber-highlight & auto-deteksi JSON.
- **Panel dapat diperbesar**: panel bawah (resizable + layar penuh), panel
  konfigurasi node (layar penuh), palette (drawer di layar sempit), setiap
  editor kode (layar penuh).
- Fitur designer: kanvas drag & drop (`FlowCanvas`), palette node + template,
  konfigurasi per jenis node, simulasi & trace viewer, validasi, preflight,
  preview kode deklaratif.

---

## 12. Menjalankan dan menguji

```powershell
cd D:\project_hustle\api_manager

mvn test                                     # 148 test
mvn -q install -DskipTests
mvn -pl control-plane spring-boot:run         # http://localhost:8080

cd frontend
npm install
npm run typecheck
npm run build
npm run dev                                   # http://localhost:5173, proxy ke 8080
```

Jika port 8080 dipakai proses lain, jalankan Control Plane di port berbeda dan
arahkan Studio ke sana:

```powershell
java -jar control-plane/target/control-plane-1.0.0-SNAPSHOT.jar --server.port=8090
# di frontend/
$env:APISTUDIO_BACKEND_URL = "http://localhost:8090"; npm run dev
```

### 12.1 Smoke test manual (terverifikasi)

```powershell
$base='http://localhost:8090'
$t = (Invoke-RestMethod "$base/api/auth/login" -Method Post -ContentType 'application/json' `
      -Body '{"username":"admin","password":"admin123"}').token
$H = @{ Authorization = "Bearer $t" }

$api = (Invoke-RestMethod "$base/api/apis" -Headers $H |
        Where-Object { $_.path -like '*transfer-intrabank*' })[0]
$ver = (Invoke-RestMethod "$base/api/apis/$($api.id)" -Headers $H).versions[0]

# kompilasi -> test suite -> preflight
Invoke-RestMethod "$base/api/versions/$($ver.id)/compile" -Method Post -Headers $H
Invoke-RestMethod "$base/api/apis/$($api.id)/tests/run" -Method Post -Headers $H
Invoke-RestMethod "$base/api/versions/$($ver.id)/preflight?environment=dev" -Headers $H

# publish -> deploy -> gateway
$rel = Invoke-RestMethod "$base/api/versions/$($ver.id)/publish" -Method Post -Headers $H `
        -ContentType 'application/json' -Body '{"environment":"dev"}'
Invoke-RestMethod "$base/api/releases/$($rel.id)/deploy" -Method Post -Headers $H `
        -ContentType 'application/json' -Body '{"environment":"dev","strategy":"DIRECT"}'
$c = Invoke-RestMethod "$base/api/consumers" -Method Post -Headers $H `
      -ContentType 'application/json' -Body '{"name":"Demo","allowedApiIds":"' + $api.id + '"}'

Invoke-RestMethod "$base/gw/v1.0/transfer-intrabank?environment=dev" -Method Post `
  -Headers @{ 'X-API-Key' = $c.apiKey } -ContentType 'application/json' `
  -Body '{"partnerReferenceNo":"REF-1","amount":{"value":"15000.00","currency":"IDR"},"beneficiaryAccountNo":"1234567890"}'
# → 200 { "responseCode":"2007300", ... }
```

### 12.2 Akun demo (`PlatformBootstrap`)

| Username | Password | Peran |
| --- | --- | --- |
| admin | admin123 | Administrator |
| designer | password123 | API Designer |
| reviewer | password123 | Reviewer |
| qa | password123 | QA |
| operator | password123 | Operator |
| auditor | password123 | Auditor |

---

## 13. Menambah node type baru

1. Tambah nilai di `engine-contracts/.../flow/NodeType.java`.
2. Buat eksekutor di `flow-engine/.../nodes/`
   (`extends AbstractNodeExecutor`, implementasikan `doExecute`, tentukan port).
3. Daftarkan di `FlowEngine` (`NodeExecutorRegistry.register(...)`).
4. Tambah aturan validasi di `FlowValidator` bila perlu.
5. Tambah metadata di `frontend/src/features/designer/nodeCatalog.ts`
   (label, kategori, warna, port) dan editor khusus di `NodeConfigPanel`.
6. Tambah test di `FlowEngineTest` (jalur sukses + jalur error).

---

## 14. Keputusan desain & trade-off

| Keputusan | Alasan | Konsekuensi |
| --- | --- | --- |
| Engine sebagai library in-process | zero-setup, mudah diuji | belum memisahkan beban CPU data plane |
| Kolom JSON sebagai `String` | skema DB sederhana, portabel ke JSONB | perlu `JsonStore` untuk parsing toleran |
| H2 in-memory | tanpa infrastruktur | data hilang saat restart |
| SHA-256 untuk API key | deterministik, cepat, cukup untuk kredensial 128-bit | bukan KDF ber-salt (tidak relevan: entropi tinggi) |
| Classloader sebagai sandbox | cukup untuk kode tim internal | **bukan** pertahanan multi-tenant |
| Tokenizer sendiri, bukan Monaco | bundle kecil, kebutuhan terbatas | fitur terbatas (tanpa pemanggilan tipe lintas file) |
| Ekstraksi kontrak ke cache | mengatasi jar bersarang Spring Boot | ekstraksi sekali per proses |
| Masking berbasis nama kunci | deterministik, murah | nilai sensitif dengan nama tak lazim bisa lolos |

---

## 15. Batasan MVP yang diketahui (jujur)

1. **Flow Engine masih disematkan** di Control Plane; belum proses sendiri,
   belum gRPC.
2. **Gateway adalah simulasi** (`/gw/**`); APISIX/Kong/Envoy belum diintegrasi.
3. **Eksekusi Java bukan sandbox proses** (lihat 6.4).
4. **Template `JAVA`** ditolak dengan pesan jelas; `SCRIPT` dan `COMPOSITE`
   aktif. Kompilasi template Java terpisah belum diaktifkan.
5. **Debugger interaktif** (breakpoint, step, ubah variabel — FR-SIM-11)
   belum ada; tersedia trace per node + replay.
6. **Data-driven test dari CSV** (FR-SIM-10): field tersimpan, eksekusi
   multi-baris belum diimplementasikan.
7. **Mock `record/replay`** belum ada; tersedia `MOCK`, `REAL`,
   `ERROR_TIMEOUT`, `ERROR_5XX`.
8. **Assertion DB** memakai sandbox baru dengan seed yang sama (sandbox
   di-rollback), jadi menguji data awal bukan efek samping.
9. **Metrik** disimpan di tabel `api_metric`, bukan Prometheus.
10. **Approval** menyimpan diff + status; belum ada notifikasi/email.
11. **Secret** memakai AES-GCM dengan kunci lokal; integrasi Vault belum ada.
12. **Canary** menyimpan bobot tetapi pembagian traffic nyata belum
    disimulasikan di `/gw/**`.
