/**
 * Parser dan serializer YAML minimal.
 *
 * <p>Menangani subset yang dipakai konfigurasi platform: map bersarang, list
 * (termasuk list of map), skalar (string/angka/boolean/null), string berkutip,
 * komentar, dan teks blok dasar. YAML penuh (anchor, merge key, tag, aliran
 * kompleks) tidak didukung karena tidak diperlukan di sini — dan menulis YAML
 * penuh berarti menambahkan dependensi besar untuk manfaat yang tidak
 * sebanding.
 *
 * <p>Modul ini murni (tanpa I/O) sehingga dapat diuji dan dipakai ulang oleh
 * editor maupun fitur ekspor.
 */

interface Line {
  indent: number;
  content: string;
  /** Nomor baris asal (0-based) untuk pesan error. */
  source: number;
}

export class YamlError extends Error {
  readonly line: number;

  constructor(message: string, line: number) {
    super(message);
    this.name = 'YamlError';
    this.line = line;
  }
}

/** Hilangkan komentar akhir baris yang tidak berada di dalam kutip. */
function stripComment(raw: string): string {
  let inQuote: string | null = null;
  for (let i = 0; i < raw.length; i++) {
    const ch = raw[i];
    if (inQuote) {
      if (ch === inQuote) {
        // kutip ganda: abaikan escape
        if (inQuote === '"' && raw[i - 1] === '\\') {
          continue;
        }
        inQuote = null;
      }
      continue;
    }
    if (ch === '"' || ch === "'") {
      inQuote = ch;
      continue;
    }
    if (ch === '#' && (i === 0 || /\s/.test(raw[i - 1]))) {
      return raw.slice(0, i);
    }
  }
  return raw;
}

/** Konversi skalar YAML ke nilai JavaScript. */
function parseScalar(text: string): unknown {
  const trimmed = text.trim();
  if (trimmed === '') {
    return null;
  }
  const lower = trimmed.toLowerCase();
  if (['true', 'yes', 'on'].includes(lower)) {
    return true;
  }
  if (['false', 'no', 'off'].includes(lower)) {
    return false;
  }
  if (lower === 'null' || trimmed === '~') {
    return null;
  }
  // string berkutip
  if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
    try {
      return JSON.parse(trimmed);
    } catch {
      return trimmed.slice(1, -1);
    }
  }
  if (trimmed.length >= 2 && trimmed.startsWith("'") && trimmed.endsWith("'")) {
    return trimmed.slice(1, -1).replace(/''/g, "'");
  }
  // collection inline
  if (trimmed.startsWith('[') || trimmed.startsWith('{')) {
    try {
      // JSON kompatibel untuk bentuk sederhana seperti ["a", "b"]
      return JSON.parse(trimmed);
    } catch {
      return trimmed;
    }
  }
  // angka
  if (/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(trimmed)) {
    return Number(trimmed);
  }
  return trimmed;
}

/** Ubah teks YAML menjadi nilai JavaScript. */
export function parseYaml(text: string): unknown {
  const rawLines = text.split(/\r?\n/);
  const lines: Line[] = [];

  for (let i = 0; i < rawLines.length; i++) {
    const noComment = stripComment(rawLines[i]);
    if (noComment.trim() === '') {
      continue;
    }
    const indentMatch = /^[ \t]*/.exec(noComment);
    const indent = indentMatch ? indentMatch[0].replace(/\t/g, '  ').length : 0;
    lines.push({ indent, content: noComment.trim(), source: i });
  }

  if (lines.length === 0) {
    return null;
  }

  let cursor = 0;

  /** Baca blok pada indentasi tertentu. */
  const parseBlock = (indent: number): unknown => {
    const first = lines[cursor];
    if (!first) {
      return null;
    }
    const isList = first.content === '-' || first.content.startsWith('- ');
    return isList ? parseList(indent) : parseMap(indent);
  };

  const parseList = (indent: number): unknown[] => {
    const result: unknown[] = [];
    while (cursor < lines.length) {
      const line = lines[cursor];
      if (line.indent < indent) {
        break;
      }
      if (line.indent > indent) {
        throw new YamlError(`Indentasi list tidak konsisten pada baris ${line.source + 1}`, line.source);
      }
      if (line.content !== '-' && !line.content.startsWith('- ')) {
        break;
      }
      const inline = line.content.slice(1).trim();
      cursor++;
      if (inline === '') {
        // item list berisi blok di baris berikutnya
        result.push(cursor < lines.length && lines[cursor].indent > indent
          ? parseBlock(lines[cursor].indent)
          : null);
        continue;
      }
      // item berbentuk "key: value" atau skalar
      const colon = findKeyColon(inline);
      if (colon >= 0) {
        // perlakukan sebagai map yang dimulai pada baris ini
        const map: Record<string, unknown> = {};
        const key = inline.slice(0, colon).trim();
        const rest = inline.slice(colon + 1).trim();
        if (rest === '') {
          map[key] = cursor < lines.length && lines[cursor].indent > indent
            ? parseBlock(lines[cursor].indent)
            : null;
        } else {
          map[key] = parseScalar(rest);
        }
        // lanjutkan key berikutnya pada indentasi item ini
        const childIndent = line.indent + 2;
        while (cursor < lines.length && lines[cursor].indent >= childIndent) {
          const child = lines[cursor];
          if (child.indent !== childIndent) {
            throw new YamlError(`Indentasi tidak konsisten pada baris ${child.source + 1}`, child.source);
          }
          const childColon = findKeyColon(child.content);
          if (childColon < 0) {
            throw new YamlError(`Harapan "kunci:" pada baris ${child.source + 1}`, child.source);
          }
          const childKey = child.content.slice(0, childColon).trim();
          const childRest = child.content.slice(childColon + 1).trim();
          cursor++;
          if (childRest === '') {
            map[childKey] = cursor < lines.length && lines[cursor].indent > childIndent
              ? parseBlock(lines[cursor].indent)
              : null;
          } else {
            map[childKey] = parseScalar(childRest);
          }
        }
        result.push(map);
        continue;
      }
      result.push(parseScalar(inline));
    }
    return result;
  };

  const parseMap = (indent: number): Record<string, unknown> => {
    const result: Record<string, unknown> = {};
    while (cursor < lines.length) {
      const line = lines[cursor];
      if (line.indent < indent) {
        break;
      }
      if (line.indent > indent) {
        throw new YamlError(`Indentasi tidak konsisten pada baris ${line.source + 1}`, line.source);
      }
      const colon = findKeyColon(line.content);
      if (colon < 0) {
        throw new YamlError(`Harapan "kunci: nilai" pada baris ${line.source + 1}`, line.source);
      }
      const key = line.content.slice(0, colon).trim();
      const rest = line.content.slice(colon + 1).trim();
      cursor++;
      if (rest === '') {
        result[key] = cursor < lines.length && lines[cursor].indent > indent
          ? parseBlock(lines[cursor].indent)
          : null;
      } else {
        result[key] = parseScalar(rest);
      }
    }
    return result;
  };

  const parsed = parseBlock(lines[0].indent);
  if (cursor < lines.length) {
    const extra = lines[cursor];
    throw new YamlError(`Indentasi berlebih pada baris ${extra.source + 1}`, extra.source);
  }
  return parsed;
}

