import { useMemo } from 'react';
import { CodeEditor, filterSuggestions, type Suggestion, type SuggestContext } from './CodeEditor';
import { yamlLanguage } from './tokenizer';

/**
 * Editor YAML dengan autocomplete sesuai skema.
 *
 * <p>Berbeda dari editor kode biasa, saran di sini ditentukan oleh **posisi
 * dalam hierarki YAML**: editor menghitung path kunci yang sedang aktif
 * (mis. {@code spec.nodes[].errorPolicy}) lalu menyarankan kunci, nilai, atau
 * potongan blok yang relevan untuk path tersebut.
 *
 * <p>Skema diberikan pemanggil lewat {@link YamlEditorProps.schema} sehingga
 * editor yang sama dapat dipakai untuk konfigurasi berbeda.
 */

/** Satu node skema: kunci bersarang dan nilai yang diizinkan. */
export interface YamlSchemaNode {
  /** Kunci yang valid pada level ini (nama & keterangan). */
  keys?: { key: string; detail?: string; kind?: Suggestion['kind'] }[];
  /** Nilai yang valid bila node ini adalah nilai akhir. */
  values?: { value: string; detail?: string }[];
  /** Skema anak per kunci. */
  children?: Record<string, YamlSchemaNode>;
  /** Skema untuk setiap item bila node ini adalah list. */
  item?: YamlSchemaNode;
  /** Sisipan blok siap pakai (mis. potongan node lengkap). */
  snippets?: Suggestion[];
  /** Sarankan nilai tanpa perlu titik dua. */
  suggestValues?: boolean;
}

export interface YamlEditorProps {
  value: string;
  onChange: (value: string) => void;
  height?: string;
  readOnly?: boolean;
  title?: string;
  actions?: React.ReactNode;
  onBlur?: () => void;
  /** Skema autocomplete untuk dokumen ini. */
  schema: YamlSchemaNode;
  /** Placeholder saat kosong. */
  placeholder?: string;
}

/** Satu baris YAML yang sudah diurai ringkas untuk analisis konteks. */
interface ParsedLine {
  indent: number;
  key: string | null;
  hasValue: boolean;
  isListItem: boolean;
  content: string;
}

/** Urai baris menjadi bentuk ringkas untuk analisis konteks. */
function analyzeLines(text: string): ParsedLine[] {
  return text.split('\n').map((raw) => {
    const indentMatch = /^[ \t]*/.exec(raw);
    const indent = indentMatch ? indentMatch[0].replace(/\t/g, '  ').length : 0;
    let content = raw.trim();
    let isListItem = false;
    if (content.startsWith('- ')) {
      isListItem = true;
      content = content.slice(2).trim();
    } else if (content === '-') {
      isListItem = true;
      content = '';
    }
    const colon = content.indexOf(':');
    let key: string | null = null;
    let hasValue = false;
    if (colon > 0) {
      const after = content.slice(colon + 1);
      // "::" atau "http://" bukan pemisah kunci di sini karena kita hanya
      // memakai heuristik sederhana: kunci bila diikuti spasi/akhir baris
      if (after === '' || after.startsWith(' ')) {
        key = content.slice(0, colon).trim();
        hasValue = after.trim() !== '';
      }
    }
    return { indent, key, hasValue, isListItem, content };
  });
}

/**
 * Tentukan skema node yang aktif berdasarkan baris dan indentasi kursor.
 *
 * <p>Algoritma: telusuri baris sebelum kursor sambil memelihara tumpukan
 * (indent, key). Setiap kali menemukan kunci yang lebih dangkal, tumpukan
 * dipangkas. Kunci pada tumpukan dipetakan lewat {@code schema.children}.
 */
