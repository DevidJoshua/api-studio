import type { FlowEdge, FlowNode, NodeType } from '../../api/types';

/**
 * Metadata node untuk palette dan kanvas.
 *
 * Satu sumber kebenaran untuk label, kategori, warna, dan port. Menambah jenis
 * node baru cukup menambah entri di sini (dan eksekutor di backend).
 */
export interface NodeMeta {
  type: NodeType;
  label: string;
  category: string;
  description: string;
  color: string;
  bg: string;
  /** Port keluar yang dimiliki node. */
  ports: string[];
  /** True bila node menghentikan alur (RESPONSE). */
  terminal?: boolean;
}

export const NODE_META: Record<NodeType, NodeMeta> = {
  TRIGGER: {
    type: 'TRIGGER',
    label: 'HTTP Endpoint',
    category: 'Trigger',
    description: 'Titik masuk request klien; satu per flow.',
    color: 'text-emerald-400',
    bg: 'bg-emerald-400/10',
    ports: ['out'],
  },
  SET_VARIABLE: {
    type: 'SET_VARIABLE',
    label: 'Set Variable',
    category: 'Logic',
    description: 'Menetapkan variabel dari ekspresi.',
    color: 'text-sky-400',
    bg: 'bg-sky-400/10',
    ports: ['out'],
  },
  TRANSFORM: {
    type: 'TRANSFORM',
    label: 'Transform',
    category: 'Logic',
    description: 'Transformasi data dengan ekspresi.',
    color: 'text-sky-400',
    bg: 'bg-sky-400/10',
    ports: ['out'],
  },
  CONDITION: {
    type: 'CONDITION',
    label: 'Condition',
    category: 'Logic',
    description: 'Percabangan true/false.',
    color: 'text-amber-400',
    bg: 'bg-amber-400/10',
    ports: ['true', 'false'],
  },
  SWITCH: {
    type: 'SWITCH',
    label: 'Switch',
    category: 'Logic',
    description: 'Percabangan banyak case.',
    color: 'text-amber-400',
    bg: 'bg-amber-400/10',
    ports: ['case', 'default'],
  },
  LOOP: {
    type: 'LOOP',
    label: 'Loop',
    category: 'Logic',
    description: 'Iterasi koleksi.',
    color: 'text-violet-400',
    bg: 'bg-violet-400/10',
    ports: ['out'],
  },
  PARALLEL: {
    type: 'PARALLEL',
    label: 'Parallel',
    category: 'Logic',
    description: 'Jalankan beberapa cabang lalu gabungkan.',
    color: 'text-violet-400',
    bg: 'bg-violet-400/10',
    ports: ['branch1', 'branch2'],
  },
  TRY_CATCH: {
    type: 'TRY_CATCH',
    label: 'Try / Catch',
    category: 'Logic',
    description: 'Tangkap kegagalan sub-flow.',
    color: 'text-orange-400',
    bg: 'bg-orange-400/10',
    ports: ['out', 'error'],
  },
  HTTP_CALL: {
    type: 'HTTP_CALL',
    label: 'HTTP Call',
    category: 'Integrasi',
    description: 'Panggil API eksternal; mendukung mock.',
    color: 'text-cyan-400',
    bg: 'bg-cyan-400/10',
    ports: ['out'],
  },
  DATABASE_QUERY: {
    type: 'DATABASE_QUERY',
    label: 'SQL Query',
    category: 'Data',
    description: 'Query SELECT read-only ke sandbox database.',
    color: 'text-purple-400',
    bg: 'bg-purple-400/10',
    ports: ['out'],
  },
  TEMPLATE_CALL: {
    type: 'TEMPLATE_CALL',
    label: 'Function Template',
    category: 'Logic',
    description: 'Panggil template yang dapat dipakai ulang.',
    color: 'text-teal-400',
    bg: 'bg-teal-400/10',
    ports: ['out'],
  },
  JAVA_CODE: {
    type: 'JAVA_CODE',
    label: 'Java Code',
    category: 'Logic',
    description: 'Logika Java pengguna, dikompilasi di Control Plane.',
    color: 'text-blue-400',
    bg: 'bg-blue-400/10',
    ports: ['out'],
  },
  RESPONSE: {
    type: 'RESPONSE',
    label: 'Response',
    category: 'Output',
    description: 'Menyusun response akhir flow.',
    color: 'text-rose-400',
    bg: 'bg-rose-400/10',
    ports: ['out'],
    terminal: true,
  },
  LOG: {
    type: 'LOG',
    label: 'Log',
    category: 'Utilities',
    description: 'Catat pesan ke trace.',
    color: 'text-slate-400',
    bg: 'bg-slate-400/10',
    ports: ['out'],
  },
  DELAY: {
    type: 'DELAY',
    label: 'Delay',
    category: 'Utilities',
    description: 'Tunda eksekusi (uji timeout).',
    color: 'text-slate-400',
    bg: 'bg-slate-400/10',
    ports: ['out'],
  },
  FAIL: {
    type: 'FAIL',
    label: 'Fail',
    category: 'Utilities',
    description: 'Gagalkan flow secara sengaja.',
    color: 'text-slate-400',
    bg: 'bg-slate-400/10',
    ports: ['out'],
  },
};