/**
 * Cari posisi titik dua pemisah kunci.
 *
 * <p>Mengabaikan titik dua di dalam kutip dan yang bukan diikuti spasi/akhir
 * baris (mis. {@code http://...}).
 */
function findKeyColon(line: string): number {
  let inQuote: string | null = null;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (inQuote) {
      if (ch === inQuote && line[i - 1] !== '\\') {
        inQuote = null;
      }
      continue;
    }
    if (ch === '"' || ch === "'") {
      inQuote = ch;
      continue;
    }
    if (ch === ':' && (i + 1 === line.length || line[i + 1] === ' ')) {
      return i;
    }
  }
  return -1;
}

/** True bila nilai perlu dikutip agar tetap berupa string. */
function needsQuotes(text: string): boolean {
  if (text === '') {
    return true;
  }
  const lower = text.toLowerCase();
  if (['true', 'false', 'null', 'yes', 'no', 'on', 'off', '~'].includes(lower)) {
    return true;
  }
  if (/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(text)) {
    return true;
  }
  // karakter yang mengubah arti YAML
  return /[:#{}\[\],&*?|<>=!%@`"'\n]/.test(text) || /^\s|\s$/.test(text);
}

/** Serialisasi nilai menjadi teks YAML. */
export function toYaml(value: unknown, indent = 0): string {
  const pad = ' '.repeat(indent);

  if (value === null || value === undefined) {
    return 'null';
  }
  if (typeof value === 'boolean' || typeof value === 'number') {
    return String(value);
  }
  if (typeof value === 'string') {
    return needsQuotes(value) ? JSON.stringify(value) : value;
  }
  if (Array.isArray(value)) {
    if (value.length === 0) {
      return '[]';
    }
    return value
      .map((item) => {
        if (item !== null && typeof item === 'object' && !Array.isArray(item)) {
          const nested = toYaml(item, indent + 2).split('\n');
          // item map: "- kunci: nilai" pada baris pertama
          const firstLine = nested[0].slice(indent + 2);
          const restLines = nested.slice(1).join('\n');
          return restLines
            ? `${pad}- ${firstLine}\n${restLines}`
            : `${pad}- ${firstLine}`;
        }
        if (Array.isArray(item)) {
          return `${pad}-\n${toYaml(item, indent + 2)
            .split('\n')
            .map((line) => ' '.repeat(indent + 2) + line.trimStart())
            .join('\n')}`;
        }
        return `${pad}- ${toYaml(item, indent + 2)}`;
      })
      .join('\n');
  }
  if (typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>);
    if (entries.length === 0) {
      return '{}';
    }
    return entries
      .map(([key, val]) => {
        const rendered = toYaml(val, indent + 2);
        if (val !== null && typeof val === 'object') {
          if (Array.isArray(val) && val.length === 0) {
            return `${pad}${key}: []`;
          }
          if (!Array.isArray(val) && Object.keys(val as object).length === 0) {
            return `${pad}${key}: {}`;
          }
          return `${pad}${key}:\n${rendered}`;
        }
        return `${pad}${key}: ${rendered}`;
      })
      .join('\n');
  }
  return String(value);
}
