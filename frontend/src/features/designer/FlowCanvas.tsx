import { useCallback, useEffect, useRef, useState } from 'react';
import type { FlowNode, NodeType, TraceStep } from '../../api/types';
import { NODE_META, portColor, portLabel, portsOf } from './nodeCatalog';
import type { FlowEditor } from './useFlowEditor';

const NODE_WIDTH = 232;
const NODE_HEIGHT = 62;

interface CanvasProps {
  editor: FlowEditor;
  selectedNodeId: string | null;
  onSelectNode: (nodeId: string | null) => void;
  /** Trace untuk mewarnai node setelah simulasi. */
  traceByNode: Record<string, TraceStep>;
  /** Node yang disorot dari panel trace. */
  highlightNodeId?: string | null;
}

/**
 * Kanvas flow interaktif.
 *
 * Fitur:
 *  - Drag node (mouse) dengan commit riwayat sekali di akhir drag.
 *  - Tarik dari port keluar ke node lain untuk membuat edge.
 *  - Drop node dari palette (HTML5 drag and drop).
 *  - Klik edge untuk menghapus; pan kanvas dengan drag latar; zoom.
 *
 * Implementasi memakai div absolut + SVG, tanpa library graf.
 */
export function FlowCanvas({
  editor,
  selectedNodeId,
  onSelectNode,
  traceByNode,
  highlightNodeId,
}: CanvasProps) {
  const { flow, moveNode, connect, disconnect, addNode } = editor;
  const containerRef = useRef<HTMLDivElement>(null);
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const [dragging, setDragging] = useState<{
    nodeId: string;
    offsetX: number;
    offsetY: number;
  } | null>(null);
  const [linking, setLinking] = useState<{ from: string; port: string; x: number; y: number } | null>(
    null,
  );
  const [panning, setPanning] = useState<{
    startX: number;
    startY: number;
    originX: number;
    originY: number;
  } | null>(null);

  const nodeById = useCallback(
    (nodeId: string) => flow.nodes.find((node) => node.id === nodeId),
    [flow.nodes],
  );

  /** Titik keluar node (sisi kanan) untuk port tertentu. */
  const portPoint = useCallback(
    (nodeId: string, port: string) => {
      const node = nodeById(nodeId);
      if (!node) {
        return { x: 0, y: 0 };
      }
      const ports = portsOf(node);
      const index = Math.max(0, ports.indexOf(port));
      const step = NODE_HEIGHT / (ports.length + 1);
      return {
        x: node.position.x + NODE_WIDTH,
        y: node.position.y + step * (index + 1),
      };
    },
    [nodeById],
  );

  /** Titik masuk node (sisi kiri). */
  const inputPoint = useCallback(
    (nodeId: string) => {
      const node = nodeById(nodeId);
      if (!node) {
        return { x: 0, y: 0 };
      }
      return { x: node.position.x, y: node.position.y + NODE_HEIGHT / 2 };
    },
    [nodeById],
  );

  /** Konversi koordinat layar ke koordinat kanvas. */
  const toCanvasPoint = useCallback(
    (clientX: number, clientY: number) => {
      const rect = containerRef.current?.getBoundingClientRect();
      if (!rect) {
        return { x: 0, y: 0 };
      }
      return {
        x: (clientX - rect.left - pan.x) / zoom,
        y: (clientY - rect.top - pan.y) / zoom,
      };
    },
    [pan, zoom],
  );

  // drag node
  useEffect(() => {
    if (!dragging) {
      return;
    }
    const handleMove = (event: MouseEvent) => {
      const point = toCanvasPoint(event.clientX, event.clientY);
      moveNode(
        dragging.nodeId,
        {
          x: Math.max(0, point.x - dragging.offsetX),
          y: Math.max(0, point.y - dragging.offsetY),
        },
        false,
      );
    };
    const handleUp = () => {
      const node = nodeById(dragging.nodeId);
      if (node) {
        // commit riwayat sekali di akhir drag
        moveNode(dragging.nodeId, node.position, true);
      }
      setDragging(null);
    };
    window.addEventListener('mousemove', handleMove);
    window.addEventListener('mouseup', handleUp);
    return () => {
      window.removeEventListener('mousemove', handleMove);
      window.removeEventListener('mouseup', handleUp);
    };
  }, [dragging, moveNode, nodeById, toCanvasPoint]);

  // pan kanvas
  useEffect(() => {
    if (!panning) {
      return;
    }
    const handleMove = (event: MouseEvent) => {
      setPan({
        x: panning.originX + (event.clientX - panning.startX),
        y: panning.originY + (event.clientY - panning.startY),
      });
    };
    const handleUp = () => setPanning(null);
    window.addEventListener('mousemove', handleMove);
    window.addEventListener('mouseup', handleUp);
    return () => {
      window.removeEventListener('mousemove', handleMove);
      window.removeEventListener('mouseup', handleUp);
    };
  }, [panning]);

  // tarik koneksi dari port
  useEffect(() => {
    if (!linking) {
      return;
    }
    const handleMove = (event: MouseEvent) => {
      const point = toCanvasPoint(event.clientX, event.clientY);
      setLinking({ ...linking, x: point.x, y: point.y });
    };
    const handleUp = (event: MouseEvent) => {
      const target = (event.target as HTMLElement | null)?.closest('[data-node-id]');
      const targetId = target?.getAttribute('data-node-id');
      if (targetId && targetId !== linking.from) {
        connect(linking.from, targetId, linking.port);
      }
      setLinking(null);
    };
    window.addEventListener('mousemove', handleMove);
    window.addEventListener('mouseup', handleUp);
    return () => {
      window.removeEventListener('mousemove', handleMove);
      window.removeEventListener('mouseup', handleUp);
    };
  }, [linking, connect, toCanvasPoint]);

  /** Drop node baru dari palette. */
  const handleDrop = (event: React.DragEvent) => {
    event.preventDefault();
    const type = event.dataTransfer.getData('application/x-node-type') as NodeType | '';
    if (!type) {
      return;
    }
    const point = toCanvasPoint(event.clientX, event.clientY);
    const node = addNode(type, {
      x: Math.max(0, point.x - NODE_WIDTH / 2),
      y: Math.max(0, point.y - NODE_HEIGHT / 2),
    });
    onSelectNode(node.id);
  };

  /** Klassifikasi warna dari trace. */
  const traceStatus = (nodeId: string) => {
    const step = traceByNode[nodeId];
    if (!step) {
      return null;
    }
    if (step.status === 'ERROR') {
      return 'error';
    }
    if (step.status === 'DISABLED' || step.status === 'SKIPPED') {
      return 'muted';
    }
    return 'ok';
  };

  return (
    <div className="relative h-full w-full overflow-hidden">
      <div
        ref={containerRef}
        className="canvas-grid h-full w-full cursor-grab active:cursor-grabbing"
        onDragOver={(event) => event.preventDefault()}
        onDrop={handleDrop}
        onMouseDown={(event) => {
          if (event.target === containerRef.current) {
            onSelectNode(null);
            setPanning({
              startX: event.clientX,
              startY: event.clientY,
              originX: pan.x,
              originY: pan.y,
            });
          }
        }}
      >
        <div
          className="relative origin-top-left"
          style={{ transform: `translate(${pan.x}px, ${pan.y}px) scale(${zoom})` }}
        >
          {/* Edge */}
          <svg
            className="pointer-events-none absolute left-0 top-0 overflow-visible"
            width={5000}
            height={4000}
            aria-hidden="true"
          >
            {flow.edges.map((edge) => {
              const source = nodeById(edge.from);
              if (!source) {
                return null;
              }
              const start = portPoint(edge.from, edge.fromPort);
              const end = inputPoint(edge.to);
              const midX = (start.x + end.x) / 2;
              const path = `M ${start.x} ${start.y} C ${midX} ${start.y}, ${midX} ${end.y}, ${end.x} ${end.y}`;
              const color = portColor(edge.fromPort);
              return (
                <g key={edge.id ?? `${edge.from}-${edge.to}`}>
                  <path d={path} stroke={color} strokeWidth={2} fill="none" opacity={0.65} />
                  <circle cx={end.x} cy={end.y} r={3.5} fill={color} />
                  {edge.id && (
                    <path
                      d={path}
                      stroke="transparent"
                      strokeWidth={14}
                      fill="none"
                      className="pointer-events-auto cursor-pointer"
                      onClick={() => disconnect(edge.id!)}
                    >
                      <title>Klik untuk menghapus koneksi</title>
                    </path>
                  )}
                </g>
              );
            })}
            {linking && (
              <line
                x1={portPoint(linking.from, linking.port).x}
                y1={portPoint(linking.from, linking.port).y}
                x2={linking.x}
                y2={linking.y}
                stroke={portColor(linking.port)}
                strokeWidth={2}
                strokeDasharray="5 4"
              />
            )}
          </svg>

          {/* Node */}
          {flow.nodes.map((node) => (
            <NodeCard
              key={node.id}
              node={node}
              selected={selectedNodeId === node.id}
              highlighted={highlightNodeId === node.id}
              traceStatus={traceStatus(node.id)}
              traceStep={traceByNode[node.id]}
              onMouseDown={(event) => {
                event.stopPropagation();
                onSelectNode(node.id);
                const point = toCanvasPoint(event.clientX, event.clientY);
                setDragging({
                  nodeId: node.id,
                  offsetX: point.x - node.position.x,
                  offsetY: point.y - node.position.y,
                });
              }}
              onStartLink={(port, clientX, clientY) => {
                const start = portPoint(node.id, port);
                setLinking({ from: node.id, port, x: start.x, y: start.y });
                void clientX;
                void clientY;
              }}
            />
          ))}
        </div>
      </div>

      {/* Kontrol zoom */}
      <div className="absolute bottom-3 right-3 flex items-center gap-1 rounded-lg border border-slate-700 bg-slate-900/95 px-1.5 py-1 shadow-xl">
        <button
          type="button"
          onClick={() => setZoom((value) => Math.max(0.4, value - 0.1))}
          className="rounded px-1.5 py-0.5 text-xs text-slate-400 hover:bg-slate-800 hover:text-slate-100"
          aria-label="Perkecil"
        >
          −
        </button>
        <span className="w-10 text-center text-[11px] tabular-nums text-slate-400">
          {Math.round(zoom * 100)}%
        </span>
        <button
          type="button"
          onClick={() => setZoom((value) => Math.min(1.8, value + 0.1))}
          className="rounded px-1.5 py-0.5 text-xs text-slate-400 hover:bg-slate-800 hover:text-slate-100"
          aria-label="Perbesar"
        >
          +
        </button>
        <button
          type="button"
          onClick={() => {
            setZoom(1);
            setPan({ x: 0, y: 0 });
          }}
          className="rounded px-1.5 py-0.5 text-[11px] text-slate-400 hover:bg-slate-800 hover:text-slate-100"
        >
          Reset
        </button>
      </div>

      {flow.nodes.length === 0 && (
        <div className="pointer-events-none absolute inset-0 grid place-items-center">
          <p className="rounded-lg border border-slate-700 bg-slate-900/90 px-4 py-2 text-xs text-slate-400 shadow-xl">
            Tarik node dari palette kiri, atau klik node untuk menambahkannya
          </p>
        </div>
      )}
    </div>
  );
}

