/**
 * Tokenizer ringan untuk syntax highlighting di dalam aplikasi.
 *
 * <p>Dibuat sendiri (tanpa Monaco/CodeMirror) karena kebutuhan aplikasi
 * terbatas: menampilkan kode pendek dengan pewarnaan dan autocomplete.
 * Menambah dependensi editor penuh akan menambah puluhan MB bundle untuk
 * manfaat yang tidak sebanding.
 *
 * <p>Token dikembalikan sebagai daftar {text, kind} tanpa posisi; renderer
 * menyusunnya berurutan. Pendekatan ini cukup untuk highlighting baris.
 */

/** Jenis token yang dikenali. */
export type TokenKind =
  | 'plain'
  | 'keyword'
  | 'type'
  | 'string'
  | 'number'
  | 'comment'
  | 'operator'
  | 'annotation'
  | 'punctuation'
  | 'constant'
  | 'builtin';

export interface Token {
  text: string;
  kind: TokenKind;
}

/** Definisi bahasa untuk tokenizer. */
export interface LanguageSpec {
  keywords: Set<string>;
  types?: Set<string>;
  constants?: Set<string>;
  builtins?: Set<string>;
  lineComment?: string;
  blockCommentStart?: string;
  blockCommentEnd?: string;
  /** Awalan anotasi/atribut, mis. '@' pada Java. */
  annotationPrefix?: string;
  /** Karakter string yang didukung. */
  quotes?: string[];
  /** Penanda khusus: baris ditangani tokenizer YAML. */
  lineBased?: 'yaml';
}

const JAVA_KEYWORDS = [
  'abstract', 'assert', 'boolean', 'break', 'byte', 'case', 'catch', 'char', 'class',
  'const', 'continue', 'default', 'do', 'double', 'else', 'enum', 'extends', 'final',
  'finally', 'float', 'for', 'goto', 'if', 'implements', 'import', 'instanceof', 'int',
  'interface', 'long', 'native', 'new', 'package', 'private', 'protected', 'public',
  'return', 'short', 'static', 'strictfp', 'super', 'switch', 'synchronized', 'this',
  'throw', 'throws', 'transient', 'try', 'void', 'volatile', 'while', 'var', 'record',
  'sealed', 'permits', 'yield', 'true', 'false', 'null',
];

const JAVA_TYPES = [
  'String', 'Object', 'Integer', 'Long', 'Double', 'Boolean', 'List', 'Map', 'Set',
  'ArrayList', 'LinkedHashMap', 'HashMap', 'HashSet', 'Optional', 'Stream', 'Math',
  'BigDecimal', 'BigInteger', 'Instant', 'LocalDate', 'LocalDateTime', 'Duration',
  'NodeContext', 'JavaNode', 'Exception', 'RuntimeException', 'IllegalStateException',
  'IllegalArgumentException', 'Number', 'CharSequence', 'Iterable', 'Collection',
  'Arrays', 'Collections', 'Objects', 'UUID', 'StringBuilder', 'Pattern', 'LocalTime',
];

const SQL_KEYWORDS = [
  'select', 'from', 'where', 'and', 'or', 'not', 'null', 'is', 'in', 'like', 'between',
  'order', 'by', 'asc', 'desc', 'group', 'having', 'limit', 'offset', 'join', 'inner',
  'left', 'right', 'full', 'outer', 'cross', 'on', 'as', 'distinct', 'count', 'sum',
  'avg', 'min', 'max', 'case', 'when', 'then', 'else', 'end', 'with', 'union', 'all',
  'exists', 'cast', 'coalesce', 'nullif',
];

const SQL_TYPES = ['int', 'integer', 'varchar', 'text', 'date', 'timestamp', 'numeric', 'decimal', 'boolean'];

const JSON_CONSTANTS = ['true', 'false', 'null'];

