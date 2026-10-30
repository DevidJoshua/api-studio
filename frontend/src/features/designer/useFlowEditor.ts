import { useCallback, useMemo, useRef, useState } from 'react';
import type { FlowDefinition, FlowEdge, FlowNode, NodeType, Position } from '../../api/types';
import { defaultConfig, defaultLabel, newId, normalizeEdges, portsOf } from './nodeCatalog';

/** Batas riwayat undo/redo. */
const MAX_HISTORY = 50;

export interface FlowEditor {
  flow: FlowDefinition;
  setFlow: (flow: FlowDefinition) => void;
  /** Tambah node baru pada posisi tertentu. */
  addNode: (type: NodeType, position: Position) => FlowNode;
  /** Hapus node beserta edge yang menyentuhnya. */
  removeNode: (nodeId: string) => void;
  /** Geser node; `commit` menyimpan riwayat. */
  moveNode: (nodeId: string, position: Position, commit?: boolean) => void;
  /** Ubah label node. */
  setLabel: (nodeId: string, label: string) => void;
  /** Ubah seluruh konfigurasi node. */
  setConfig: (nodeId: string, config: Record<string, unknown>) => void;
  /** Ubah satu field konfigurasi. */
  patchConfig: (nodeId: string, patch: Record<string, unknown>) => void;
  /** Aktif/nonaktifkan node. */
  setDisabled: (nodeId: string, disabled: boolean) => void;
  /** Ubah kebijakan error node. */
  setErrorPolicy: (nodeId: string, policy: FlowNode['onError']) => void;
  /** Hubungkan dua node pada port tertentu. */
  connect: (from: string, to: string, port: string) => void;
  /** Hapus edge berdasarkan id. */
  disconnect: (edgeId: string) => void;
  /** Ganti node dan seluruh edge-nya (mis. setelah impor). */
  reset: (flow: FlowDefinition) => void;
  undo: () => void;
  redo: () => void;
  canUndo: boolean;
  canRedo: boolean;
  dirty: boolean;
  /** Tandai tersimpan (dipanggil setelah simpan berhasil). */
  markSaved: () => void;
}

/**
 * State editor flow dengan riwayat undo/redo.
 *
 * Semua mutasi melewati `commit`, sehingga undo/redo konsisten dan riwayat
 * tidak dipenuhi perubahan kecil (mis. setiap piksel saat drag).
 */
