import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import {
  TOKEN_CLASS,
  tokenizeLine,
  type LanguageSpec,
  type Token,
} from './tokenizer';

/** Satu saran autocomplete. */
export interface Suggestion {
  /** Teks yang disisipkan saat dipilih. */
  insert: string;
  /** Label yang ditampilkan. */
  label: string;
  /** Keterangan singkat (jenis/nilai). */
  detail?: string;
  /** Kategori untuk pengelompokan/ikon. */
  kind?: 'keyword' | 'snippet' | 'variable' | 'function' | 'type' | 'table' | 'column';
}

/** Konteks yang diberikan ke penyedia saran. */
export interface SuggestContext {
  /** Teks lengkap. */
  value: string;
  /** Posisi kursor. */
  cursor: number;
  /** Baris tempat kursor berada (0-based). */
  line: number;
  /** Prefiks kata yang sedang diketik sebelum kursor. */
  prefix: string;
}

export interface CodeEditorProps {
  value: string;
  onChange: (value: string) => void;
  /** Bahasa untuk pewarnaan. */
  language: LanguageSpec;
  /** Tinggi area editor (kelas Tailwind atau px). */
  height?: string;
  /** Placeholder saat kosong. */
  placeholder?: string;
  /** Baca-saja (mis. menampilkan hasil). */
  readOnly?: boolean;
  /** Jumlah ruang indentasi. */
  indentSize?: number;
  /** Penyedia saran autocomplete. */
  suggest?: (context: SuggestContext) => Suggestion[];
  /** Label di header editor. */
  title?: string;
  /** Konten tambahan di header (mis. tombol Check). */
  actions?: React.ReactNode;
  /** Dipanggil saat kehilangan fokus. */
  onBlur?: () => void;
  /** Dipanggil saat Ctrl/Cmd+Enter ditekan (mis. jalankan). */
  onRunShortcut?: () => void;
  /** Tampilkan nomor baris. */
  lineNumbers?: boolean;
  /** Izinkan perbesar ke layar penuh. */
  expandable?: boolean;
  /** Ukuran font pada mode layar penuh. */
  expandedHeight?: string;
}

/**
 * Editor kode ringan dengan pewarnaan sintaks dan autocomplete.
 *
 * <p>Arsitektur: sebuah {@code textarea} transparan ditumpuk tepat di atas
 * lapisan {@code <pre>} berisi token berwarna. Textarea menangani input,
 * seleksi, dan caret; lapisan highlight hanya visual. Keduanya memakai metrik
 * font identik sehingga posisi kursor selalu selaras.
 *
 * <p>Pendekatan ini menjaga bundle tetap kecil dan perilaku native
 * (undo/redo, IME, aksesibilitas) tetap bekerja.
 */
