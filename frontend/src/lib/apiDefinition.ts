import type { ApiDetail, ApiVersion, FlowDefinition, FlowEdge, FlowNode, NodeType } from '../api/types';
import { NODE_META, portsOf } from '../features/designer/nodeCatalog';

/**
 * Konversi antara definisi flow internal dan dokumen deklaratif
 * {@code ApiDefinition} (JSON/YAML).
 *
 * <p>Dokumen ini adalah bentuk yang ramah review dan dokumentasi: mudah di-diff
 * di Git, dibaca manusia, dan dapat diedit lewat editor YAML dengan
 * autocomplete. Konversi balik bersifat toleran: dokumen yang tidak lengkap
 * tetap menghasilkan flow yang valid, dengan peringatan dikumpulkan pemanggil.
 */

/** Dokumen deklaratif yang dihasilkan. */
export interface ApiDefinitionDocument {
  apiVersion: string;
  kind: string;
  metadata: {
    name: string;
    method: string;
    path: string;
    version: string;
  };
  spec: {
    flow: { name: string; version: number };
    policy: Record<string, unknown>;
    nodes: Record<string, unknown>[];
    edges: Record<string, unknown>[];
  };
}

/** Bangun dokumen dari state aplikasi. */
export function toApiDefinition(
  api: Pick<ApiDetail, 'name' | 'method' | 'path' | 'policy'> | null,
  version: Pick<ApiVersion, 'label'> | null,
  flow: FlowDefinition,
): ApiDefinitionDocument {
  return {
    apiVersion: 'apistudio/v1',
    kind: 'ApiDefinition',
    metadata: {
      name: api?.name ?? flow.name ?? 'Flow',
      method: api?.method ?? 'POST',
      path: api?.path ?? '/',
      version: version?.label ?? 'v1',
    },
    spec: {
      flow: { name: flow.name, version: flow.version ?? 1 },
      policy: (api?.policy as Record<string, unknown>) ?? {},
      nodes: flow.nodes.map((node) => ({
        id: node.id,
        type: node.type,
        label: node.label,
        ...(node.disabled ? { disabled: true } : {}),
        position: node.position,
        ...(node.onError ? { errorPolicy: node.onError } : {}),
        config: node.config,
      })),
      edges: flow.edges.map((edge) => ({
        from: edge.from,
        port: edge.fromPort,
        to: edge.to,
        ...(edge.label ? { label: edge.label } : {}),
      })),
    },
  };
}

/** Hasil konversi balik beserta catatan masalah yang ditemukan. */
export interface ParseResult {
  flow: FlowDefinition;
  warnings: string[];
}

const VALID_TYPES = new Set(Object.keys(NODE_META) as NodeType[]);
const VALID_PORTS = new Set(['out', 'true', 'false', 'error', 'default']);

/** Konversi dokumen deklaratif kembali menjadi {@link FlowDefinition}. */
export function fromApiDefinition(document: unknown): ParseResult {
  const warnings: string[] = [];
  const doc = (document ?? {}) as Record<string, unknown>;

  if (doc.kind && doc.kind !== 'ApiDefinition') {
    warnings.push(`kind tidak dikenal: ${String(doc.kind)} (diharapkan ApiDefinition)`);
  }

  const spec = (doc.spec ?? {}) as Record<string, unknown>;
  const metadata = (doc.metadata ?? {}) as Record<string, unknown>;
  const flowPart = (spec.flow ?? {}) as Record<string, unknown>;

  const rawNodes = Array.isArray(spec.nodes) ? spec.nodes : [];
  const rawEdges = Array.isArray(spec.edges) ? spec.edges : [];

  const nodes: FlowNode[] = [];
  rawNodes.forEach((raw, index) => {
    const item = (raw ?? {}) as Record<string, unknown>;
    const type = String(item.type ?? '') as NodeType;
    if (!VALID_TYPES.has(type)) {
      warnings.push(`node[${index}]: jenis "${String(item.type)}" tidak dikenal, dilewati`);
      return;
    }
    const position = (item.position ?? {}) as Record<string, unknown>;
    nodes.push({
      id: String(item.id ?? `node_${index + 1}`),
      type,
      label: String(item.label ?? type),
      config: (item.config as Record<string, unknown>) ?? {},
      position: {
        x: typeof position.x === 'number' ? position.x : 0,
        y: typeof position.y === 'number' ? position.y : 0,
      },
      onError: (item.errorPolicy as FlowNode['onError']) ?? null,
      disabled: item.disabled === true,
    });
  });

  const nodeIds = new Set(nodes.map((node) => node.id));
  const edges: FlowEdge[] = [];
  rawEdges.forEach((raw, index) => {
    const item = (raw ?? {}) as Record<string, unknown>;
    const from = String(item.from ?? '');
    const to = String(item.to ?? '');
    const port = String(item.port ?? 'out');
    if (!nodeIds.has(from) || !nodeIds.has(to)) {
      warnings.push(`edge[${index}]: ${from} -> ${to} menunjuk node yang tidak ada, dilewati`);
      return;
    }
    if (!VALID_PORTS.has(port)) {
      warnings.push(`edge[${index}]: port "${port}" tidak dikenal, memakai "out"`);
    }
    edges.push({
      id: `edge_${index + 1}`,
      from,
      to,
      fromPort: VALID_PORTS.has(port) ? port : 'out',
      label: item.label ? String(item.label) : null,
    });
  });

  // peringatkan port yang tidak cocok dengan jenis node sumber
  edges.forEach((edge) => {
    const source = nodes.find((node) => node.id === edge.from);
    if (source && !portsOf(source).includes(edge.fromPort)) {
      warnings.push(
        `edge ${edge.from} -> ${edge.to}: port "${edge.fromPort}" tidak dimiliki ${source.type}`,
      );
    }
  });

  return {
    flow: {
      name: String(flowPart.name ?? metadata.name ?? 'Flow'),
      version: typeof flowPart.version === 'number' ? flowPart.version : 1,
      nodes,
      edges,
    },
    warnings,
  };
}
