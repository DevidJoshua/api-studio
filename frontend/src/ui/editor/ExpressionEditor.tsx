import { useMemo } from 'react';
import { CodeEditor, filterSuggestions, type Suggestion, type SuggestContext } from './CodeEditor';
import { expressionLanguage } from './tokenizer';

/**
 * Editor ekspresi flow (BRD FR-FLOW-07).
 *
 * <p>Menyediakan autocomplete untuk variabel request, fungsi engine, dan
 * variabel flow yang tersedia, sehingga pengguna tidak perlu menghafal nama
 * fungsi atau bentuk path.
 */

/** Variabel tingkat atas yang selalu tersedia. */
const ROOT_VARIABLES: Suggestion[] = [
  { insert: 'request.', label: 'request', detail: 'method, path, headers, query, body', kind: 'variable' },
  { insert: 'vars.', label: 'vars', detail: 'variabel hasil node sebelumnya', kind: 'variable' },
  { insert: 'response.', label: 'response', detail: 'hasil node RESPONSE', kind: 'variable' },
  { insert: 'flow.', label: 'flow', detail: 'name, version', kind: 'variable' },
];

/** Fungsi bawaan engine ekspresi. */
const FUNCTIONS: Suggestion[] = [
  { insert: 'upper(', label: 'upper(text)', detail: 'huruf besar', kind: 'function' },
  { insert: 'lower(', label: 'lower(text)', detail: 'huruf kecil', kind: 'function' },
  { insert: 'trim(', label: 'trim(text)', detail: 'buang spasi tepi', kind: 'function' },
  { insert: 'length(', label: 'length(x)', detail: 'panjang teks/list/map', kind: 'function' },
  { insert: 'number(', label: 'number(x)', detail: 'konversi ke angka', kind: 'function' },
  { insert: 'string(', label: 'string(x)', detail: 'konversi ke teks', kind: 'function' },
  { insert: 'timestamp()', label: 'timestamp()', detail: 'waktu ISO-8601', kind: 'function' },
  { insert: 'now()', label: 'now()', detail: 'alias timestamp()', kind: 'function' },
  { insert: 'sha256(', label: 'sha256(text)', detail: 'hash SHA-256', kind: 'function' },
  { insert: 'hmacSha256(', label: 'hmacSha256(text)', detail: 'signature HMAC', kind: 'function' },
  { insert: 'base64(', label: 'base64(text)', detail: 'kode Base64', kind: 'function' },
  { insert: 'json(', label: 'json(value)', detail: 'serialisasi JSON', kind: 'function' },
  { insert: 'get(', label: 'get(obj, path)', detail: 'ambil nilai bertitik', kind: 'function' },
  { insert: 'coalesce(', label: 'coalesce(a, b, ...)', detail: 'nilai pertama non-null', kind: 'function' },
  { insert: 'isEmpty(', label: 'isEmpty(x)', detail: 'cek kosong', kind: 'function' },
  { insert: 'uuid()', label: 'uuid()', detail: 'UUID v4 acak', kind: 'function' },
];

/** Field body request yang lazim, untuk mempercepat penulisan path. */
const BODY_FIELDS: Suggestion[] = [
  { insert: 'request.body.', label: 'request.body', detail: 'body request', kind: 'variable' },
  { insert: 'request.headers.', label: 'request.headers', detail: 'header request', kind: 'variable' },
  { insert: 'request.query.', label: 'request.query', detail: 'query string', kind: 'variable' },
  { insert: 'request.method', label: 'request.method', detail: 'method HTTP', kind: 'variable' },
  { insert: 'request.path', label: 'request.path', detail: 'path request', kind: 'variable' },
];

/** Operator perbandingan & logika sebagai saran cepat. */
const OPERATORS: Suggestion[] = [
  { insert: ' == ', label: '==', detail: 'sama dengan', kind: 'keyword' },
  { insert: ' != ', label: '!=', detail: 'tidak sama', kind: 'keyword' },
  { insert: ' > ', label: '>', detail: 'lebih besar', kind: 'keyword' },
  { insert: ' >= ', label: '>=', detail: 'lebih besar sama', kind: 'keyword' },
  { insert: ' < ', label: '<', detail: 'lebih kecil', kind: 'keyword' },
  { insert: ' <= ', label: '<=', detail: 'lebih kecil sama', kind: 'keyword' },
  { insert: ' && ', label: '&&', detail: 'dan', kind: 'keyword' },
  { insert: ' || ', label: '||', detail: 'atau', kind: 'keyword' },
  { insert: ' ? : ', label: '? :', detail: 'ternary', kind: 'keyword' },
];