/** Spesifikasi bahasa Java (untuk node Java Code). */
export const javaLanguage: LanguageSpec = {
  keywords: new Set(JAVA_KEYWORDS),
  types: new Set(JAVA_TYPES),
  builtins: new Set(['ctx', 'java', 'io', 'apistudio', 'contracts', 'node']),
  lineComment: '//',
  blockCommentStart: '/*',
  blockCommentEnd: '*/',
  annotationPrefix: '@',
  quotes: ['"', "'"],
};

/** Spesifikasi SQL (untuk node Database Query). */
export const sqlLanguage: LanguageSpec = {
  keywords: new Set(SQL_KEYWORDS),
  types: new Set(SQL_TYPES),
  builtins: new Set(['?', '*']),
  lineComment: '--',
  blockCommentStart: '/*',
  blockCommentEnd: '*/',
  quotes: ["'", '"'],
};

/** Spesifikasi JSON. */
export const jsonLanguage: LanguageSpec = {
  keywords: new Set(),
  constants: new Set(JSON_CONSTANTS),
  quotes: ['"'],
};

/** Spesifikasi ekspresi flow (mirip ekspresi engine). */
export const expressionLanguage: LanguageSpec = {
  keywords: new Set(['true', 'false', 'null', 'and', 'or', 'not']),
  builtins: new Set([
    'request', 'vars', 'response', 'flow', 'inputs', 'loop',
    'upper', 'lower', 'trim', 'length', 'number', 'string', 'now', 'timestamp',
    'sha256', 'hmacSha256', 'base64', 'json', 'get', 'coalesce', 'isEmpty', 'uuid',
  ]),
  lineComment: '//',
  quotes: ["'", '"'],
};

/**
 * Spesifikasi YAML.
 *
 * <p>YAML tidak memakai tokenizer identifier seperti bahasa pemrograman, jadi
 * pewarnaannya ditangani {@link tokenizeYamlLine} yang memahami bentuk
 * {@code key: value}, penanda list, komentar, dan skalar.
 */
export const yamlLanguage: LanguageSpec = {
  keywords: new Set(['true', 'false', 'null', 'yes', 'no', 'on', 'off']),
  lineComment: '#',
  quotes: ['"', "'"],
  lineBased: 'yaml',
};

/** True bila baris YAML adalah komentar penuh. */
function isYamlCommentLine(line: string): boolean {
  return line.trimStart().startsWith('#');
}

/** Deteksi skalar YAML dan kembalikan jenis tokennya. */
function yamlScalarKind(text: string): TokenKind {
  const trimmed = text.trim();
  if (trimmed === '') {
    return 'plain';
  }
  const lower = trimmed.toLowerCase();
  if (['true', 'false', 'yes', 'no', 'on', 'off'].includes(lower)) {
    return 'constant';
  }
  if (lower === 'null' || trimmed === '~') {
    return 'constant';
  }
  if (/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(trimmed)) {
    return 'number';
  }
  if (trimmed.startsWith('&') || trimmed.startsWith('*')) {
    return 'annotation';
  }
  return 'string';
}

/**
 * Tokenisasi satu baris YAML.
 *
 * <p>Yang dikenali: indentasi (plain), komentar ({@code #}), penanda list
 * ({@code -}), kunci sebelum {@code :} (type/keyword), dan nilai skalar
 * (string/angka/boolean/null).
 *
 * @param line  baris tanpa newline
 * @param inBlock tidak dipakai YAML (tanpa blok komentar) — dijaga untuk
 *                keseragaman tanda tangan dengan {@link tokenizeLine}
 */
