import { useMemo } from 'react';
import { CodeEditor, filterSuggestions, type Suggestion, type SuggestContext } from './CodeEditor';
import { javaLanguage } from './tokenizer';

/**
 * Editor kode Java untuk node Java Code, bergaya IntelliJ IDEA.
 *
 * <p>Fitur yang menyerupai IDE:
 * <ul>
 *   <li>Pewarnaan sintaks: kata kunci, tipe, string, angka, komentar, anotasi.</li>
 *   <li>Autocomplete konteks flow: {@code ctx.} menampilkan method NodeContext.</li>
 *   <li>Autocomplete kelas JDK & koleksi yang lazim dipakai.</li>
 *   <li>Snippet siap pakai (baca input, tulis variabel, panggil template, dsb.).</li>
 *   <li>Auto-indent, auto-pair kurung/kutip, Tab/Shift+Tab indentasi blok.</li>
 * </ul>
 *
 * <p>Kode pengguna ditulis sebagai isi method {@code run()}; panel ini tidak
 * menampilkan pembungkus class agar fokus pada logika. Pembungkusan dilakukan
 * Control Plane saat kompilasi.
 */

/** Method NodeContext yang tersedia di dalam kode pengguna. */
const CTX_METHODS: Suggestion[] = [
  { insert: 'input("")', label: 'input(field)', detail: 'Ambil field body request', kind: 'function' },
  { insert: 'inputMap()', label: 'inputMap()', detail: 'Map body request', kind: 'function' },
  { insert: 'get("")', label: 'get(name)', detail: 'Baca variabel flow', kind: 'function' },
  { insert: 'set("", ', label: 'set(name, value)', detail: 'Tulis variabel flow', kind: 'function' },
  { insert: 'eval("")', label: 'eval(expression)', detail: 'Evaluasi ekspresi flow', kind: 'function' },
  { insert: 'log("")', label: 'log(message)', detail: 'Tulis log ke trace', kind: 'function' },
  { insert: 'callTemplate("", java.util.Map.of())', label: 'callTemplate(key, args)', detail: 'Panggil function template', kind: 'function' },
  { insert: 'headers()', label: 'headers()', detail: 'Map header request', kind: 'function' },
  { insert: 'query()', label: 'query()', detail: 'Map query string', kind: 'function' },
  { insert: 'nodeId()', label: 'nodeId()', detail: 'Id node saat ini', kind: 'function' },
];

/** Snippet utuh yang sering dipakai pada node Java. */
const SNIPPETS: Suggestion[] = [
  {
    insert: 'String id = ctx.input("id") == null ? "" : ctx.input("id").toString();\nctx.set("id", id);\nreturn id;',
    label: 'baca input lalu kembalikan',
    detail: 'Snippet',
    kind: 'snippet',
  },
  {
    insert: 'Object nilai = ctx.get("");\nreturn nilai;',
    label: 'baca variabel flow',
    detail: 'Snippet',
    kind: 'snippet',
  },
  {
    insert: 'java.util.Map<String, Object> hasil = new java.util.LinkedHashMap<>();\nhasil.put("", );\nreturn hasil;',
    label: 'bangun map hasil',
    detail: 'Snippet',
    kind: 'snippet',
  },
  {
    insert: 'if (ctx.input("") == null) {\n    throw new IllegalArgumentException("parameter wajib");\n}',
    label: 'validasi input wajib',
    detail: 'Snippet',
    kind: 'snippet',
  },
  {
    insert: 'Object ts = ctx.callTemplate("snapbi.timestamp@1", java.util.Map.of());',
    label: 'panggil template timestamp',
    detail: 'Snippet',
    kind: 'snippet',
  },
];

/** Tipe & kelas JDK yang lazim pada logika API. */
const JDK_TYPES: Suggestion[] = [
  { insert: 'String', label: 'String', detail: 'java.lang', kind: 'type' },
  { insert: 'Object', label: 'Object', detail: 'java.lang', kind: 'type' },
  { insert: 'Integer', label: 'Integer', detail: 'java.lang', kind: 'type' },
  { insert: 'Long', label: 'Long', detail: 'java.lang', kind: 'type' },
  { insert: 'Double', label: 'Double', detail: 'java.lang', kind: 'type' },
  { insert: 'Boolean', label: 'Boolean', detail: 'java.lang', kind: 'type' },
  { insert: 'Number', label: 'Number', detail: 'java.lang', kind: 'type' },
  { insert: 'java.math.BigDecimal', label: 'BigDecimal', detail: 'presisi uang', kind: 'type' },
  { insert: 'java.util.Map<String, Object>', label: 'Map<String, Object>', detail: 'java.util', kind: 'type' },
  { insert: 'java.util.LinkedHashMap<String, Object>', label: 'LinkedHashMap', detail: 'urutan tetap', kind: 'type' },
  { insert: 'java.util.List<Object>', label: 'List<Object>', detail: 'java.util', kind: 'type' },
  { insert: 'java.util.ArrayList<>()', label: 'new ArrayList<>()', detail: 'java.util', kind: 'type' },
  { insert: 'java.util.Optional', label: 'Optional', detail: 'java.util', kind: 'type' },
  { insert: 'java.time.Instant', label: 'Instant', detail: 'waktu', kind: 'type' },
  { insert: 'java.util.UUID.randomUUID().toString()', label: 'UUID acak', detail: 'id unik', kind: 'snippet' },
];