/** Kelompokkan metadata node per kategori untuk palette. */
export function nodeCategories(): { category: string; nodes: NodeMeta[] }[] {
  const groups = new Map<string, NodeMeta[]>();
  Object.values(NODE_META).forEach((meta) => {
    const list = groups.get(meta.category) ?? [];
    list.push(meta);
    groups.set(meta.category, list);
  });
  return Array.from(groups.entries()).map(([category, nodes]) => ({ category, nodes }));
}

/** Port default node. */
export function portsOf(node: FlowNode): string[] {
  return NODE_META[node.type]?.ports ?? ['out'];
}

/** Warna garis edge berdasarkan port asal. */
export function portColor(port: string): string {
  switch (port) {
    case 'true':
      return '#34d399';
    case 'false':
      return '#fb7185';
    case 'error':
      return '#f97316';
    case 'default':
      return '#94a3b8';
    default:
      return '#64748b';
  }
}

/** Label port yang ramah. */
export function portLabel(port: string): string {
  switch (port) {
    case 'out':
      return 'keluar';
    case 'true':
      return 'ya';
    case 'false':
      return 'tidak';
    case 'error':
      return 'error';
    default:
      return port;
  }
}

/** ID unik sederhana untuk node/edge baru. */
export function newId(prefix = 'id'): string {
  return `${prefix}_${Math.random().toString(36).slice(2, 9)}`;
}

/** Konfigurasi awal per jenis node (agar node baru langsung berguna). */
export function defaultConfig(type: NodeType): Record<string, unknown> {
  switch (type) {
    case 'TRIGGER':
      return { description: 'Titik masuk request klien' };
    case 'SET_VARIABLE':
      return { assignments: { contoh: '=request.body.id' } };
    case 'TRANSFORM':
      return { assignments: { hasil: '=request.body' } };
    case 'CONDITION':
      return { expression: '=number(request.body.amount) > 0' };
    case 'SWITCH':
      return { expression: '=request.body.type', cases: ['transfer'] };
    case 'LOOP':
      return { items: '=request.body.items', itemName: 'item' };
    case 'PARALLEL':
      return { timeoutMs: 30000 };
    case 'TRY_CATCH':
      return {};
    case 'HTTP_CALL':
      return {
        url: 'https://api.contoh.com/endpoint',
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: { id: '=request.body.id' },
        timeoutMs: 10000,
      };
    case 'DATABASE_QUERY':
      return { sql: 'select * from contoh where id = ?', params: ['=request.body.id'] };
    case 'TEMPLATE_CALL':
      return { templateKey: 'snapbi.timestamp', version: 1, args: {} };
    case 'JAVA_CODE':
      return {
        source:
          '// Variabel flow tersedia lewat ctx\nString id = ctx.input("id") == null ? "-" : ctx.input("id").toString();\nctx.set("id", id);\nreturn java.util.Map.of("id", id);',
        libraries: [],
      };
    case 'RESPONSE':
      return {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
        body: { responseCode: '2007300', responseMessage: 'Successful' },
      };
    case 'LOG':
      return { message: 'Memproses ${request.body.id}', level: 'info' };
    case 'DELAY':
      return { ms: 200 };
    case 'FAIL':
      return { message: 'Kegagalan yang disengaja' };
    default:
      return {};
  }
}

/** Label awal node baru. */
export function defaultLabel(type: NodeType): string {
  return NODE_META[type]?.label ?? type;
}

/** Sumber Java awal untuk node Java Code. */
export function defaultJavaSource(): string {
  return String(defaultConfig('JAVA_CODE').source ?? '');
}

/**
 * Deduplikasi edge: satu port hanya boleh punya satu tujuan.
 *
 * <p>Ini menyelaraskan kanvas dengan perilaku engine yang memilih edge pertama
 * pada port yang sama, sehingga apa yang terlihat = apa yang dieksekusi.
 */
export function normalizeEdges(edges: FlowEdge[]): FlowEdge[] {
  const seen = new Set<string>();
  const result: FlowEdge[] = [];
  for (const edge of edges) {
    const key = `${edge.from}::${edge.fromPort}`;
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    result.push(edge);
  }
  return result;
}

/** Cari edge keluar dari node pada port tertentu. */
export function findEdge(edges: FlowEdge[], from: string, port: string): FlowEdge | undefined {
  return edges.find((edge) => edge.from === from && edge.fromPort === port);
}

/** True bila flow memiliki tepat satu TRIGGER dan minimal satu RESPONSE. */
export function validateStructure(nodes: FlowNode[]): {
  triggers: number;
  responses: number;
  ok: boolean;
} {
  const triggers = nodes.filter((node) => node.type === 'TRIGGER').length;
  const responses = nodes.filter((node) => node.type === 'RESPONSE').length;
  return { triggers, responses, ok: triggers === 1 && responses >= 1 };
}