export interface ExpressionEditorProps {
  value: string;
  onChange: (value: string) => void;
  height?: string;
  readOnly?: boolean;
  placeholder?: string;
  onBlur?: () => void;
  /** Variabel flow yang tersedia (dari node sebelumnya). */
  flowVariables?: string[];
  /** Field body request yang terdeteksi pada flow. */
  bodyFields?: string[];
}

/**
 * Editor ekspresi dengan saran variabel, field, dan fungsi.
 *
 * <p>Awalan {@code =} opsional: pengguna dapat menulis ekspresi langsung dan
 * editor menandai bahwa itu akan dievaluasi.
 */
export function ExpressionEditor({
  value,
  onChange,
  height = '10rem',
  readOnly,
  placeholder = '=number(request.body.amount) > 0',
  onBlur,
  flowVariables = [],
  bodyFields = [],
}: ExpressionEditorProps) {
  /** Variabel flow & field body menjadi saran spesifik aplikasi. */
  const dynamicSuggestions = useMemo<Suggestion[]>(() => {
    const items: Suggestion[] = [];
    flowVariables.forEach((name) => {
      items.push({ insert: `vars.${name}`, label: `vars.${name}`, detail: 'variabel flow', kind: 'variable' });
    });
    bodyFields.forEach((field) => {
      items.push({
        insert: `request.body.${field}`,
        label: `request.body.${field}`,
        detail: 'field request',
        kind: 'variable',
      });
    });
    return items;
  }, [flowVariables, bodyFields]);

  const suggest = (context: SuggestContext): Suggestion[] => {
    const before = context.value.slice(0, context.cursor);

    // di dalam string kutip: berhenti menyarankan
    const single = (before.match(/'/g) ?? []).length;
    const double = (before.match(/"/g) ?? []).length;
    if (single % 2 === 1 || double % 2 === 1) {
      return [];
    }

    // setelah "vars." -> variabel flow saja
    if (/\bvars\.[A-Za-z0-9_]*$/.test(before)) {
      const typed = context.prefix.replace(/^.*\./, '');
      return filterSuggestions(
        flowVariables.map((name) => ({
          insert: name,
          label: name,
          detail: 'variabel flow',
          kind: 'variable' as const,
        })),
        typed,
      );
    }

    // setelah "request.body." -> field body
    if (/\brequest\.body\.[A-Za-z0-9_]*$/.test(before)) {
      const typed = context.prefix.replace(/^.*\./, '');
      return filterSuggestions(
        bodyFields.map((field) => ({
          insert: field,
          label: field,
          detail: 'field request',
          kind: 'variable' as const,
        })),
        typed,
      );
    }

    const typed = context.prefix.replace(/^.*\./, '');
    return filterSuggestions(
      [...ROOT_VARIABLES, ...BODY_FIELDS, ...dynamicSuggestions, ...FUNCTIONS, ...OPERATORS],
      typed,
    );
  };

  return (
    <CodeEditor
      value={value}
      onChange={onChange}
      language={expressionLanguage}
      height={height}
      readOnly={readOnly}
      placeholder={placeholder}
      title="Ekspresi"
      onBlur={onBlur}
      indentSize={2}
      suggest={readOnly ? undefined : suggest}
    />
  );
}

/** Ekstrak variabel yang dihasilkan node flow {@code vars.x} dan {@code ctx.set}. */
export function extractFlowVariables(nodes: { type: string; config: Record<string, unknown> }[]): string[] {
  const names = new Set<string>();
  for (const node of nodes) {
    // node Set Variable/Transform: config.assignments
    const assignments = node.config.assignments;
    if (assignments && typeof assignments === 'object') {
      Object.keys(assignments as Record<string, unknown>).forEach((key) => names.add(key));
    }
    // node Java: pola ctx.set("nama"
    if (typeof node.config.source === 'string') {
      for (const match of node.config.source.matchAll(/ctx\.set\(\s*"([A-Za-z_][A-Za-z0-9_]*)"/g)) {
        names.add(match[1]);
      }
    }
  }
  return [...names].sort();
}

/** Ekstrak field body request yang dipakai flow ({@code =request.body.x}). */
export function extractBodyFields(value: unknown, found: Set<string> = new Set()): Set<string> {
  if (typeof value === 'string') {
    for (const match of value.matchAll(/request\.body\.([A-Za-z_][A-Za-z0-9_.]*)/g)) {
      found.add(match[1]);
    }
  } else if (Array.isArray(value)) {
    value.forEach((item) => extractBodyFields(item, found));
  } else if (value && typeof value === 'object') {
    Object.values(value as Record<string, unknown>).forEach((item) => extractBodyFields(item, found));
  }
  return found;
}