export function tokenizeYamlLine(line: string, inBlock = false): Token[] {
  void inBlock;
  const tokens: Token[] = [];

  // baris komentar penuh
  if (isYamlCommentLine(line)) {
    return [{ text: line, kind: 'comment' }];
  }

  let i = 0;

  // indentasi
  const indentMatch = /^[ \t]*/.exec(line);
  const indent = indentMatch ? indentMatch[0] : '';
  if (indent) {
    tokens.push({ text: indent, kind: 'plain' });
    i = indent.length;
  }

  // penanda list
  if (line.startsWith('- ', i)) {
    tokens.push({ text: '- ', kind: 'punctuation' });
    i += 2;
  } else if (line[i] === '-' && line.slice(i).trim() === '-') {
    tokens.push({ text: '-', kind: 'punctuation' });
    i += 1;
  }

  // sisa baris: kunci[: nilai] atau sekadar nilai
  const rest = line.slice(i);
  if (!rest) {
    return tokens;
  }

  // pisahkan komentar di akhir baris (jika ada, dan tidak di dalam kutip)
  let body = rest;
  let trailingComment = '';
  let inQuote: string | null = null;
  for (let p = 0; p < rest.length; p++) {
    const ch = rest[p];
    if (inQuote) {
      if (ch === inQuote) {
        inQuote = null;
      }
      continue;
    }
    if (ch === '"' || ch === "'") {
      inQuote = ch;
      continue;
    }
    if (ch === '#' && (p === 0 || /\s/.test(rest[p - 1]))) {
      body = rest.slice(0, p);
      trailingComment = rest.slice(p);
      break;
    }
  }

  // deteksi "key:" atau "key: value"
  const keyMatch = /^([^:\s][^:]*?)(:)(\s|$)/.exec(body);
  if (keyMatch) {
    tokens.push({ text: keyMatch[1], kind: 'keyword' });
    tokens.push({ text: keyMatch[2], kind: 'punctuation' });
    const afterKey = body.slice(keyMatch[0].length);
    if (afterKey) {
      // nilai setelah kunci
      if (afterKey.startsWith(' ')) {
        tokens.push({ text: ' ', kind: 'plain' });
      }
      const valueText = afterKey.trimStart();
      const leading = afterKey.slice(0, afterKey.length - valueText.length);
      if (leading && leading !== ' ') {
        tokens.push({ text: leading, kind: 'plain' });
      }
      if (valueText.startsWith('[') || valueText.startsWith('{')) {
        // inline collection: warnai seadanya sebagai string
        tokens.push({ text: valueText, kind: 'string' });
      } else {
        tokens.push({ text: valueText, kind: yamlScalarKind(valueText) });
      }
    }
  } else {
    tokens.push({ text: body, kind: yamlScalarKind(body) });
  }

  if (trailingComment) {
    tokens.push({ text: trailingComment, kind: 'comment' });
  }

  return tokens;
}


/** True bila karakter dapat menjadi bagian identifier. */
function isIdentifierChar(ch: string): boolean {
  return /[A-Za-z0-9_$]/.test(ch);
}

function isDigit(ch: string): boolean {
  return ch >= '0' && ch <= '9';
}

/**
 * Pecah satu baris kode menjadi token.
 *
 * <p>Beroperasi per baris agar tetap deterministik meski blok komentar
 * melewati beberapa baris; status blok komentar diteruskan oleh pemanggil.
 *
 * @param line       baris tanpa newline
 * @param spec       spesifikasi bahasa
 * @param inBlock    true bila baris diawali di dalam blok komentar
 * @returns token dan status blok komentar setelah baris ini
 */
