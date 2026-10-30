import type { YamlSchemaNode } from './YamlEditor';
import { NODE_META } from '../../features/designer/nodeCatalog';

/**
 * Skema YAML untuk dokumen `ApiDefinition`.
 *
 * <p>Dipakai editor YAML agar penulisan konfigurasi flow mendapat saran kunci
 * dan nilai yang benar: kunci metadata, daftar node dengan jenis valid, port
 * yang dikenal, dan nilai kebijakan error.
 *
 * <p>Definisi ini sengaja selaras dengan `FlowDefinition` di backend sehingga
 * kesalahan ketik tertangkap saat menulis, bukan saat publish.
 */

const ERROR_POLICY: YamlSchemaNode = {
  keys: [
    { key: 'timeoutMs', detail: 'batas waktu node (ms)' },
    { key: 'retries', detail: 'jumlah percobaan ulang' },
    { key: 'backoffMs', detail: 'jeda antar percobaan (ms)' },
    { key: 'onError', detail: 'perilaku saat gagal' },
    { key: 'fallbackNodeId', detail: 'node tujuan saat FALLBACK' },
  ],
  children: {
    onError: {
      values: [
        { value: 'FAIL', detail: 'gagalkan flow' },
        { value: 'CONTINUE', detail: 'lanjut ke edge berikutnya' },
        { value: 'FALLBACK', detail: 'lompat ke fallbackNodeId' },
      ],
    },
  },
};

const NODE_ITEM: YamlSchemaNode = {
  keys: [
    { key: 'id', detail: 'id unik node' },
    { key: 'type', detail: 'jenis node' },
    { key: 'label', detail: 'judul di kanvas' },
    { key: 'disabled', detail: 'true untuk melewati node' },
    { key: 'position', detail: 'posisi kanvas {x, y}' },
    { key: 'errorPolicy', detail: 'timeout, retry, fallback' },
    { key: 'config', detail: 'konfigurasi sesuai jenis node' },
  ],
  children: {
    type: {
      values: Object.keys(NODE_META).map((type) => ({ value: type, detail: 'jenis node' })),
    },
    position: {
      keys: [
        { key: 'x', detail: 'koordinat horizontal' },
        { key: 'y', detail: 'koordinat vertikal' },
      ],
    },
    errorPolicy: ERROR_POLICY,
    // config bebas: sarankan kunci yang lazim lintas jenis node
    config: {
      keys: [
        { key: 'expression', detail: 'ekspresi (CONDITION/SWITCH)' },
        { key: 'assignments', detail: 'variabel (SET_VARIABLE/TRANSFORM)' },
        { key: 'url', detail: 'URL (HTTP_CALL)' },
        { key: 'method', detail: 'method HTTP (HTTP_CALL)' },
        { key: 'headers', detail: 'header (HTTP_CALL/RESPONSE)' },
        { key: 'body', detail: 'body (HTTP_CALL/RESPONSE)' },
        { key: 'sql', detail: 'query SELECT (DATABASE_QUERY)' },
        { key: 'params', detail: 'parameter query (DATABASE_QUERY)' },
        { key: 'templateKey', detail: 'kunci template (TEMPLATE_CALL)' },
        { key: 'version', detail: 'versi template (TEMPLATE_CALL)' },
        { key: 'args', detail: 'argumen template (TEMPLATE_CALL)' },
        { key: 'source', detail: 'kode Java (JAVA_CODE)' },
        { key: 'libraries', detail: 'koordinat Maven (JAVA_CODE)' },
        { key: 'status', detail: 'status HTTP (RESPONSE)' },
        { key: 'message', detail: 'pesan (LOG/FAIL)' },
        { key: 'level', detail: 'level log (LOG)' },
        { key: 'ms', detail: 'durasi (DELAY)' },
        { key: 'items', detail: 'koleksi (LOOP)' },
        { key: 'itemName', detail: 'nama item (LOOP)' },
        { key: 'cases', detail: 'daftar case (SWITCH)' },
        { key: 'timeoutMs', detail: 'timeout cabang (PARALLEL)' },
      ],
      children: {
        method: {
          values: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((value) => ({ value })),
        },
        level: {
          values: ['debug', 'info', 'warn', 'error'].map((value) => ({ value })),
        },
      },
    },
  },
  snippets: [
    {
      insert:
        'id: node_baru\n  type: LOG\n  label: Catat\n  config:\n    message: "halo"\n    level: info',
      label: 'node LOG',
      detail: 'potongan node',
      kind: 'snippet',
    },
    {
      insert:
        'id: node_resp\n  type: RESPONSE\n  label: Response\n  config:\n    status: 200\n    body:\n      responseCode: "2007300"',
      label: 'node RESPONSE',
      detail: 'potongan node',
      kind: 'snippet',
    },
  ],
};