/** Method umum pada String/Map/List agar pengguna cepat. */
const COMMON_METHODS: Suggestion[] = [
  { insert: 'toString()', label: 'toString()', detail: 'Object', kind: 'function' },
  { insert: 'equals(', label: 'equals(other)', detail: 'Object', kind: 'function' },
  { insert: 'toUpperCase()', label: 'toUpperCase()', detail: 'String', kind: 'function' },
  { insert: 'toLowerCase()', label: 'toLowerCase()', detail: 'String', kind: 'function' },
  { insert: 'trim()', label: 'trim()', detail: 'String', kind: 'function' },
  { insert: 'length()', label: 'length()', detail: 'String', kind: 'function' },
  { insert: 'isEmpty()', label: 'isEmpty()', detail: 'String/Collection', kind: 'function' },
  { insert: 'contains(', label: 'contains(value)', detail: 'String/Collection', kind: 'function' },
  { insert: 'replace(', label: 'replace(old, new)', detail: 'String', kind: 'function' },
  { insert: 'split(', label: 'split(regex)', detail: 'String', kind: 'function' },
  { insert: 'substring(', label: 'substring(from, to)', detail: 'String', kind: 'function' },
  { insert: 'put(', label: 'put(key, value)', detail: 'Map', kind: 'function' },
  { insert: 'get(', label: 'get(key)', detail: 'Map', kind: 'function' },
  { insert: 'getOrDefault(', label: 'getOrDefault(key, default)', detail: 'Map', kind: 'function' },
  { insert: 'containsKey(', label: 'containsKey(key)', detail: 'Map', kind: 'function' },
  { insert: 'keySet()', label: 'keySet()', detail: 'Map', kind: 'function' },
  { insert: 'entrySet()', label: 'entrySet()', detail: 'Map', kind: 'function' },
  { insert: 'add(', label: 'add(value)', detail: 'List', kind: 'function' },
  { insert: 'size()', label: 'size()', detail: 'Collection', kind: 'function' },
  { insert: 'stream()', label: 'stream()', detail: 'Collection', kind: 'function' },
];