function resolveSchema(
  lines: ParsedLine[],
  cursorLine: number,
  schema: YamlSchemaNode,
): { node: YamlSchemaNode; inList: boolean } {
  const stack: { indent: number; node: YamlSchemaNode }[] = [{ indent: -1, node: schema }];
  let current = schema;
  let listMode = false;

  for (let i = 0; i <= cursorLine && i < lines.length; i++) {
    const line = lines[i];
    const top = stack[stack.length - 1];

    // turun satu level bila indentasi bertambah
    if (line.indent > top.indent) {
      const next = current && current.item && listMode ? current.item : current;
      if (next && next !== current) {
        stack.push({ indent: line.indent, node: next });
        current = next;
        listMode = false;
      }
    } else {
      // naik level sesuai indentasi
      while (stack.length > 1 && line.indent < stack[stack.length - 1].indent) {
        stack.pop();
      }
      current = stack[stack.length - 1].node;
    }

    if (line.isListItem) {
      // list item: bila skema punya item, masuk ke skema item
      const active = stack[stack.length - 1].node;
      if (active.item) {
        current = active.item;
        listMode = false;
      }
      if (line.key && current.children?.[line.key]) {
        current = current.children[line.key];
      } else if (line.key) {
        current = { };
      }
      continue;
    }

    if (line.key) {
      const children = current.children;
      if (children && children[line.key]) {
        current = children[line.key];
        listMode = false;
      } else if (children) {
        // kunci tidak dikenal pada level ini: konteks jadi bebas
        current = {};
      }
    }
  }

  // bila kunci terakhir adalah list (belum ada item), siapkan skema item
  const active = stack[stack.length - 1].node;
  if (active.item && !current.children) {
    return { node: active.item, inList: true };
  }
  return { node: current, inList: false };
}

export function YamlEditor({
  value,
  onChange,
  height = '20rem',
  readOnly,
  title = 'YAML',
  actions,
  onBlur,
  schema,
  placeholder,
}: YamlEditorProps) {
  const lines = useMemo(() => analyzeLines(value), [value]);

  /** Bangun saran sesuai konteks kursor. */
  const suggest = (context: SuggestContext): Suggestion[] => {
    const before = context.value.slice(0, context.cursor);
    const currentLine = before.split('\n').pop() ?? '';
    const currentLineFull = context.value.split('\n')[context.line] ?? '';

    // di dalam komentar: tidak menyarankan
    if (currentLine.trimStart().startsWith('#')) {
      return [];
    }

    const { node } = resolveSchema(lines, context.line, schema);

    // konteks nilai: baris sudah memuat "kunci: <kursor>"
    const colonIndex = currentLineFull.indexOf(':');
    const isValuePosition =
      colonIndex >= 0 &&
      currentLineFull.slice(0, context.cursor - (before.length - currentLine.length)).length >
        colonIndex;

    if (isValuePosition && node.values) {
      const typed = currentLineFull.slice(colonIndex + 1).trim();
      return filterSuggestions(
        node.values.map((entry) => ({
          insert: entry.value,
          label: entry.value,
          detail: entry.detail,
          kind: 'keyword' as const,
        })),
        typed,
      );
    }

    // konteks kunci: sarankan kunci skema + snippet blok
    const typedKey = currentLine.replace(/^[ \t]*(- )?/, '').split(':')[0];
    const keySuggestions: Suggestion[] = (node.keys ?? []).map((entry) => ({
      insert: `${entry.key}:`,
      label: `${entry.key}:`,
      detail: entry.detail,
      kind: entry.kind ?? 'keyword',
    }));

    return filterSuggestions([...keySuggestions, ...(node.snippets ?? [])], typedKey);
  };

  return (
    <CodeEditor
      value={value}
      onChange={onChange}
      language={yamlLanguage}
      height={height}
      readOnly={readOnly}
      placeholder={placeholder ?? '# tulis konfigurasi YAML di sini'}
      title={title}
      actions={actions}
      onBlur={onBlur}
      indentSize={2}
      suggest={readOnly ? undefined : suggest}
    />
  );
}
