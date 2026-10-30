import { useEffect, useMemo, useState } from 'react';
import { CodeEditor, filterSuggestions, type Suggestion, type SuggestContext } from './CodeEditor';
import { jsonLanguage } from './tokenizer';
import { Button } from '../Button';
import { Badge } from '../Feedback';

/**
 * Editor JSON dengan validasi langsung dan tombol rapikan.
 *
 * <p>Dipakai untuk field terstruktur: headers, body, assignments, mock, seed,
 * assertion. Nilai hanya diteruskan ke pemanggil ketika JSON valid, sehingga
 * kesalahan ketik tidak pernah sampai ke backend.
 */

/** Saran kunci yang sering dipakai, dapat disesuaikan per pemakaian. */
const COMMON_KEYS: Suggestion[] = [
  { insert: '"Content-Type":', label: '"Content-Type"', detail: 'header', kind: 'keyword' },
  { insert: '"application/json"', label: '"application/json"', detail: 'nilai umum', kind: 'keyword' },
  { insert: '"Authorization":', label: '"Authorization"', detail: 'header', kind: 'keyword' },
  { insert: '"X-API-Key":', label: '"X-API-Key"', detail: 'header', kind: 'keyword' },
  { insert: '"X-TIMESTAMP":', label: '"X-TIMESTAMP"', detail: 'header SNAP BI', kind: 'keyword' },
  { insert: '"partnerReferenceNo":', label: '"partnerReferenceNo"', detail: 'field SNAP BI', kind: 'keyword' },
  { insert: '"amount":', label: '"amount"', detail: 'field SNAP BI', kind: 'keyword' },
  { insert: '"currency":', label: '"currency"', detail: 'field SNAP BI', kind: 'keyword' },
  { insert: '"IDR"', label: '"IDR"', detail: 'mata uang', kind: 'keyword' },
  { insert: '"responseCode":', label: '"responseCode"', detail: 'response SNAP BI', kind: 'keyword' },
  { insert: 'true', label: 'true', detail: 'boolean', kind: 'keyword' },
  { insert: 'false', label: 'false', detail: 'boolean', kind: 'keyword' },
  { insert: 'null', label: 'null', detail: 'kosong', kind: 'keyword' },
];

export interface JsonEditorProps {
  value: unknown;
  onChange: (value: unknown) => void;
  height?: string;
  readOnly?: boolean;
  title?: string;
  placeholder?: string;
  actions?: React.ReactNode;
  onBlur?: () => void;
  /** Saran kunci tambahan khusus pemakaian ini. */
  extraKeys?: Suggestion[];
  /** Sembunyikan indikator validasi (mis. untuk pratinjau kecil). */
  hideStatus?: boolean;
}

/**
 * Editor JSON dua arah: menerima nilai terstruktur, menampilkan teks rapi, dan
 * hanya memanggil {@code onChange} saat teks valid.
 */
export function JsonEditor({
  value,
  onChange,
  height = '12rem',
  readOnly,
  title = 'JSON',
  placeholder = '{ }',
  actions,
  onBlur,
  extraKeys = [],
  hideStatus = false,
}: JsonEditorProps) {
  const [text, setText] = useState(() => safeStringify(value));
  const [error, setError] = useState<string | null>(null);

  // Nilai dari luar (mis. ganti node) menyetel ulang teks.
  useEffect(() => {
    setText(safeStringify(value));
    setError(null);
  }, [value]);

  const initial = useMemo(() => safeStringify(value), [value]);

  /** Validasi setiap perubahan; hanya nilai valid diteruskan. */
  const handleChange = (next: string) => {
    setText(next);
    if (!next.trim()) {
      setError(null);
      onChange(null);
      return;
    }
    try {
      onChange(JSON.parse(next));
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'JSON tidak valid');
    }
  };

  const format = () => {
    try {
      const parsed = JSON.parse(text);
      setText(JSON.stringify(parsed, null, 2));
      setError(null);
      onChange(parsed);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'JSON tidak valid');
    }
  };

  const suggest = (context: SuggestContext): Suggestion[] => {
    const before = context.value.slice(0, context.cursor);
    // jangan menyarankan di dalam string value
    const quoteCount = (before.match(/"/g) ?? []).length;
    const inString = quoteCount % 2 === 1;
    const currentLine = before.split('\n').pop() ?? '';
    // sarankan hanya untuk kunci baru (baris kosong atau setelah { , [)
    if (inString && !/[{"\[,]\s*"/.test(currentLine)) {
      return [];
    }
    const typed = context.prefix.replace(/^"/, '');
    return filterSuggestions([...COMMON_KEYS, ...extraKeys], typed);
  };

  const statusActions = (
    <>
      <Button size="xs" variant="ghost" onClick={format} disabled={readOnly || !!error}>
        Rapikan
      </Button>
      {!hideStatus &&
        (error ? (
          <Badge tone="error" size="xs">
            tidak valid
          </Badge>
        ) : text.trim() ? (
          <Badge tone="success" size="xs">
            valid
          </Badge>
        ) : null)}
      {actions}
    </>
  );

  return (
    <div>
      <CodeEditor
        value={text}
        onChange={handleChange}
        language={jsonLanguage}
        height={height}
        readOnly={readOnly}
        placeholder={placeholder}
        title={title}
        actions={statusActions}
        onBlur={onBlur}
        indentSize={2}
        suggest={readOnly ? undefined : suggest}
      />
      {!hideStatus && error && (
        <p className="mt-1 truncate text-[10px] text-rose-400" title={error}>
          {error}
        </p>
      )}
      {!hideStatus && !error && text.trim() && text !== initial && (
        <p className="mt-1 text-[10px] text-amber-400/80">belum disimpan ke node</p>
      )}
    </div>
  );
}

/** Serialisasi aman: nilai tak terdefinisi menjadi objek kosong. */
function safeStringify(value: unknown): string {
  if (value === undefined || value === null) {
    return '';
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return '';
  }
}