export function CodeEditor({
  value,
  onChange,
  language,
  height = '18rem',
  placeholder,
  readOnly = false,
  indentSize = 4,
  suggest,
  title,
  actions,
  onBlur,
  onRunShortcut,
  lineNumbers = true,
  expandable = true,
  expandedHeight = 'calc(100vh - 12rem)',
}: CodeEditorProps) {
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const highlightRef = useRef<HTMLDivElement>(null);
  const gutterRef = useRef<HTMLDivElement>(null);

  const [suggestions, setSuggestions] = useState<Suggestion[]>([]);
  const [activeSuggestion, setActiveSuggestion] = useState(0);
  const [suggestOpen, setSuggestOpen] = useState(false);
  const [expanded, setExpanded] = useState(false);

  // Esc menutup mode layar penuh; daftar saran ditangani di handleKeyDown.
  useEffect(() => {
    if (!expanded) {
      return;
    }
    const handleKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setExpanded(false);
      }
    };
    window.addEventListener('keydown', handleKey);
    // Tandai bahwa editor layar penuh terbuka, agar overlay lain (modal/panel)
    // tidak ikut menutup pada penekanan Esc yang sama.
    document.body.dataset.editorFullscreen = '1';
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      window.removeEventListener('keydown', handleKey);
      delete document.body.dataset.editorFullscreen;
      document.body.style.overflow = previousOverflow;
    };
  }, [expanded]);

  const indent = ' '.repeat(indentSize);
  const lines = value.split('\n');

  /** Token per baris, dihitung ulang saat nilai berubah. */
  const tokenized = useMemo(() => {
    const result: Token[][] = [];
    let inBlock = false;
    for (const line of lines) {
      const { tokens, inBlock: next } = tokenizeLine(line, language, inBlock);
      result.push(tokens);
      inBlock = next;
    }
    return result;
  }, [lines, language]);

  /** Sinkronkan scroll lapisan highlight dan gutter dengan textarea. */
  const syncScroll = useCallback(() => {
    const textarea = textareaRef.current;
    if (!textarea) {
      return;
    }
    if (highlightRef.current) {
      highlightRef.current.scrollTop = textarea.scrollTop;
      highlightRef.current.scrollLeft = textarea.scrollLeft;
    }
    if (gutterRef.current) {
      gutterRef.current.scrollTop = textarea.scrollTop;
    }
  }, []);

  useLayoutEffect(() => {
    syncScroll();
  }, [value, syncScroll]);

  // ------------------------------------------------------------------ saran

  /** Hitung prefiks kata sebelum kursor. */
  const prefixAt = useCallback((text: string, cursor: number): string => {
    let start = cursor;
    while (start > 0 && /[A-Za-z0-9_$.]/.test(text[start - 1])) {
      start--;
    }
    return text.slice(start, cursor);
  }, []);

  /** Perbarui daftar saran berdasarkan posisi kursor. */
  const refreshSuggestions = useCallback(
    (text: string, cursor: number, force = false) => {
      if (!suggest || readOnly) {
        setSuggestOpen(false);
        return;
      }
      const prefix = prefixAt(text, cursor);
      const beforeCursor = text.slice(0, cursor);
      const line = beforeCursor.split('\n').length - 1;
      // hanya sarankan setelah minimal 1 karakter, kecuali dipaksa (Ctrl+Space)
      if (!force && prefix.length < 1) {
        setSuggestOpen(false);
        return;
      }
      const items = suggest({ value: text, cursor, line, prefix });
      if (items.length === 0) {
        setSuggestOpen(false);
        return;
      }
      setSuggestions(items.slice(0, 12));
      setActiveSuggestion(0);
      setSuggestOpen(true);
    },
    [suggest, readOnly, prefixAt],
  );

  /** Sisipkan saran terpilih, mengganti prefiks yang sudah diketik. */
  const applySuggestion = useCallback(
    (suggestion: Suggestion) => {
      const textarea = textareaRef.current;
      if (!textarea) {
        return;
      }
      const cursor = textarea.selectionStart;
      const prefix = prefixAt(value, cursor);
      const start = cursor - prefix.length;
      // pertahankan suffix identifier yang sudah ada di kanan kursor
      let end = cursor;
      while (end < value.length && /[A-Za-z0-9_]/.test(value[end])) {
        end++;
      }
      const next = value.slice(0, start) + suggestion.insert + value.slice(end);
      const nextCursor = start + suggestion.insert.length;
      onChange(next);
      setSuggestOpen(false);
      requestAnimationFrame(() => {
        textarea.focus();
        textarea.setSelectionRange(nextCursor, nextCursor);
      });
    },
    [value, onChange, prefixAt],
  );

  // ------------------------------------------------------------------ input

  /** Tangani tombol khusus: indent, auto-pair, auto-indent, navigasi saran. */
  const handleKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    const textarea = event.currentTarget;
    const { selectionStart, selectionEnd } = textarea;
    const multiline = selectionStart !== selectionEnd;

    // navigasi daftar saran
    if (suggestOpen && suggestions.length > 0) {
      if (event.key === 'ArrowDown') {
        event.preventDefault();
        setActiveSuggestion((current) => (current + 1) % suggestions.length);
        return;
      }
      if (event.key === 'ArrowUp') {
        event.preventDefault();
        setActiveSuggestion((current) => (current - 1 + suggestions.length) % suggestions.length);
        return;
      }
      if (event.key === 'Enter' || event.key === 'Tab') {
        event.preventDefault();
        applySuggestion(suggestions[activeSuggestion]);
        return;
      }
      if (event.key === 'Escape') {
        event.preventDefault();
        setSuggestOpen(false);
        return;
      }
    }

    // Ctrl/Cmd+Space memaksa saran
    if (event.key === ' ' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      refreshSuggestions(value, selectionStart, true);
      return;
    }

    // Ctrl/Cmd+Enter menjalankan aksi
    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && onRunShortcut) {
      event.preventDefault();
      onRunShortcut();
      return;
    }

    if (readOnly) {
      return;
    }

    // Tab: indentasi atau kurangi indentasi
    if (event.key === 'Tab') {
      event.preventDefault();
      if (event.shiftKey) {
        // kurangi indentasi baris terpilih
        const lineStart = value.lastIndexOf('\n', selectionStart - 1) + 1;
        const lineEnd = value.indexOf('\n', selectionEnd);
        const block = value.slice(lineStart, lineEnd === -1 ? value.length : lineEnd);
        const dedented = block.split('\n').map((line) => line.replace(new RegExp(`^ {1,${indentSize}}`), '')).join('\n');
        const next = value.slice(0, lineStart) + dedented + value.slice(lineEnd === -1 ? value.length : lineEnd);
        onChange(next);
        requestAnimationFrame(() => {
          textarea.setSelectionRange(lineStart, lineStart + dedented.length);
        });
        return;
      }
      if (multiline) {
        // indentasi seluruh baris terpilih
        const lineStart = value.lastIndexOf('\n', selectionStart - 1) + 1;
        const lineEnd = value.indexOf('\n', selectionEnd);
        const block = value.slice(lineStart, lineEnd === -1 ? value.length : lineEnd);
        const indented = block.split('\n').map((line) => indent + line).join('\n');
        const next = value.slice(0, lineStart) + indented + value.slice(lineEnd === -1 ? value.length : lineEnd);
        onChange(next);
        return;
      }
      const next = value.slice(0, selectionStart) + indent + value.slice(selectionEnd);
      onChange(next);
      requestAnimationFrame(() => {
        textarea.setSelectionRange(selectionStart + indent.length, selectionStart + indent.length);
      });
      return;
    }

    // Enter: auto-indent mengikuti baris sebelumnya + buka blok
    if (event.key === 'Enter') {
      event.preventDefault();
      const lineStart = value.lastIndexOf('\n', selectionStart - 1) + 1;
      const currentLine = value.slice(lineStart, selectionStart);
      const leading = currentLine.match(/^[ \t]*/)?.[0] ?? '';
      const trimmed = currentLine.trimEnd();
      const opensBlock = /[{([]$/.test(trimmed);
      const nextIndent = opensBlock ? leading + indent : leading;
      const closing = opensBlock && /[)\]}],?$/.test(trimmed) ? '' : '';
      const insertion = `\n${nextIndent}${closing}`;
      const next = value.slice(0, selectionStart) + insertion + value.slice(selectionEnd);
      onChange(next);
      setSuggestOpen(false);
      requestAnimationFrame(() => {
        const pos = selectionStart + insertion.length - closing.length;
        textarea.setSelectionRange(pos, pos);
      });
      return;
    }

    // Auto-pair: tanda kurung dan kutip
    const pairs: Record<string, string> = {
      '(': ')',
      '{': '}',
      '[': ']',
      '"': '"',
      "'": "'",
    };
    if (pairs[event.key]) {
      const closing = pairs[event.key];
      const selected = value.slice(selectionStart, selectionEnd);
      event.preventDefault();
      const next =
        value.slice(0, selectionStart) + event.key + selected + closing + value.slice(selectionEnd);
      onChange(next);
      requestAnimationFrame(() => {
        if (selected) {
          textarea.setSelectionRange(selectionStart + 1, selectionStart + 1 + selected.length);
        } else {
          const pos = selectionStart + 1;
          textarea.setSelectionRange(pos, pos);
        }
      });
      return;
    }

    // Lompati penutup yang sudah ada
    if ([')', '}', ']', '"', "'"].includes(event.key) && value[selectionStart] === event.key && !multiline) {
      event.preventDefault();
      requestAnimationFrame(() => {
        textarea.setSelectionRange(selectionStart + 1, selectionStart + 1);
      });
    }
  };

  /** Perbarui saran saat pengguna mengetik. */
  const handleChange = (event: React.ChangeEvent<HTMLTextAreaElement>) => {
    const next = event.target.value;
    onChange(next);
    // setState belum tentu sinkron; hitung posisi dari event
    const cursor = event.target.selectionStart ?? next.length;
    refreshSuggestions(next, cursor);
  };

  const handleCursor = () => {
    // tutup saran saat kursor berpindah tanpa mengetik
    if (suggestOpen) {
      setSuggestOpen(false);
    }
  };

  // baris yang sedang aktif (untuk sorot gutter)
  const activeLine = useMemo(() => {
    const textarea = textareaRef.current;
    if (!textarea) {
      return 0;
    }
    return value.slice(0, textarea.selectionStart).split('\n').length - 1;
  }, [value]);

  /** Layout bersama: font, padding, line-height harus identik antara lapisan. */
  const sharedText =
    'font-mono text-[12.5px] leading-[1.55] whitespace-pre tab-size-4';

  /** Header dengan judul, aksi, dan tombol perbesar. */
  const header = (expanded: boolean) => (
    <div className="flex shrink-0 items-center justify-between gap-2 border-b border-slate-800 bg-slate-900/70 px-2 py-1">
      <span className="min-w-0 truncate text-[10px] font-semibold uppercase tracking-wider text-slate-500">
        {title ?? 'editor'}
      </span>
      <div className="flex shrink-0 items-center gap-1">
        {actions}
        {expandable && (
          <button
            type="button"
            onClick={() => setExpanded(!expanded)}
            title={expanded ? 'Perkecil (Esc)' : 'Perbesar ke layar penuh'}
            aria-label={expanded ? 'Perkecil editor' : 'Perbesar editor'}
            className="rounded px-1.5 py-0.5 text-[11px] text-slate-400 hover:bg-slate-800 hover:text-slate-100"
          >
            {expanded ? '⤡' : '⤢'}
          </button>
        )}
      </div>
    </div>
  );

  /** Badan editor; dirender di panel atau layar penuh. */
  const body = (heightValue: string) => (
    <div className="relative flex min-h-0 flex-1" style={{ height: expandable ? undefined : heightValue }}>
      {/* Gutter nomor baris */}
      {lineNumbers && (
        <div
          ref={gutterRef}
          className="shrink-0 select-none overflow-hidden border-r border-slate-800 bg-slate-900/40 py-2 text-right"
          style={{ width: `${Math.max(2, String(lines.length).length) + 1}ch` }}
        >
          <div className={sharedText}>
            {lines.map((_, index) => (
              <div
                key={index}
                className={`px-2 ${
                  index === activeLine ? 'bg-slate-800/60 text-slate-300' : 'text-slate-600'
                }`}
              >
                {index + 1}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Area editor */}
      <div className="relative min-w-0 flex-1">
        {/* Lapisan highlight */}
        <div
          ref={highlightRef}
          aria-hidden="true"
          className={`pointer-events-none absolute inset-0 overflow-auto p-2 ${sharedText}`}
        >
          {tokenized.map((tokens, index) => (
            <div key={index}>
              {tokens.length === 0 ? (
                <span> </span>
              ) : (
                tokens.map((token, tokenIndex) => (
                  <span key={tokenIndex} className={TOKEN_CLASS[token.kind]}>
                    {token.text}
                  </span>
                ))
              )}
            </div>
          ))}
          {/* baris terakhir kosong agar scroll sejajar */}
          <div>&nbsp;</div>
        </div>

        {/* Textarea transparan */}
        <textarea
          ref={textareaRef}
          value={value}
          onChange={handleChange}
          onKeyDown={handleKeyDown}
          onScroll={syncScroll}
          onBlur={() => {
            setSuggestOpen(false);
            onBlur?.();
          }}
          onClick={handleCursor}
          onKeyUp={handleCursor}
          readOnly={readOnly}
          spellCheck={false}
          autoComplete="off"
          autoCorrect="off"
          autoCapitalize="off"
          placeholder={placeholder}
          aria-label={title ?? 'editor kode'}
          className={`absolute inset-0 h-full w-full resize-none overflow-auto bg-transparent p-2 text-transparent caret-sky-300 outline-none selection:bg-indigo-500/30 placeholder:text-slate-600 ${sharedText}`}
        />

        {/* Daftar saran */}
        {suggestOpen && suggestions.length > 0 && (
          <SuggestionList
            suggestions={suggestions}
            active={activeSuggestion}
            onPick={applySuggestion}
            onHover={setActiveSuggestion}
          />
        )}
      </div>
    </div>
  );

  return (
    <>
      {/*
        Hanya satu instance yang dirender pada satu waktu. Merender panel dan
        layar penuh bersamaan akan membuat ref (textarea/highlight/gutter)
        saling menimpa sehingga scroll dan caret tidak sinkron.
      */}
      {!expanded && (
        <div className="overflow-hidden rounded-lg border border-slate-800 bg-slate-950">
          {(title || actions || expandable) && header(false)}
          <div style={{ height }} className="flex">
            {body(height)}
          </div>
        </div>
      )}

      {expanded && (
        <div
          className="fixed inset-0 z-[60] flex flex-col bg-slate-950/95 p-3 backdrop-blur-sm"
          role="dialog"
          aria-modal="true"
          aria-label={`${title ?? 'editor'} layar penuh`}
        >
          <div className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-xl border border-slate-700 bg-slate-950 shadow-2xl">
            {header(true)}
            <div className="flex min-h-0 flex-1" style={{ height: expandedHeight }}>
              {body(expandedHeight)}
            </div>
          </div>
          <p className="mt-1.5 text-center text-[10px] text-slate-500">
            Esc untuk menutup · Ctrl+Space untuk saran
          </p>
        </div>
      )}
    </>
  );
}

/** Daftar saran autocomplete dengan navigasi keyboard. */
function SuggestionList({
  suggestions,
  active,
  onPick,
  onHover,
}: {
  suggestions: Suggestion[];
  active: number;
  onPick: (suggestion: Suggestion) => void;
  onHover: (index: number) => void;
}) {
  const KIND_TONE: Record<NonNullable<Suggestion['kind']>, string> = {
    keyword: 'text-rose-400',
    snippet: 'text-violet-300',
    variable: 'text-sky-300',
    function: 'text-cyan-300',
    type: 'text-amber-300',
    table: 'text-emerald-300',
    column: 'text-slate-300',
  };

  return (
    <div
      role="listbox"
      className="absolute left-2 top-full z-30 mt-1 max-h-56 w-80 overflow-y-auto rounded-lg border border-slate-700 bg-slate-900/98 py-1 shadow-2xl backdrop-blur"
    >
      {suggestions.map((suggestion, index) => (
        <button
          key={`${suggestion.insert}-${index}`}
          type="button"
          role="option"
          aria-selected={index === active}
          onMouseEnter={() => onHover(index)}
          onMouseDown={(event) => {
            event.preventDefault();
            onPick(suggestion);
          }}
          className={`flex w-full items-start gap-2 px-2 py-1 text-left ${
            index === active ? 'bg-indigo-600/25' : 'hover:bg-slate-800/70'
          }`}
        >
          <span
            className={`mt-[2px] shrink-0 text-[9px] font-bold uppercase ${
              KIND_TONE[suggestion.kind ?? 'keyword']
            }`}
          >
            {(suggestion.kind ?? 'kw').slice(0, 4)}
          </span>
          <span className="min-w-0 flex-1">
            <span className="block truncate font-mono text-[11px] text-slate-100">
              {suggestion.label}
            </span>
            {suggestion.detail && (
              <span className="block truncate text-[10px] text-slate-500">{suggestion.detail}</span>
            )}
          </span>
        </button>
      ))}
    </div>
  );
}

/** Hook pembantu: saran statis yang difilter berdasarkan prefiks. */
export function filterSuggestions(items: Suggestion[], prefix: string): Suggestion[] {
  if (!prefix) {
    return items;
  }
  const lower = prefix.toLowerCase();
  // utamakan yang diawali prefiks, lalu yang memuat prefiks
  const starts = items.filter((item) => item.label.toLowerCase().startsWith(lower));
  const contains = items.filter(
    (item) => !item.label.toLowerCase().startsWith(lower) && item.label.toLowerCase().includes(lower),
  );
  return [...starts, ...contains];
}

/** Sembunyikan saran saat komponen tidak fokus (dipakai internal). */
export function useDismissOnOutside(
  ref: React.RefObject<HTMLElement>,
  onDismiss: () => void,
  enabled: boolean,
): void {
  useEffect(() => {
    if (!enabled) {
      return;
    }
    const handler = (event: MouseEvent) => {
      if (ref.current && !ref.current.contains(event.target as Node)) {
        onDismiss();
      }
    };
    window.addEventListener('mousedown', handler);
    return () => window.removeEventListener('mousedown', handler);
  }, [ref, onDismiss, enabled]);
}