export function tokenizeLine(
  line: string,
  spec: LanguageSpec,
  inBlock: boolean,
): { tokens: Token[]; inBlock: boolean } {
  const tokens: Token[] = [];
  let i = 0;
  let block = inBlock;

  const push = (text: string, kind: TokenKind) => {
    if (text) {
      tokens.push({ text, kind });
    }
  };

  while (i < line.length) {
    // lanjutan blok komentar
    if (block) {
      const end = spec.blockCommentEnd ? line.indexOf(spec.blockCommentEnd, i) : -1;
      if (end === -1) {
        push(line.slice(i), 'comment');
        return { tokens, inBlock: true };
      }
      push(line.slice(i, end + spec.blockCommentEnd!.length), 'comment');
      i = end + spec.blockCommentEnd!.length;
      block = false;
      continue;
    }

    const ch = line[i];
    const rest = line.slice(i);

    // komentar baris
    if (spec.lineComment && rest.startsWith(spec.lineComment)) {
      push(rest, 'comment');
      return { tokens, inBlock: false };
    }

    // awal blok komentar
    if (spec.blockCommentStart && rest.startsWith(spec.blockCommentStart)) {
      const end = spec.blockCommentEnd ? line.indexOf(spec.blockCommentEnd, i) : -1;
      if (end === -1) {
        push(rest, 'comment');
        return { tokens, inBlock: true };
      }
      push(line.slice(i, end + spec.blockCommentEnd!.length), 'comment');
      i = end + spec.blockCommentEnd!.length;
      continue;
    }

    // anotasi (Java)
    if (spec.annotationPrefix && ch === spec.annotationPrefix) {
      let j = i + 1;
      while (j < line.length && isIdentifierChar(line[j])) {
        j++;
      }
      push(line.slice(i, j), 'annotation');
      i = j;
      continue;
    }

    // string
    if (spec.quotes?.includes(ch)) {
      let j = i + 1;
      while (j < line.length) {
        if (line[j] === '\\') {
          j += 2;
          continue;
        }
        if (line[j] === ch) {
          j++;
          break;
        }
        j++;
      }
      push(line.slice(i, j), 'string');
      i = j;
      continue;
    }

    // angka
    if (isDigit(ch)) {
      let j = i;
      while (j < line.length && /[0-9._eExXa-fA-F]/.test(line[j])) {
        j++;
      }
      push(line.slice(i, j), 'number');
      i = j;
      continue;
    }

    // identifier / kata kunci
    if (/[A-Za-z_$]/.test(ch)) {
      let j = i;
      while (j < line.length && isIdentifierChar(line[j])) {
        j++;
      }
      const word = line.slice(i, j);
      const kind: TokenKind = spec.keywords.has(word)
        ? 'keyword'
        : spec.types?.has(word)
          ? 'type'
          : spec.constants?.has(word)
            ? 'constant'
            : spec.builtins?.has(word)
              ? 'builtin'
              : 'plain';
      push(word, kind);
      i = j;
      continue;
    }

    // operator & tanda baca
    if ('+-*/%=<>!&|^~?:'.includes(ch)) {
      push(ch, 'operator');
      i++;
      continue;
    }
    if ('(){}[];,."'.includes(ch)) {
      push(ch, 'punctuation');
      i++;
      continue;
    }

    // spasi & karakter lain
    let j = i;
    while (j < line.length && !isIdentifierChar(line[j]) && !'+-*/%=<>!&|^~?:(){}[];,."\''.includes(line[j])) {
      j++;
    }
    if (j === i) {
      j++;
    }
    push(line.slice(i, j), 'plain');
    i = j;
  }

  return { tokens, inBlock: false };
}

/** Tokenisasi seluruh teks, mempertahankan newline sebagai token terpisah. */
export function tokenize(code: string, spec: LanguageSpec): Token[][] {
  const lines = code.split('\n');
  // YAML memakai penanganan per baris sendiri (bentuk key: value, list, komentar)
  if (spec.lineBased === 'yaml') {
    return lines.map((line) => tokenizeYamlLine(line));
  }
  const result: Token[][] = [];
  let inBlock = false;
  for (const line of lines) {
    const { tokens, inBlock: next } = tokenizeLine(line, spec, inBlock);
    result.push(tokens);
    inBlock = next;
  }
  return result;
}

/** Kelas warna Tailwind per jenis token (tema gelap ala IDE). */
export const TOKEN_CLASS: Record<TokenKind, string> = {
  plain: 'text-slate-200',
  keyword: 'text-rose-400',
  type: 'text-cyan-300',
  string: 'text-emerald-300',
  number: 'text-amber-300',
  comment: 'text-slate-500 italic',
  operator: 'text-slate-400',
  annotation: 'text-amber-400',
  punctuation: 'text-slate-400',
  constant: 'text-violet-300',
  builtin: 'text-sky-300',
};