/** Method per kategori tipe, agar saran setelah {@code variabel.} relevan. */
const METHODS_BY_TYPE: Record<string, Suggestion[]> = {
  string: [
    { insert: 'toString()', label: 'toString()', detail: 'String', kind: 'function' },
    { insert: 'trim()', label: 'trim()', detail: 'String', kind: 'function' },
    { insert: 'toUpperCase()', label: 'toUpperCase()', detail: 'String', kind: 'function' },
    { insert: 'toLowerCase()', label: 'toLowerCase()', detail: 'String', kind: 'function' },
    { insert: 'length()', label: 'length()', detail: 'String', kind: 'function' },
    { insert: 'isEmpty()', label: 'isEmpty()', detail: 'String', kind: 'function' },
    { insert: 'contains(', label: 'contains(x)', detail: 'String', kind: 'function' },
    { insert: 'startsWith(', label: 'startsWith(x)', detail: 'String', kind: 'function' },
    { insert: 'endsWith(', label: 'endsWith(x)', detail: 'String', kind: 'function' },
    { insert: 'replace(', label: 'replace(a, b)', detail: 'String', kind: 'function' },
    { insert: 'replaceAll(', label: 'replaceAll(regex, r)', detail: 'String', kind: 'function' },
    { insert: 'split(', label: 'split(regex)', detail: 'String', kind: 'function' },
    { insert: 'substring(', label: 'substring(from, to)', detail: 'String', kind: 'function' },
    { insert: 'indexOf(', label: 'indexOf(x)', detail: 'String', kind: 'function' },
    { insert: 'charAt(', label: 'charAt(i)', detail: 'String', kind: 'function' },
    { insert: 'matches(', label: 'matches(regex)', detail: 'String', kind: 'function' },
    { insert: 'concat(', label: 'concat(x)', detail: 'String', kind: 'function' },
  ],
  number: [
    { insert: 'intValue()', label: 'intValue()', detail: 'Number', kind: 'function' },
    { insert: 'longValue()', label: 'longValue()', detail: 'Number', kind: 'function' },
    { insert: 'doubleValue()', label: 'doubleValue()', detail: 'Number', kind: 'function' },
    { insert: 'toString()', label: 'toString()', detail: 'Number', kind: 'function' },
    { insert: 'compareTo(', label: 'compareTo(x)', detail: 'Number', kind: 'function' },
  ],
  map: [
    { insert: 'get(', label: 'get(key)', detail: 'Map', kind: 'function' },
    { insert: 'getOrDefault(', label: 'getOrDefault(k, d)', detail: 'Map', kind: 'function' },
    { insert: 'put(', label: 'put(key, value)', detail: 'Map', kind: 'function' },
    { insert: 'putIfAbsent(', label: 'putIfAbsent(k, v)', detail: 'Map', kind: 'function' },
    { insert: 'containsKey(', label: 'containsKey(key)', detail: 'Map', kind: 'function' },
    { insert: 'keySet()', label: 'keySet()', detail: 'Map', kind: 'function' },
    { insert: 'values()', label: 'values()', detail: 'Map', kind: 'function' },
    { insert: 'entrySet()', label: 'entrySet()', detail: 'Map', kind: 'function' },
    { insert: 'isEmpty()', label: 'isEmpty()', detail: 'Map', kind: 'function' },
    { insert: 'size()', label: 'size()', detail: 'Map', kind: 'function' },
    { insert: 'forEach(', label: 'forEach((k, v) -> {})', detail: 'Map', kind: 'function' },
  ],
  list: [
    { insert: 'add(', label: 'add(value)', detail: 'List', kind: 'function' },
    { insert: 'get(', label: 'get(index)', detail: 'List', kind: 'function' },
    { insert: 'size()', label: 'size()', detail: 'List', kind: 'function' },
    { insert: 'isEmpty()', label: 'isEmpty()', detail: 'List', kind: 'function' },
    { insert: 'contains(', label: 'contains(x)', detail: 'List', kind: 'function' },
    { insert: 'forEach(', label: 'forEach(x -> {})', detail: 'List', kind: 'function' },
    { insert: 'stream()', label: 'stream()', detail: 'List', kind: 'function' },
    { insert: 'subList(', label: 'subList(from, to)', detail: 'List', kind: 'function' },
  ],
  boolean: [
    { insert: 'booleanValue()', label: 'booleanValue()', detail: 'Boolean', kind: 'function' },
    { insert: 'toString()', label: 'toString()', detail: 'Boolean', kind: 'function' },
  ],
};

/** Klasifikasi tipe deklarasi menjadi kategori untuk pemilihan method. */
function categorizeType(type: string): string | null {
  const lower = type.toLowerCase();
  if (lower.includes('string') || lower.includes('charsequence')) {
    return 'string';
  }
  if (
    ['int', 'long', 'double', 'float', 'short', 'byte'].includes(lower) ||
    lower.includes('integer') ||
    lower.includes('long') ||
    lower.includes('double') ||
    lower.includes('bignum') ||
    lower.includes('number')
  ) {
    return 'number';
  }
  if (lower.includes('map')) {
    return 'map';
  }
  if (lower.includes('list') || lower.includes('set') || lower.includes('collection')) {
    return 'list';
  }
  if (lower.includes('boolean')) {
    return 'boolean';
  }
  if (lower.includes('bigdecimal')) {
    return 'number';
  }
  return null;
}

/** Satu variabel yang dideklarasikan di kode pengguna. */
interface DeclaredVariable {
  name: string;
  category: string | null;
}

/**
 * Lacak variabel lokal yang dideklarasikan pengguna.
 *
 * <p>Menangkap pola {@code Tipe nama = ...} dan {@code var nama = ...}. Ini yang
 * membuat saran setelah {@code nama.} relevan dengan tipe variabelnya.
 */
export function extractDeclaredVariables(source: string): DeclaredVariable[] {
  const variables: DeclaredVariable[] = [];
  const pattern =
    /\b([A-Za-z_][A-Za-z0-9_.<>,\[\]\s]*?)\s+([A-Za-z_][A-Za-z0-9_]*)\s*=/g;
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(source)) !== null) {
    const type = match[1].trim();
    const name = match[2];
    // hindari menangkap potongan ekspresi seperti "= a + b"
    if (type.length > 40 || /[=+\-*/]/.test(type)) {
      continue;
    }
    variables.push({ name, category: categorizeType(type) });
  }
  return variables;
}

