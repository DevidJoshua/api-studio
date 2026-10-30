# JavaFlow Studio — Platform API Management Low-Code

Platform untuk membangun API lewat designer visual, menjalankan kode Java pada
node, menguji dengan mock & sandbox database, lalu publish/deploy dan
memanggilnya melalui gateway.

Dokumentasi teknis lengkap: [`CONTEXT.MD`](CONTEXT.MD).

## Struktur

```
engine-contracts/   kontrak murni (graph, ExecutionRequest/Result, SPI node)
flow-engine/        interpreter flow, ekspresi, sandbox Java & DB, validator
control-plane/      REST API, JPA, compiler, publish/deploy, gateway /gw/**
frontend/           Studio React + TypeScript (Vite), bukan modul Maven
brd/                dokumen persyaratan (di-ignore Git)
```

## Prasyarat

- JDK 17
- Maven 3.9+
- Node.js 18+

## Menjalankan

Backend (default port 8080):

```powershell
mvn test                       # 148 test
mvn -q install -DskipTests
mvn -pl control-plane spring-boot:run
```

Bila port 8080 sudah dipakai proses lain:

```powershell
java -jar control-plane/target/control-plane-1.0.0-SNAPSHOT.jar --server.port=8090
```

Frontend (default 5173, proxy `/api` + `/gw` ke backend):

```powershell
cd frontend
npm install
npm run dev
# bila backend di 8090:
# $env:APISTUDIO_BACKEND_URL = "http://localhost:8090"; npm run dev
```

Perintah lain:

```powershell
cd frontend
npm run typecheck
npm run build
```

## Akun demo

Dibuat otomatis oleh `PlatformBootstrap`.

| Username | Password | Peran |
| --- | --- | --- |
| admin | admin123 | Administrator |
| designer | password123 | API Designer |
| reviewer | password123 | Reviewer |
| qa | password123 | QA |
| operator | password123 | Operator |
| auditor | password123 | Auditor |

## Mencoba cepat

API contoh SNAP BI `POST /v1.0/transfer-intrabank` sudah tersedia beserta flow,
kode Java, seed database, dan 2 test case.

1. Login sebagai `designer` / `password123`.
2. Tab **APIs & Archives** → buka **Transfer Antar Bank**.
3. Klik **Check** pada node Java → **Validasi** → jalankan **Simulasi**.
4. **Preflight** → **Publish** → **Deploy** ke `dev` (tab Deployments).
5. Buat consumer (tab **Data Sources** → Consumer), salin API key, lalu panggil:

```powershell
curl -X POST "http://localhost:8080/gw/v1.0/transfer-intrabank?environment=dev" `
     -H "X-API-Key: <apiKey>" -H "Content-Type: application/json" `
     -d '{"partnerReferenceNo":"REF-1","amount":{"value":"15000.00","currency":"IDR"},"beneficiaryAccountNo":"1234567890"}'
# → 200 {"responseCode":"2007300", ...}
```

## Fitur utama

- **Designer visual**: kanvas drag & drop, 16 jenis node, palette + function
  template, konfigurasi per node, undo/redo.
- **Editor kode khusus**: Java (autocomplete `ctx.*`, snippet, kelas JDK),
  SQL (saran tabel/kolom dari seed), JSON (validasi langsung), ekspresi
  (variabel flow + fungsi engine). Semua editor punya mode layar penuh.
- **Panel dapat diperbesar**: panel bawah resizable + layar penuh, panel
  konfigurasi node layar penuh, palette drawer di layar sempit.
- **Uji**: simulasi dengan mock & seed, test suite, assertion
  (response/DB/trace), trace per node dengan input/output/durasi.
- **Rilis**: preflight checklist, approval untuk production, publish immutable
  dengan `artifactHash`, deploy langsung atau canary, rollback.
- **Keamanan**: RBAC 6 peran, API key SHA-256 untuk gateway, secret AES-GCM,
  audit log, masking data sensitif.

## Batasan MVP

Ringkasnya: engine masih in-process, gateway masih simulasi `/gw/**`, dan
eksekusi Java bukan sandbox proses (hanya cocok untuk tim internal
tepercaya). Daftar lengkap di `CONTEXT.MD` bagian 15.
