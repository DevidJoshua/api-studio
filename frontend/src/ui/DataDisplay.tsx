import { useEffect, useMemo, useRef, useState } from 'react';
import {
  TOKEN_CLASS,
  jsonLanguage,
  javaLanguage,
  sqlLanguage,
  expressionLanguage,
  tokenizeLine,
  type LanguageSpec,
  type Token,
} from './editor/tokenizer';
import type { ReactNode } from 'react';

/** Kolom tabel. */
export interface Column<T> {
  key: string;
  header: ReactNode;
  render?: (row: T, index: number) => ReactNode;
  className?: string;
  hideOnMobile?: boolean;
}

/** Tabel data dengan klik baris opsional. */
export function DataTable<T>({
  columns,
  rows,
  rowKey,
  onRowClick,
  emptyMessage = 'Belum ada data',
  dense = false,
}: {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T, index: number) => string;
  onRowClick?: (row: T) => void;
  emptyMessage?: ReactNode;
  dense?: boolean;
}) {
  if (rows.length === 0) {
    return <p className="px-3 py-4 text-center text-[11px] text-slate-500">{emptyMessage}</p>;
  }
  return (
    <div className="overflow-x-auto custom-scrollbar">
      <table className="w-full border-collapse text-left">
        <thead>
          <tr className="border-b border-slate-800">
            {columns.map((column) => (
              <th
                key={column.key}
                className={`whitespace-nowrap px-3 ${dense ? 'py-1.5' : 'py-2'} text-[10px] font-semibold uppercase tracking-wider text-slate-500 ${
                  column.hideOnMobile ? 'hidden md:table-cell' : ''
                } ${column.className ?? ''}`}
              >
                {column.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => (
            <tr
              key={rowKey(row, index)}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
              className={`border-b border-slate-800/60 text-xs text-slate-300 ${
                onRowClick ? 'cursor-pointer hover:bg-slate-800/40' : ''
              }`}
            >
              {columns.map((column) => (
                <td
                  key={column.key}
                  className={`px-3 ${dense ? 'py-1.5' : 'py-2'} ${
                    column.hideOnMobile ? 'hidden md:table-cell' : ''
                  } ${column.className ?? ''}`}
                >
                  {column.render
                    ? column.render(row, index)
                    : String((row as Record<string, unknown>)[column.key] ?? '—')}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Bahasa yang dikenali untuk pewarnaan blok kode read-only. */
const CODE_LANGUAGES: Record<string, LanguageSpec> = {
  json: jsonLanguage,
  java: javaLanguage,
  sql: sqlLanguage,
  expression: expressionLanguage,
  javascript: jsonLanguage,
  js: jsonLanguage,
};

/**
 * Blok kode dengan tombol salin.
 *
 * <p>Bila {@code language} cocok dengan bahasa yang dikenal (json/java/sql/
 * expression), isinya diwarnai memakai tokenizer yang sama dengan editor
 * sehingga tampilan kode konsisten di seluruh aplikasi. Bahasa lain
 * ditampilkan sebagai teks polos.
 */
export function CodeBlock({
  code,
  language,
  maxHeight = 'max-h-80',
  wrap = false,
  copyable = true,
}: {
  code: string;
  language?: string;
  maxHeight?: string;
  wrap?: boolean;
  copyable?: boolean;
}) {
  const [copied, setCopied] = useState(false);
  const timer = useRef<number | null>(null);

  useEffect(
    () => () => {
      if (timer.current !== null) {
        window.clearTimeout(timer.current);
      }
    },
    [],
  );

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      if (timer.current !== null) {
        window.clearTimeout(timer.current);
      }
      timer.current = window.setTimeout(() => setCopied(false), 1500);
    } catch {
      // clipboard tidak tersedia (mis. konteks non-HTTPS): diamkan saja
      setCopied(false);
    }
  };

  // Pilih bahasa: eksplisit, atau deteksi JSON dari bentuk isinya.
  const spec = useMemo<LanguageSpec | undefined>(() => {
    if (language) {
      return CODE_LANGUAGES[language.toLowerCase()];
    }
    const trimmed = code.trimStart();
    if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
      return jsonLanguage;
    }
    return undefined;
  }, [language, code]);

  /** Baris bertoken; null bila bahasa tidak dikenali (render polos). */
  const highlighted = useMemo(() => {
    if (!spec) {
      return null;
    }
    const result: Token[][] = [];
    let inBlock = false;
    for (const line of code.split('\n')) {
      const { tokens, inBlock: next } = tokenizeLine(line, spec, inBlock);
      result.push(tokens);
      inBlock = next;
    }
    return result;
  }, [code, spec]);

  return (
    <div className="overflow-hidden rounded-lg border border-slate-800 bg-slate-950">
      <div className="flex items-center justify-between gap-2 border-b border-slate-800 bg-slate-900/60 px-2 py-1">
        <span className="text-[10px] font-semibold uppercase tracking-wide text-slate-500">
          {language ?? (spec === jsonLanguage ? 'json' : 'code')}
        </span>
        {copyable && (
          <button
            type="button"
            onClick={copy}
            className="rounded px-1.5 py-0.5 text-[10px] text-slate-400 hover:bg-slate-800 hover:text-slate-100"
          >
            {copied ? 'Tersalin' : 'Salin'}
          </button>
        )}
      </div>
      <pre className={`overflow-auto ${maxHeight} p-3 font-mono text-[11px] leading-relaxed text-slate-300 ${
        wrap ? 'whitespace-pre-wrap break-words' : ''
      } custom-scrollbar`}>
        <code>
          {highlighted
            ? highlighted.map((tokens, index) => (
                <div key={index}>
                  {tokens.length === 0 ? (
                    '\u00A0'
                  ) : (
                    tokens.map((token, tokenIndex) => (
                      <span key={tokenIndex} className={TOKEN_CLASS[token.kind]}>
                        {token.text}
                      </span>
                    ))
                  )}
                </div>
              ))
            : code}
        </code>
      </pre>
    </div>
  );
}

/** Tab pilihan. */
export function Tabs({
  tabs,
  active,
  onChange,
  size = 'sm',
}: {
  tabs: { key: string; label: ReactNode; badge?: ReactNode }[];
  active: string;
  onChange: (key: string) => void;
  size?: 'xs' | 'sm';
}) {
  return (
    <div role="tablist" className="flex flex-wrap items-center gap-1 border-b border-slate-800">
      {tabs.map((tab) => (
        <button
          key={tab.key}
          type="button"
          role="tab"
          aria-selected={active === tab.key}
          onClick={() => onChange(tab.key)}
          className={`-mb-px flex items-center gap-1.5 border-b-2 transition-colors ${
            size === 'xs' ? 'px-2.5 py-1.5 text-[11px]' : 'px-3 py-2 text-xs'
          } ${
            active === tab.key
              ? 'border-indigo-500 font-semibold text-indigo-400'
              : 'border-transparent text-slate-400 hover:text-slate-200'
          }`}
        >
          {tab.label}
          {tab.badge}
        </button>
      ))}
    </div>
  );
}

/** Linimasa vertikal untuk riwayat/trace. */
export function Timeline({
  items,
}: {
  items: {
    key: string;
    title: ReactNode;
    subtitle?: ReactNode;
    meta?: ReactNode;
    tone?: 'default' | 'primary' | 'success' | 'warning' | 'error' | 'info' | 'muted';
    onClick?: () => void;
  }[];
}) {
  if (items.length === 0) {
    return <p className="py-3 text-center text-[11px] text-slate-500">Belum ada riwayat</p>;
  }
  return (
    <ol className="relative space-y-2 border-l border-slate-800 pl-3">
      {items.map((item) => (
        <li key={item.key} className="relative">
          <span
            className={`absolute -left-[17px] top-1.5 h-2 w-2 rounded-full ${
              item.tone === 'success'
                ? 'bg-emerald-400'
                : item.tone === 'error'
                  ? 'bg-rose-400'
                  : item.tone === 'warning'
                    ? 'bg-amber-400'
                    : item.tone === 'info'
                      ? 'bg-cyan-400'
                      : 'bg-slate-600'
            }`}
          />
          <div
            onClick={item.onClick}
            className={item.onClick ? 'cursor-pointer rounded px-1 hover:bg-slate-800/40' : 'px-1'}
          >
            <div className="flex items-baseline justify-between gap-2">
              <p className="min-w-0 text-[11px] font-medium text-slate-200">{item.title}</p>
              {item.meta && <span className="shrink-0 text-[10px] text-slate-500">{item.meta}</span>}
            </div>
            {item.subtitle && <p className="text-[10px] text-slate-500">{item.subtitle}</p>}
          </div>
        </li>
      ))}
    </ol>
  );
}
