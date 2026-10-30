import { useMemo } from 'react';
import { CodeEditor, filterSuggestions, type Suggestion, type SuggestContext } from './CodeEditor';
import { sqlLanguage } from './tokenizer';

/**
 * Editor SQL untuk node Database Query.
 *
 * <p>Sandbox hanya mengizinkan SELECT, sehingga editor menekankan kata kunci
 * baca dan menyediakan saran tabel/kolom dari seed versi agar penulisan query
 * cepat dan sesuai data yang benar-benar tersedia.
 */

const SQL_KEYWORD_SUGGESTIONS: Suggestion[] = [
  { insert: 'select ', label: 'select', detail: 'ambil kolom', kind: 'keyword' },
  { insert: 'from ', label: 'from', detail: 'sumber tabel', kind: 'keyword' },
  { insert: 'where ', label: 'where', detail: 'filter', kind: 'keyword' },
  { insert: 'and ', label: 'and', detail: 'gabung kondisi', kind: 'keyword' },
  { insert: 'or ', label: 'or', detail: 'alternatif kondisi', kind: 'keyword' },
  { insert: 'order by ', label: 'order by', detail: 'urutkan', kind: 'keyword' },
  { insert: 'group by ', label: 'group by', detail: 'kelompokkan', kind: 'keyword' },
  { insert: 'join ', label: 'join', detail: 'gabung tabel', kind: 'keyword' },
  { insert: 'left join ', label: 'left join', detail: 'gabung kiri', kind: 'keyword' },
  { insert: 'inner join ', label: 'inner join', detail: 'gabung dalam', kind: 'keyword' },
  { insert: 'on ', label: 'on', detail: 'kondisi join', kind: 'keyword' },
  { insert: 'limit ', label: 'limit', detail: 'batasi baris', kind: 'keyword' },
  { insert: 'offset ', label: 'offset', detail: 'lewati baris', kind: 'keyword' },
  { insert: 'distinct ', label: 'distinct', detail: 'unik', kind: 'keyword' },
  { insert: 'as ', label: 'as', detail: 'alias', kind: 'keyword' },
  { insert: 'is null', label: 'is null', detail: 'cek kosong', kind: 'keyword' },
  { insert: 'is not null', label: 'is not null', detail: 'cek terisi', kind: 'keyword' },
  { insert: 'count(*)', label: 'count(*)', detail: 'hitung baris', kind: 'function' },
  { insert: 'coalesce(', label: 'coalesce(a, b)', detail: 'nilai default', kind: 'function' },
  { insert: 'lower(', label: 'lower(x)', detail: 'huruf kecil', kind: 'function' },
  { insert: 'upper(', label: 'upper(x)', detail: 'huruf besar', kind: 'function' },
  { insert: 'sum(', label: 'sum(x)', detail: 'jumlah', kind: 'function' },
  { insert: 'max(', label: 'max(x)', detail: 'terbesar', kind: 'function' },
  { insert: 'min(', label: 'min(x)', detail: 'terkecil', kind: 'function' },
  { insert: '?', label: '?', detail: 'placeholder parameter', kind: 'keyword' },
];

export interface SqlEditorProps {
  value: string;
  onChange: (value: string) => void;
  height?: string;
  readOnly?: boolean;
  actions?: React.ReactNode;
  onBlur?: () => void;
  onRunShortcut?: () => void;
  /** Skema sandbox dari seed: {@code { tabel: [kolom...] }}. */
  schema?: Record<string, string[]>;
}

/**
 * Editor SQL dengan saran tabel dan kolom dari seed versi.
 *
 * <p>Tabel terdeteksi setelah {@code from}/{@code join}; kolom disarankan
 * setelah {@code select}/{@code where}/{@code on} atau setelah alias tabel.
 */
export function SqlEditor({
  value,
  onChange,
  height = '12rem',
  readOnly,
  actions,
  onBlur,
  onRunShortcut,
  schema = {},
}: SqlEditorProps) {
  const tables = useMemo(() => Object.keys(schema), [schema]);

  const tableSuggestions = useMemo<Suggestion[]>(
    () => tables.map((name) => ({ insert: name, label: name, detail: 'tabel sandbox', kind: 'table' as const })),
    [tables],
  );

  const suggest = (context: SuggestContext): Suggestion[] => {
    const before = context.value.slice(0, context.cursor);
    const typed = context.prefix.replace(/^.*\./, '');

    // setelah "alias." -> kolom tabel itu
    const aliasMatch = /\b([A-Za-z_][A-Za-z0-9_]*)\.\s*[A-Za-z0-9_]*$/.exec(before);
    if (aliasMatch) {
      const alias = aliasMatch[1].toLowerCase();
      const table = tables.find((name) => name.toLowerCase() === alias)
        ?? tables.find((name) => name.toLowerCase().startsWith(alias));
      if (table) {
        return filterSuggestions(
          (schema[table] ?? []).map((column) => ({
            insert: column,
            label: column,
            detail: `kolom ${table}`,
            kind: 'column' as const,
          })),
          typed,
        );
      }
    }

    // setelah from/join -> hanya tabel
    if (/\b(from|join)\s+[A-Za-z0-9_]*$/.test(before)) {
      return filterSuggestions(tableSuggestions, typed);
    }

    // setelah select/where/on/and/or/order by/group by -> kolom semua tabel + fungsi
    if (/\b(select|where|on|and|or|by|having)\s+[A-Za-z0-9_]*$/.test(before)) {
      const columns: Suggestion[] = [];
      Object.entries(schema).forEach(([table, cols]) => {
        cols.forEach((column) => {
          columns.push({
            insert: column,
            label: column,
            detail: table,
            kind: 'column',
          });
        });
      });
      return filterSuggestions([...columns, ...SQL_KEYWORD_SUGGESTIONS], typed);
    }

    return filterSuggestions([...SQL_KEYWORD_SUGGESTIONS, ...tableSuggestions], typed);
  };

  return (
    <CodeEditor
      value={value}
      onChange={onChange}
      language={sqlLanguage}
      height={height}
      readOnly={readOnly}
      placeholder="select * from tabel where kolom = ?"
      title="SQL"
      actions={actions}
      onBlur={onBlur}
      onRunShortcut={onRunShortcut}
      suggest={readOnly ? undefined : suggest}
    />
  );
}

/** Putuskan {@code [{table, rows}]} menjadi peta tabel ke daftar kolom. */
export function schemaFromSeeds(seeds: unknown): Record<string, string[]> {
  if (!Array.isArray(seeds)) {
    return {};
  }
  const schema: Record<string, string[]> = {};
  for (const seed of seeds) {
    if (!seed || typeof seed !== 'object') {
      continue;
    }
    const record = seed as { table?: unknown; rows?: unknown };
    if (typeof record.table !== 'string' || !Array.isArray(record.rows) || record.rows.length === 0) {
      continue;
    }
    const first = record.rows[0];
    if (first && typeof first === 'object') {
      schema[record.table] = Object.keys(first as Record<string, unknown>);
    }
  }
  return schema;
}