export function useFlowEditor(initial: FlowDefinition): FlowEditor {
  const [flow, setFlowState] = useState<FlowDefinition>(() => ({
    ...initial,
    edges: normalizeEdges(initial.edges ?? []),
  }));
  const [dirty, setDirty] = useState(false);

  const history = useRef<FlowDefinition[]>([
    { ...initial, edges: normalizeEdges(initial.edges ?? []) },
  ]);
  const index = useRef(0);
  const [version, setVersion] = useState(0);

  /** Terapkan perubahan dan dorong ke riwayat. */
  const commit = useCallback((next: FlowDefinition) => {
    const normalized: FlowDefinition = { ...next, edges: normalizeEdges(next.edges) };
    setFlowState(normalized);
    setDirty(true);

    // buang cabang redo
    history.current = history.current.slice(0, index.current + 1);
    history.current.push(normalized);
    if (history.current.length > MAX_HISTORY) {
      history.current.shift();
    }
    index.current = history.current.length - 1;
    setVersion((value) => value + 1);
  }, []);

  /** Perubahan tanpa riwayat (drag berjalan). */
  const applyTransient = useCallback((next: FlowDefinition) => {
    setFlowState(next);
    setDirty(true);
  }, []);

  const addNode = useCallback(
    (type: NodeType, position: Position): FlowNode => {
      const node: FlowNode = {
        id: newId(type.toLowerCase()),
        type,
        label: defaultLabel(type),
        config: defaultConfig(type),
        position,
        onError: { onError: 'FAIL', retries: 0, backoffMs: 0 },
        disabled: false,
      };
      commit({ ...flow, nodes: [...flow.nodes, node] });
      return node;
    },
    [flow, commit],
  );

  const removeNode = useCallback(
    (nodeId: string) => {
      commit({
        ...flow,
        nodes: flow.nodes.filter((node) => node.id !== nodeId),
        edges: flow.edges.filter((edge) => edge.from !== nodeId && edge.to !== nodeId),
      });
    },
    [flow, commit],
  );

  const moveNode = useCallback(
    (nodeId: string, position: Position, withHistory = false) => {
      const next: FlowDefinition = {
        ...flow,
        nodes: flow.nodes.map((node) =>
          node.id === nodeId ? { ...node, position } : node,
        ),
      };
      if (withHistory) {
        commit(next);
      } else {
        applyTransient(next);
      }
    },
    [flow, commit, applyTransient],
  );

  const updateNode = useCallback(
    (nodeId: string, patch: Partial<FlowNode>) => {
      commit({
        ...flow,
        nodes: flow.nodes.map((node) => (node.id === nodeId ? { ...node, ...patch } : node)),
      });
    },
    [flow, commit],
  );

  const setLabel = useCallback(
    (nodeId: string, label: string) => updateNode(nodeId, { label }),
    [updateNode],
  );

  const setConfig = useCallback(
    (nodeId: string, config: Record<string, unknown>) => updateNode(nodeId, { config }),
    [updateNode],
  );

  const patchConfig = useCallback(
    (nodeId: string, patch: Record<string, unknown>) => {
      const node = flow.nodes.find((candidate) => candidate.id === nodeId);
      if (!node) {
        return;
      }
      updateNode(nodeId, { config: { ...node.config, ...patch } });
    },
    [flow.nodes, updateNode],
  );

  const setDisabled = useCallback(
    (nodeId: string, disabled: boolean) => updateNode(nodeId, { disabled }),
    [updateNode],
  );

  const setErrorPolicy = useCallback(
    (nodeId: string, policy: FlowNode['onError']) => updateNode(nodeId, { onError: policy }),
    [updateNode],
  );

  const connect = useCallback(
    (from: string, to: string, port: string) => {
      if (from === to) {
        return;
      }
      const node = flow.nodes.find((candidate) => candidate.id === from);
      if (!node) {
        return;
      }
      const validPort = portsOf(node).includes(port) ? port : portsOf(node)[0];
      // satu port satu tujuan: ganti edge lama pada port yang sama
      const filtered = flow.edges.filter(
        (edge) => !(edge.from === from && edge.fromPort === validPort),
      );
      const edge: FlowEdge = {
        id: newId('edge'),
        from,
        to,
        fromPort: validPort,
        label: null,
      };
      commit({ ...flow, edges: [...filtered, edge] });
    },
    [flow, commit],
  );

  const disconnect = useCallback(
    (edgeId: string) => {
      commit({ ...flow, edges: flow.edges.filter((edge) => edge.id !== edgeId) });
    },
    [flow, commit],
  );

  const reset = useCallback((next: FlowDefinition) => {
    const normalized: FlowDefinition = { ...next, edges: normalizeEdges(next.edges ?? []) };
    setFlowState(normalized);
    setDirty(false);
    history.current = [normalized];
    index.current = 0;
    setVersion((value) => value + 1);
  }, []);

  const undo = useCallback(() => {
    if (index.current <= 0) {
      return;
    }
    index.current -= 1;
    setFlowState(history.current[index.current]);
    setDirty(true);
    setVersion((value) => value + 1);
  }, []);

  const redo = useCallback(() => {
    if (index.current >= history.current.length - 1) {
      return;
    }
    index.current += 1;
    setFlowState(history.current[index.current]);
    setDirty(true);
    setVersion((value) => value + 1);
  }, []);

  const markSaved = useCallback(() => setDirty(false), []);

  return useMemo(
    () => ({
      flow,
      setFlow: setFlowState,
      addNode,
      removeNode,
      moveNode,
      setLabel,
      setConfig,
      patchConfig,
      setDisabled,
      setErrorPolicy,
      connect,
      disconnect,
      reset,
      undo,
      redo,
      canUndo: index.current > 0,
      canRedo: index.current < history.current.length - 1,
      dirty,
      markSaved,
    }),
    // `version` memaksa objek baru saat riwayat berubah sehingga canUndo/canRedo segar
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [
      flow,
      version,
      dirty,
      addNode,
      removeNode,
      moveNode,
      setLabel,
      setConfig,
      patchConfig,
      setDisabled,
      setErrorPolicy,
      connect,
      disconnect,
      reset,
      undo,
      redo,
      markSaved,
    ],
  );
}