export interface JavaEditorProps {
  value: string;
  onChange: (value: string) => void;
  height?: string;
  readOnly?: boolean;
  title?: string;
  actions?: React.ReactNode;
  onBlur?: () => void;
  onRunShortcut?: () => void;
  /** Nama variabel yang tersedia di flow (untuk saran). */
  flowVariables?: string[];
}

/**
 * Editor Java dengan autocomplete sadar konteks.
 *
 * <p>Penyedia saran memeriksa teks sebelum kursor: setelah {@code ctx.} hanya
 * method konteks yang disarankan; selain itu kelas, snippet, dan method umum.
 */
export function JavaEditor({
  value,
  onChange,
  height = '22rem',
  readOnly,
  title = 'Java',
  actions,
  onBlur,
  onRunShortcut,
  flowVariables = [],
}: JavaEditorProps) {
  /** Variabel flow sebagai saran variabel. */
  const variableSuggestions = useMemo<Suggestion[]>(
    () =>
      flowVariables.map((name) => ({
        insert: `ctx.get("${name}")`,
        label: name,
        detail: 'variabel flow',
        kind: 'variable' as const,
      })),
    [flowVariables],
  );

  /** Bangun daftar saran sesuai konteks kursor. */
  const suggest = (context: SuggestContext): Suggestion[] => {
    const before = context.value.slice(0, context.cursor);

    // setelah "ctx." atau "ctx.<huruf>" -> method konteks saja
    if (/\bctx\.[A-Za-z0-9_]*$/.test(before)) {
      const typed = context.prefix.replace(/^.*\./, '');
      return filterSuggestions(CTX_METHODS, typed);
    }

    // setelah "variabel." -> method sesuai tipe variabel yang dideklarasikan
    const memberMatch = /\b([A-Za-z_][A-Za-z0-9_]*)\.\s*([A-Za-z0-9_]*)$/.exec(before);
    if (memberMatch) {
      const [, target, typed] = memberMatch;
      // ctx sudah ditangani di atas; java.* adalah paket
      if (target !== 'java' && target !== 'ctx' && target !== 'this') {
        const declared = extractDeclaredVariables(context.value);
        const variable = declared.find((v) => v.name === target);
        if (variable?.category && METHODS_BY_TYPE[variable.category]) {
          return filterSuggestions(METHODS_BY_TYPE[variable.category], typed);
        }
        // objek tak dikenal: method umum Object
        return filterSuggestions(
          [
            { insert: 'toString()', label: 'toString()', detail: 'Object', kind: 'function' as const },
            { insert: 'equals(', label: 'equals(other)', detail: 'Object', kind: 'function' as const },
            { insert: 'hashCode()', label: 'hashCode()', detail: 'Object', kind: 'function' as const },
          ],
          typed,
        );
      }
    }

    // setelah tanda kutip baru: tidak menyarankan apa pun
    if (/"[^"]*$/.test(before) && !/"[^"]*"$/.test(before)) {
      return [];
    }

    // di awal "//" komentar: tidak menyarankan
    const currentLine = before.split('\n').pop() ?? '';
    if (currentLine.trimStart().startsWith('//')) {
      return [];
    }

    // variabel lokal yang sudah dideklarasikan (relevan untuk dipakai ulang)
    const localVariables: Suggestion[] = extractDeclaredVariables(context.value).map((variable) => ({
      insert: variable.name,
      label: variable.name,
      detail: `variabel lokal · ${variable.category ?? 'objek'}`,
      kind: 'variable' as const,
    }));

    const typed = context.prefix.replace(/^.*\./, '');
    return filterSuggestions(
      [
        ...CTX_METHODS,
        ...variableSuggestions,
        ...localVariables,
        ...SNIPPETS,
        ...JDK_TYPES,
        ...COMMON_METHODS,
      ],
      typed,
    );
  };

  return (
    <CodeEditor
      value={value}
      onChange={onChange}
      language={javaLanguage}
      height={height}
      readOnly={readOnly}
      placeholder="// Tulis isi method run(); variabel flow tersedia lewat ctx"
      title={title}
      actions={actions}
      onBlur={onBlur}
      onRunShortcut={onRunShortcut}
      suggest={readOnly ? undefined : suggest}
    />
  );
}