/** Kartu satu node pada kanvas. */
function NodeCard({
  node,
  selected,
  highlighted,
  traceStatus,
  traceStep,
  onMouseDown,
  onStartLink,
}: {
  node: FlowNode;
  selected: boolean;
  highlighted: boolean;
  traceStatus: 'ok' | 'error' | 'muted' | null;
  traceStep?: TraceStep;
  onMouseDown: (event: React.MouseEvent) => void;
  onStartLink: (port: string, clientX: number, clientY: number) => void;
}) {
  const meta = NODE_META[node.type];
  const ports = portsOf(node);
  const summary = nodeSummary(node);

  const borderColor =
    traceStatus === 'error'
      ? 'border-rose-500'
      : traceStatus === 'ok'
        ? 'border-emerald-500/70'
        : selected
          ? 'border-indigo-500'
          : 'border-slate-700';

  return (
    <div
      data-node-id={node.id}
      onMouseDown={onMouseDown}
      className={`absolute flex select-none flex-col rounded-lg border bg-slate-800/95 shadow-xl backdrop-blur transition-colors ${borderColor} ${
        selected ? 'ring-2 ring-indigo-500/30' : ''
      } ${highlighted ? 'ring-2 ring-cyan-400/60' : ''} ${node.disabled ? 'opacity-50' : ''}`}
      style={{
        left: node.position.x,
        top: node.position.y,
        width: NODE_WIDTH,
        height: NODE_HEIGHT,
      }}
    >
      {/* Port masuk */}
      {node.type !== 'TRIGGER' && (
        <span
          className="absolute -left-1.5 top-1/2 h-3 w-3 -translate-y-1/2 rounded-full border-2 border-slate-900 bg-slate-500"
          aria-hidden="true"
        />
      )}

      <div className="flex h-full items-center gap-2 px-2.5">
        <span
          className={`grid h-7 w-7 shrink-0 place-items-center rounded-md ${meta?.bg ?? 'bg-slate-700/40'} ${
            meta?.color ?? 'text-slate-400'
          }`}
        >
          <span className="h-2.5 w-2.5 rounded-sm bg-current" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="truncate text-[11px] font-semibold leading-tight text-slate-100">
            {node.label}
          </p>
          <p className="truncate text-[10px] uppercase tracking-wide text-slate-500">
            {meta?.label ?? node.type}
          </p>
        </div>
        {traceStep && (
          <span className="shrink-0 text-[10px] tabular-nums text-slate-500">
            {Math.round(traceStep.durationMs)}ms
          </span>
        )}
      </div>

      {summary && (
        <p className="truncate border-t border-slate-700/60 px-2.5 py-0.5 font-mono text-[10px] text-slate-500">
          {summary}
        </p>
      )}

      {/* Port keluar */}
      {ports.map((port) => {
        const step = NODE_HEIGHT / (ports.length + 1);
        const index = ports.indexOf(port);
        return (
          <button
            key={port}
            type="button"
            title={`Tarik untuk menghubungkan (${portLabel(port)})`}
            aria-label={`Port keluar ${portLabel(port)}`}
            className="absolute -right-2 h-4 w-4 rounded-full border-2 border-slate-900 transition hover:scale-125"
            style={{
              top: step * (index + 1) - 8,
              backgroundColor: portColor(port),
            }}
            onMouseDown={(event) => {
              event.stopPropagation();
              onStartLink(port, event.clientX, event.clientY);
            }}
          />
        );
      })}
    </div>
  );
}

/** Ringkasan singkat isi node untuk ditampilkan di kartu. */
function nodeSummary(node: FlowNode): string {
  switch (node.type) {
    case 'CONDITION':
    case 'SWITCH':
      return String(node.config.expression ?? '');
    case 'DATABASE_QUERY':
      return String(node.config.sql ?? '');
    case 'HTTP_CALL':
      return `${node.config.method ?? 'GET'} ${node.config.url ?? ''}`;
    case 'TEMPLATE_CALL':
      return `${node.config.templateKey ?? ''}@${node.config.version ?? 1}`;
    case 'RESPONSE':
      return `status ${node.config.status ?? 200}`;
    case 'LOG':
      return String(node.config.message ?? '');
    case 'DELAY':
      return `${node.config.ms ?? 0} ms`;
    case 'JAVA_CODE':
      return 'kode Java';
    default:
      return '';
  }
}