const EDGE_ITEM: YamlSchemaNode = {
  keys: [
    { key: 'from', detail: 'id node sumber' },
    { key: 'to', detail: 'id node tujuan' },
    { key: 'port', detail: 'port keluar' },
    { key: 'label', detail: 'label tampilan' },
  ],
  children: {
    port: {
      values: [
        { value: 'out', detail: 'keluaran normal' },
        { value: 'true', detail: 'cabang benar' },
        { value: 'false', detail: 'cabang salah' },
        { value: 'error', detail: 'cabang error (TRY_CATCH)' },
      ],
    },
  },
};

/** Skema lengkap dokumen ApiDefinition. */
export const API_DEFINITION_SCHEMA: YamlSchemaNode = {
  keys: [
    { key: 'apiVersion', detail: 'versi skema dokumen', kind: 'keyword' },
    { key: 'kind', detail: 'jenis dokumen', kind: 'keyword' },
    { key: 'metadata', detail: 'nama, method, path, versi', kind: 'keyword' },
    { key: 'spec', detail: 'nodes dan edges', kind: 'keyword' },
  ],
  children: {
    apiVersion: {
      values: [
        { value: 'apistudio/v1', detail: 'versi saat ini' },
      ],
    },
    kind: {
      values: [
        { value: 'ApiDefinition', detail: 'definisi API' },
      ],
    },
    metadata: {
      keys: [
        { key: 'name', detail: 'nama API' },
        { key: 'method', detail: 'method HTTP' },
        { key: 'path', detail: 'path publik' },
        { key: 'version', detail: 'label versi' },
        { key: 'environment', detail: 'environment target' },
      ],
      children: {
        method: {
          values: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((value) => ({ value })),
        },
      },
    },
    spec: {
      keys: [
        { key: 'flow', detail: 'nama & versi flow' },
        { key: 'policy', detail: 'kebijakan API' },
        { key: 'nodes', detail: 'daftar node', kind: 'keyword' },
        { key: 'edges', detail: 'daftar koneksi', kind: 'keyword' },
      ],
      children: {
        flow: {
          keys: [
            { key: 'name', detail: 'nama flow' },
            { key: 'version', detail: 'versi flow' },
          ],
        },
        policy: {
          keys: [
            { key: 'auth', detail: 'jenis autentikasi' },
            { key: 'rateLimitPerMinute', detail: 'batas request per menit' },
            { key: 'ipWhitelist', detail: 'daftar IP diizinkan' },
          ],
          children: {
            auth: {
              values: [
                { value: 'none', detail: 'tanpa autentikasi' },
                { value: 'apiKey', detail: 'API key consumer' },
                { value: 'oauth2', detail: 'OAuth2' },
                { value: 'jwt', detail: 'JWT' },
                { value: 'hmac', detail: 'HMAC signature' },
              ],
            },
          },
        },
        nodes: { item: NODE_ITEM },
        edges: { item: EDGE_ITEM },
      },
    },
  },
};
