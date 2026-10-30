import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  apiApi,
  releaseApi,
  templateApi,
  testApi,
  versionApi,
} from '../api/endpoints';
import type {
  ApiDetail,
  ApiVersionDetail,
  CompileNodeResult,
  FunctionTemplateInfo,
  PreflightCheck,
  SimulationResult,
  ValidationReport,
} from '../api/types';
import { useAsync } from '../app/hooks';
import { IconCheck, IconCode, IconPlay, IconPlus, IconSave, IconX } from '../app/icons';
import {
  API_DEFINITION_SCHEMA,
  Alert,
  Badge,
  Button,
  Checkbox,
  CodeEditor,
  EmptyState,
  Field,
  HintBox,
  Modal,
  Panel,
  SegmentedControl,
  Select,
  Skeleton,
  Stack,
  Tabs,
  TextInput,
  YamlEditor,
  jsonLanguage,
} from '../ui';
import { FlowCanvas } from '../features/designer/FlowCanvas';
import { NodePalette } from '../features/designer/NodePalette';
import { NodeConfigPanel } from '../features/designer/NodeConfigPanel';
import { SimulationPanel, type SimulationPayload } from '../features/designer/SimulationPanel';
import { useFlowEditor } from '../features/designer/useFlowEditor';
import { validateStructure } from '../features/designer/nodeCatalog';
import { errorMessage, formatDateTime, prettyJson } from '../lib/format';
import { fromApiDefinition, toApiDefinition } from '../lib/apiDefinition';
import { parseYaml, toYaml } from '../lib/yaml';
import { versionTone } from '../ui/types';

type BottomTab = 'simulation' | 'validation' | 'preflight' | 'code' | 'details';

/**
 * Workflow Canvas: editor flow visual.
 *
 * Tata letak: palette kiri, kanvas tengah, konfigurasi node kanan, dan panel
 * bawah untuk simulasi/validasi/preflight/preview kode.
 */
export function WorkflowCanvasPage({
  apiId,
  onNotify,
  onSaved,
}: {
  apiId: string | null;
  onNotify: (message: string, tone: 'success' | 'error' | 'info') => void;
  onSaved: () => void;
}) {
  const [versionId, setVersionId] = useState<string | null>(null);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [bottomTab, setBottomTab] = useState<BottomTab>('simulation');
  const [validation, setValidation] = useState<ValidationReport | null>(null);
  const [preflight, setPreflight] = useState<PreflightCheck | null>(null);
  const [simulation, setSimulation] = useState<SimulationResult | null>(null);
  const [simulationError, setSimulationError] = useState<string | null>(null);
  const [compilingNode, setCompilingNode] = useState<string | null>(null);
  const [compileResults, setCompileResults] = useState<Record<string, CompileNodeResult>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [environment, setEnvironment] = useState('dev');
  const [saveTestOpen, setSaveTestOpen] = useState(false);
  const [configDrawerOpen, setConfigDrawerOpen] = useState(false);
  const [paletteDrawerOpen, setPaletteDrawerOpen] = useState(false);
  /** Editor deklaratif: format, teks, dan pesan kesalahan. */
  const [previewFormat, setPreviewFormat] = useState<'yaml' | 'json'>('yaml');
  const [previewText, setPreviewText] = useState('');
  const [previewError, setPreviewError] = useState<string | null>(null);
  /** Tinggi panel bawah (px) dan status layar penuh. */
  const [bottomPanelHeight, setBottomPanelHeight] = useState(288);
  const [bottomExpanded, setBottomExpanded] = useState(false);
  const bottomHeight = `${bottomPanelHeight}px`;
  /** Panel konfigurasi diperbesar ke modal penuh. */
  const [configExpanded, setConfigExpanded] = useState(false);
  const [testName, setTestName] = useState('');
  const [lastSimulationPayload, setLastSimulationPayload] = useState<SimulationPayload | null>(null);

  const loadApi = useCallback(() => (apiId ? apiApi.detail(apiId) : Promise.resolve(null)), [apiId]);
  const apiState = useAsync<ApiDetail | null>(loadApi, [apiId]);

  const loadTemplates = () => templateApi.list();
  const templatesState = useAsync<FunctionTemplateInfo[]>(loadTemplates, []);

  /** Pilih versi draft (atau terbaru) saat API berubah. */
  useEffect(() => {
    const detail = apiState.data;
    if (!detail) {
      setVersionId(null);
      return;
    }
    const draft = detail.versions.find((version) => version.status === 'DRAFT');
    const fallback =
      detail.versions.find((version) => version.id === detail.latestVersionId) ?? detail.versions[0];
    setVersionId(draft?.id ?? fallback?.id ?? null);
  }, [apiState.data]);

  const loadVersion = useCallback(
    () => (versionId ? versionApi.detail(versionId) : Promise.resolve(null)),
    [versionId],
  );
  const versionState = useAsync<ApiVersionDetail | null>(loadVersion, [versionId]);

  const editor = useFlowEditor(
    versionState.data?.flow ?? { name: 'Flow baru', nodes: [], edges: [], version: 1 },
  );
  const [editorReady, setEditorReady] = useState(false);

  useEffect(() => {
    if (versionState.data && !editorReady) {
      editor.reset(versionState.data.flow);
      setEditorReady(true);
    }
  }, [versionState.data, editor, editorReady]);

  useEffect(() => {
    setEditorReady(false);
    setSelectedNodeId(null);
    setValidation(null);
    setPreflight(null);
    setSimulation(null);
    setCompileResults({});
  }, [versionId]);

  /** Esc menutup panel atau overlay yang sedang terbuka. */
  useEffect(() => {
    const handleKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') {
        return;
      }
      // Editor kode layar penuh menangani Esc-nya sendiri; jangan ikut menutup
      // modal yang menaunginya pada penekanan tombol yang sama.
      if (document.body.dataset.editorFullscreen === '1') {
        return;
      }
      if (configExpanded) {
        setConfigExpanded(false);
      } else if (bottomExpanded) {
        setBottomExpanded(false);
      } else if (paletteDrawerOpen) {
        setPaletteDrawerOpen(false);
      } else if (configDrawerOpen) {
        setConfigDrawerOpen(false);
      }
    };
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, [configExpanded, bottomExpanded, paletteDrawerOpen, configDrawerOpen]);

  const version = versionState.data;
  const api = apiState.data;
  const isDraft = version?.status === 'DRAFT';

  const selectedNode = useMemo(
    () => editor.flow.nodes.find((node) => node.id === selectedNodeId) ?? null,
    [editor.flow.nodes, selectedNodeId],
  );

  /** Trace dikelompokkan per node untuk mewarnai kanvas. */
  const traceByNode = useMemo(() => {
    const map: Record<string, SimulationResult['trace'][number]> = {};
    simulation?.trace.forEach((step) => {
      // simpan langkah terakhir per node
      map[step.nodeId] = step;
    });
    return map;
  }, [simulation]);

  const structure = useMemo(() => validateStructure(editor.flow.nodes), [editor.flow.nodes]);

  // ------------------------------------------------------------- aksi

  const save = async () => {
    if (!version) {
      return;
    }
    setBusy('save');
    setError(null);
    try {
      await versionApi.saveFlow(version.id, editor.flow);
      // simpan juga source Java agar kompilasi memakai kode terbaru
      const javaSources: Record<string, unknown> = { ...(version.javaSources ?? {}) };
      editor.flow.nodes.forEach((node) => {
        if (node.type === 'JAVA_CODE' && typeof node.config.source === 'string') {
          javaSources[node.id] = {
            source: node.config.source,
            libraries: Array.isArray(node.config.libraries) ? node.config.libraries : [],
          };
        }
      });
      await versionApi.saveJava(version.id, javaSources);
      editor.markSaved();
      onNotify('Flow tersimpan.', 'success');
      versionState.reload();
      onSaved();
    } catch (cause) {
      setError(errorMessage(cause, 'Gagal menyimpan flow'));
    } finally {
      setBusy(null);
    }
  };

  const validate = async () => {
    if (!version) {
      return;
    }
    setBusy('validate');
    setError(null);
    try {
      const report = await versionApi.validate(version.id, editor.flow);
      setValidation(report);
      setBottomTab('validation');
      onNotify(
        report.valid ? 'Flow valid.' : `${report.errors} error validasi ditemukan.`,
        report.valid ? 'success' : 'error',
      );
    } catch (cause) {
      setError(errorMessage(cause, 'Gagal memvalidasi flow'));
    } finally {
      setBusy(null);
    }
  };

  const compileNode = async (nodeId: string, source: string, libraries: string[]) => {
    if (!version) {
      return;
    }
    setCompilingNode(nodeId);
    try {
      // simpan dulu agar backend mengompilasi kode terbaru
      const javaSources: Record<string, unknown> = { ...(version.javaSources ?? {}) };
      editor.flow.nodes.forEach((node) => {
        if (node.type === 'JAVA_CODE') {
          javaSources[node.id] = {
            source: node.id === nodeId ? source : String(node.config.source ?? ''),
            libraries:
              node.id === nodeId
                ? libraries
                : Array.isArray(node.config.libraries)
                  ? node.config.libraries
                  : [],
          };
        }
      });
      await versionApi.saveJava(version.id, javaSources);
      const report = await versionApi.compile(version.id);
      const nodes = report.results ?? [];
      const map: Record<string, CompileNodeResult> = {};
      nodes.forEach((result) => {
        map[result.nodeId] = result;
      });
      setCompileResults(map);

      const own = map[nodeId];
      if (own && own.success) {
        onNotify('Kompilasi berhasil.', 'success');
      } else {
        onNotify(`Kompilasi gagal: ${own?.problems?.[0]?.message ?? 'lihat detail'}`, 'error');
      }
    } catch (cause) {
      onNotify(errorMessage(cause, 'Kompilasi gagal'), 'error');
    } finally {
      setCompilingNode(null);
    }
  };

  const runSimulation = async (payload: SimulationPayload) => {
    if (!version) {
      return;
    }
    setBusy('simulate');
    setSimulationError(null);
    setLastSimulationPayload(payload);
    try {
      // pastikan flow tersimpan agar simulasi memakai versi terbaru
      await versionApi.saveFlow(version.id, editor.flow);
      editor.markSaved();
      const result = await testApi.simulate({
        versionId: version.id,
        method: payload.method,
        path: payload.path,
        headers: payload.headers,
        body: payload.body,
        mocks: payload.mocks,
        seed: payload.seed,
        assertions: payload.assertions,
      });
      setSimulation(result);
      if (result.statusCode >= 500) {
        onNotify(`Simulasi gagal: HTTP ${result.statusCode}`, 'error');
      }
    } catch (cause) {
      setSimulationError(errorMessage(cause, 'Simulasi gagal'));
    } finally {
      setBusy(null);
    }
  };

  const runPreflight = async () => {
    if (!version) {
      return;
    }
    setBusy('preflight');
    try {
      const result = await releaseApi.preflight(version.id, environment);
      setPreflight(result);
      setBottomTab('preflight');
      onNotify(
        result.ready ? 'Siap dipublish.' : 'Ada pemeriksaan yang belum terpenuhi.',
        result.ready ? 'success' : 'error',
      );
    } catch (cause) {
      onNotify(errorMessage(cause, 'Gagal memeriksa preflight'), 'error');
    } finally {
      setBusy(null);
    }
  };

  const saveAsTest = async () => {
    if (!version || !api || !lastSimulationPayload || !testName.trim()) {
      return;
    }
    setBusy('savetest');
    try {
      await testApi.create(api.id, {
        versionId: version.id,
        name: testName.trim(),
        method: lastSimulationPayload.method,
        path: lastSimulationPayload.path,
        headers: lastSimulationPayload.headers,
        body: lastSimulationPayload.body,
        mocks: lastSimulationPayload.mocks,
        seed: lastSimulationPayload.seed,
        assertions: lastSimulationPayload.assertions,
      });
      onNotify('Test case disimpan ke suite.', 'success');
      setSaveTestOpen(false);
      setTestName('');
    } catch (cause) {
      onNotify(errorMessage(cause, 'Gagal menyimpan test case'), 'error');
    } finally {
      setBusy(null);
    }
  };

  /**
   * Isi teks editor deklaratif mengikuti flow yang sedang diedit. Disinkronkan
   * hanya saat tab dibuka pertama kali atau saat pengguna menekan "Setel ulang",
   * agar suntingan yang belum diterapkan tidak hilang setiap render.
   */
  const syncPreviewFromFlow = useCallback(() => {
    const document = toApiDefinition(api, version, editor.flow);
    setPreviewText(previewFormat === 'yaml' ? toYaml(document) : JSON.stringify(document, null, 2));
    setPreviewError(null);
  }, [api, version, editor.flow, previewFormat]);

  /** Terapkan teks editor kembali menjadi flow pada kanvas. */
  const applyPreviewToFlow = () => {
    if (!isDraft) {
      return;
    }
    try {
      const document = previewFormat === 'yaml' ? parseYaml(previewText) : JSON.parse(previewText);
      const { flow: parsed, warnings } = fromApiDefinition(document);
      if (parsed.nodes.length === 0) {
        setPreviewError('Dokumen tidak memuat node; perubahan dibatalkan.');
        return;
      }
      editor.reset(parsed);
      setPreviewError(
        warnings.length > 0 ? `Diterapkan dengan catatan: ${warnings[0]}${
          warnings.length > 1 ? ` (+${warnings.length - 1} lagi)` : ''
        }` : null,
      );
      onNotify(
        warnings.length > 0
          ? `Flow diterapkan (${warnings.length} catatan).`
          : 'Flow diterapkan dari dokumen.',
        warnings.length > 0 ? 'info' : 'success',
      );
    } catch (cause) {
      setPreviewError(errorMessage(cause, 'Dokumen tidak valid'));
    }
  };

  /** Isi editor deklaratif saat tab dibuka atau versi berganti. */
  useEffect(() => {
    if (bottomTab === 'code') {
      syncPreviewFromFlow();
    }
    // sengaja hanya bergantung pada tab dan versi: menyunting teks tidak boleh
    // memicu sinkronisasi balik yang menghapus ketikan pengguna
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bottomTab, versionId, previewFormat]);

  // ------------------------------------------------------------- render

  if (!apiId) {
    return (
      <div className="grid h-full place-items-center p-6">
        <EmptyState
          title="Belum ada API aktif"
          description="Buka salah satu API dari tab APIs & Archives untuk mulai menyusun flow."
        />
      </div>
    );
  }

  if (apiState.loading || versionState.loading) {
    return (
      <div className="p-6">
        <Skeleton lines={8} />
      </div>
    );
  }

  if (!version) {
    return (
      <div className="grid h-full place-items-center p-6">
        <EmptyState title="Versi tidak ditemukan" description="API ini belum memiliki versi." />
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      {/* Toolbar */}
      <div className="flex shrink-0 flex-wrap items-center justify-between gap-2 border-b border-slate-800 bg-slate-900/70 px-3 py-1.5">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <Badge tone="primary" size="xs">
            {api?.method} {api?.path}
          </Badge>
          <Badge tone={versionTone(version.status)} size="xs">
            {version.label} · {version.status}
          </Badge>
          {editor.dirty && <Badge tone="warning" size="xs">belum disimpan</Badge>}
          {!structure.ok && (
            <Badge tone="error" size="xs">
              {structure.triggers !== 1
                ? `${structure.triggers} trigger`
                : 'tanpa response'}
            </Badge>
          )}
          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={editor.undo}
              disabled={!editor.canUndo}
              className="rounded px-1.5 py-0.5 text-[11px] text-slate-400 hover:bg-slate-800 disabled:opacity-40"
              title="Undo"
            >
              ↶
            </button>
            <button
              type="button"
              onClick={editor.redo}
              disabled={!editor.canRedo}
              className="rounded px-1.5 py-0.5 text-[11px] text-slate-400 hover:bg-slate-800 disabled:opacity-40"
              title="Redo"
            >
              ↷
            </button>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-1.5">
          <div className="w-28">
            <Select
              value={environment}
              onChange={(event) => setEnvironment(event.target.value)}
              options={['dev', 'staging', 'production'].map((value) => ({ value, label: value }))}
            />
          </div>
          <Button
            size="xs"
            variant="soft"
            icon={<IconPlus size={12} />}
            onClick={() => setPaletteDrawerOpen(true)}
            className="lg:hidden"
          >
            Tambah node
          </Button>
          <Button
            size="xs"
            variant="ghost"
            icon={<IconPlay size={12} />}
            onClick={() => setBottomTab('simulation')}
          >
            Simulasi
          </Button>
          <Button
            size="xs"
            variant="ghost"
            icon={<IconCheck size={12} />}
            loading={busy === 'validate'}
            onClick={validate}
          >
            Validasi
          </Button>
          <Button size="xs" variant="ghost" onClick={runPreflight} loading={busy === 'preflight'}>
            Preflight
          </Button>
          <Button
            size="xs"
            variant="solid"
            icon={<IconSave size={12} />}
            loading={busy === 'save'}
            disabled={!isDraft}
            onClick={save}
          >
            Simpan
          </Button>
        </div>
      </div>

      {error && (
        <div className="shrink-0 px-3 pt-2">
          <Alert tone="error" onClose={() => setError(null)}>
            {error}
          </Alert>
        </div>
      )}

      {!isDraft && (
        <div className="shrink-0 px-3 pt-2">
          <Alert tone="warning">
            Versi ini berstatus <strong>{version.status}</strong> dan tidak dapat diubah. Buat versi
            baru dari tab APIs &amp; Archives.
          </Alert>
        </div>
      )}

      {/* Area kerja */}
      <div className="flex min-h-0 flex-1">
        <div className="hidden w-56 shrink-0 lg:block">
          <NodePalette
            onAddNode={(type) => {
              const node = editor.addNode(type, { x: 320, y: 180 + editor.flow.nodes.length * 20 });
              setSelectedNodeId(node.id);
            }}
            templates={templatesState.data ?? []}
            onUseTemplate={(templateKey, versionNumber) => {
              const node = editor.addNode('TEMPLATE_CALL', {
                x: 320,
                y: 180 + editor.flow.nodes.length * 20,
              });
              editor.patchConfig(node.id, { templateKey, version: versionNumber });
              setSelectedNodeId(node.id);
            }}
          />
        </div>

        {/*
          Palette untuk layar kecil: tanpa ini pengguna pada layar sempit tidak
          dapat menambah node sama sekali karena sidebar disembunyikan.
        */}
        {paletteDrawerOpen && (
          <div className="fixed inset-0 z-40 flex bg-slate-950/70 lg:hidden">
            <div className="h-full w-72 max-w-full border-r border-slate-800 bg-slate-900">
              <div className="flex items-center justify-between border-b border-slate-800 px-3 py-2">
                <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-400">
                  Node palette
                </span>
                <button
                  type="button"
                  onClick={() => setPaletteDrawerOpen(false)}
                  className="text-slate-500 hover:text-slate-200"
                  aria-label="Tutup palette"
                >
                  ×
                </button>
              </div>
              <div className="h-[calc(100%-2.5rem)]">
                <NodePalette
                  onAddNode={(type) => {
                    const node = editor.addNode(type, {
                      x: 320,
                      y: 180 + editor.flow.nodes.length * 20,
                    });
                    setSelectedNodeId(node.id);
                    setPaletteDrawerOpen(false);
                  }}
                  templates={templatesState.data ?? []}
                  onUseTemplate={(templateKey, versionNumber) => {
                    const node = editor.addNode('TEMPLATE_CALL', {
                      x: 320,
                      y: 180 + editor.flow.nodes.length * 20,
                    });
                    editor.patchConfig(node.id, { templateKey, version: versionNumber });
                    setSelectedNodeId(node.id);
                    setPaletteDrawerOpen(false);
                  }}
                />
              </div>
            </div>
          </div>
        )}


        <div className="flex min-w-0 flex-1 flex-col">
          <div className="min-h-0 flex-1">
            <FlowCanvas
              editor={editor}
              selectedNodeId={selectedNodeId}
              onSelectNode={setSelectedNodeId}
              traceByNode={traceByNode}
            />
          </div>

          {/* Panel bawah: tinggi dapat diubah, dapat diperbesar ke layar penuh */}
          <section
            className={`shrink-0 border-t border-slate-800 bg-slate-900 ${
              bottomExpanded ? 'fixed inset-0 z-50 flex flex-col' : ''
            }`}
            style={bottomExpanded ? undefined : { height: bottomHeight }}
          >
            {/* Pegangan ubah ukuran */}
            {!bottomExpanded && (
              <div
                role="separator"
                aria-label="Ubah tinggi panel"
                aria-orientation="horizontal"
                onPointerDown={(event) => {
                  event.preventDefault();
                  const startY = event.clientY;
                  const startHeight = bottomPanelHeight;
                  const maxHeight = Math.max(240, window.innerHeight - 220);
                  const handleMove = (moveEvent: PointerEvent) => {
                    // panel tumbuh saat kursor naik
                    const next = startHeight + (startY - moveEvent.clientY);
                    setBottomPanelHeight(Math.min(Math.max(next, 140), maxHeight));
                  };
                  const handleUp = () => {
                    window.removeEventListener('pointermove', handleMove);
                    window.removeEventListener('pointerup', handleUp);
                    document.body.style.cursor = '';
                    document.body.style.userSelect = '';
                  };
                  window.addEventListener('pointermove', handleMove);
                  window.addEventListener('pointerup', handleUp);
                  document.body.style.cursor = 'row-resize';
                  document.body.style.userSelect = 'none';
                }}
                className="group flex h-2 cursor-row-resize items-center justify-center hover:bg-indigo-500/20"
              >
                <span className="h-0.5 w-10 rounded-full bg-slate-700 group-hover:bg-indigo-500" />
              </div>
            )}

            <div className={`flex items-center justify-between gap-2 px-2 ${bottomExpanded ? 'pt-2' : 'pt-1'}`}>
              <Tabs
                size="xs"
                active={bottomTab}
                onChange={(key) => setBottomTab(key as BottomTab)}
                tabs={[
                  { key: 'simulation', label: 'Simulasi' },
                  {
                    key: 'validation',
                    label: 'Validasi',
                    badge: validation ? (
                      <Badge tone={validation.valid ? 'success' : 'error'} size="xs">
                        {validation.errors}
                      </Badge>
                    ) : undefined,
                  },
                  {
                    key: 'preflight',
                    label: 'Preflight',
                    badge: preflight ? (
                      <Badge tone={preflight.ready ? 'success' : 'warning'} size="xs">
                        {preflight.ready ? 'siap' : 'tertahan'}
                      </Badge>
                    ) : undefined,
                  },
                  { key: 'code', label: 'Preview kode' },
                  { key: 'details', label: 'Detail versi' },
                ]}
              />
              <div className="flex items-center gap-1">
                <Button
                  size="xs"
                  variant="ghost"
                  onClick={() => setSaveTestOpen(true)}
                  disabled={!simulation || !lastSimulationPayload}
                >
                  Simpan sebagai test
                </Button>
                <button
                  type="button"
                  onClick={() => setBottomExpanded((value) => !value)}
                  title={bottomExpanded ? 'Perkecil panel' : 'Perbesar panel ke layar penuh'}
                  aria-label={bottomExpanded ? 'Perkecil panel' : 'Perbesar panel'}
                  className="rounded px-1.5 py-0.5 text-[11px] text-slate-400 hover:bg-slate-800 hover:text-slate-100"
                >
                  {bottomExpanded ? '⤡' : '⤢'}
                </button>
              </div>
            </div>

            <div className={`overflow-hidden ${bottomExpanded ? 'min-h-0 flex-1' : 'h-[calc(100%-2.5rem)]'}`}>
              {bottomTab === 'simulation' && (
                <SimulationPanel
                  running={busy === 'simulate'}
                  result={simulation}
                  error={simulationError}
                  defaultMethod={api?.method ?? 'POST'}
                  defaultPath={api?.path ?? '/'}
                  onRun={runSimulation}
                  onSelectTraceNode={setSelectedNodeId}
                />
              )}

              {bottomTab === 'validation' && (
                <div className="h-full overflow-y-auto p-2 custom-scrollbar">
                  {!validation ? (
                    <EmptyState
                      title="Belum divalidasi"
                      description='Klik "Validasi" untuk memeriksa struktur flow.'
                    />
                  ) : validation.issues.length === 0 ? (
                    <HintBox tone="success" title="Flow valid">
                      Tidak ada masalah yang ditemukan.
                    </HintBox>
                  ) : (
                    <Stack gap={1}>
                      {validation.issues.map((issue, index) => (
                        <button
                          key={index}
                          type="button"
                          onClick={() => issue.nodeId && setSelectedNodeId(issue.nodeId)}
                          className={`flex w-full items-start gap-2 rounded border px-2 py-1 text-left ${
                            issue.severity === 'ERROR'
                              ? 'border-rose-900/60 bg-rose-950/30'
                              : 'border-amber-900/60 bg-amber-950/20'
                          }`}
                        >
                          <Badge tone={issue.severity === 'ERROR' ? 'error' : 'warning'} size="xs">
                            {issue.severity}
                          </Badge>
                          <span className="min-w-0 flex-1 text-[11px] text-slate-300">
                            {issue.message}
                          </span>
                          {issue.nodeId && (
                            <span className="shrink-0 font-mono text-[10px] text-slate-500">
                              {issue.nodeId}
                            </span>
                          )}
                        </button>
                      ))}
                    </Stack>
                  )}
                </div>
              )}

              {bottomTab === 'preflight' && (
                <div className="h-full overflow-y-auto p-2 custom-scrollbar">
                  {!preflight ? (
                    <EmptyState
                      title="Belum ada pemeriksaan"
                      description='Klik "Preflight" untuk mengecek kesiapan publish.'
                    />
                  ) : (
                    <Stack gap={1}>
                      <div className="flex items-center gap-2">
                        <Badge tone={preflight.ready ? 'success' : 'warning'} size="sm">
                          {preflight.ready ? 'Siap publish' : 'Belum siap'}
                        </Badge>
                        <span className="text-[11px] text-slate-500">
                          environment {preflight.environment}
                        </span>
                      </div>
                      {preflight.checks.map((check) => (
                        <div
                          key={check.name}
                          className={`flex items-start gap-2 rounded border px-2 py-1 ${
                            check.passed
                              ? 'border-emerald-900/50 bg-emerald-950/20'
                              : 'border-amber-900/60 bg-amber-950/20'
                          }`}
                        >
                          {check.passed ? (
                            <IconCheck size={12} className="mt-0.5 shrink-0 text-emerald-400" />
                          ) : (
                            <IconX size={12} className="mt-0.5 shrink-0 text-amber-400" />
                          )}
                          <div className="min-w-0 flex-1">
                            <p className="text-[11px] font-medium capitalize text-slate-200">
                              {check.name}
                            </p>
                            <p className="text-[10px] text-slate-400">{check.message}</p>
                          </div>
                        </div>
                      ))}
                    </Stack>
                  )}
                </div>
              )}

              {bottomTab === 'code' && (
                <div className="flex h-full flex-col p-2">
                  <div className="mb-1 flex items-center justify-between gap-2">
                    <SegmentedControl
                      value={previewFormat}
                      onChange={(value) => {
                        setPreviewFormat(value);
                        // sinkronkan teks dari flow saat berganti format
                        setPreviewText(
                          value === 'yaml'
                            ? toYaml(toApiDefinition(api, version, editor.flow))
                            : JSON.stringify(toApiDefinition(api, version, editor.flow), null, 2),
                        );
                        setPreviewError(null);
                      }}
                      size="xs"
                      options={[
                        { value: 'yaml', label: 'YAML' },
                        { value: 'json', label: 'JSON' },
                      ]}
                    />
                    <div className="flex items-center gap-1">
                      <Button
                        size="xs"
                        variant="ghost"
                        icon={<IconCode size={11} />}
                        onClick={() => {
                          void navigator.clipboard.writeText(previewText).then(
                            () => onNotify('Definisi flow disalin.', 'success'),
                            () => onNotify('Clipboard tidak tersedia.', 'error'),
                          );
                        }}
                      >
                        Salin
                      </Button>
                      <Button
                        size="xs"
                        variant="soft"
                        onClick={applyPreviewToFlow}
                        disabled={!isDraft}
                      >
                        Terapkan ke flow
                      </Button>
                      <Button
                        size="xs"
                        variant="ghost"
                        onClick={() => {
                          setPreviewText(
                            previewFormat === 'yaml'
                              ? toYaml(toApiDefinition(api, version, editor.flow))
                              : JSON.stringify(toApiDefinition(api, version, editor.flow), null, 2),
                          );
                          setPreviewError(null);
                        }}
                      >
                        Setel ulang
                      </Button>
                    </div>
                  </div>

                  {previewError && (
                    <p className="mb-1 truncate rounded border border-rose-900/60 bg-rose-950/30 px-2 py-1 text-[10px] text-rose-300">
                      {previewError}
                    </p>
                  )}

                  <div className="min-h-0 flex-1">
                    {previewFormat === 'yaml' ? (
                      <YamlEditor
                        value={previewText}
                        onChange={setPreviewText}
                        schema={API_DEFINITION_SCHEMA}
                        title="api-definition.yaml"
                        height="100%"
                        readOnly={!isDraft}
                      />
                    ) : (
                      <CodeEditor
                        value={previewText}
                        onChange={setPreviewText}
                        language={jsonLanguage}
                        title="api-definition.json"
                        height="100%"
                        readOnly={!isDraft}
                      />
                    )}
                  </div>
                </div>
              )}

              {bottomTab === 'details' && (
                <div className="h-full overflow-auto p-2 custom-scrollbar">
                  <pre className="rounded border border-slate-800 bg-slate-950 p-2 font-mono text-[10px] text-slate-300">
                    {prettyJson({
                      version: version.label,
                      status: version.status,
                      changeNote: version.changeNote,
                      createdAt: formatDateTime(version.createdAt),
                      publishedAt: formatDateTime(version.publishedAt),
                      nodes: editor.flow.nodes.length,
                      edges: editor.flow.edges.length,
                      javaNodes: Object.keys(version.javaSources ?? {}),
                    })}
                  </pre>
                </div>
              )}
            </div>
          </section>
        </div>

        {/* Panel konfigurasi: sidebar tetap pada layar lebar, panel geser pada layar kecil */}
        <aside className="hidden w-80 shrink-0 border-l border-slate-800 xl:block">
          <div className="h-full overflow-hidden">
            <NodeConfigPanel
              node={selectedNode}
              allNodes={editor.flow.nodes}
              version={version}
              templates={templatesState.data ?? []}
              compileResult={selectedNode ? compileResults[selectedNode.id] : undefined}
              compiling={selectedNode ? compilingNode === selectedNode.id : false}
              onCompile={compileNode}
              onLabelChange={(label) => selectedNode && editor.setLabel(selectedNode.id, label)}
              onPatchConfig={(patch) => selectedNode && editor.patchConfig(selectedNode.id, patch)}
              onDisabledChange={(disabled) =>
                selectedNode && editor.setDisabled(selectedNode.id, disabled)
              }
              onErrorPolicyChange={(policy) =>
                selectedNode && editor.setErrorPolicy(selectedNode.id, policy)
              }
              onExpand={() => setConfigExpanded(true)}
              onDelete={() => {
                if (selectedNode) {
                  editor.removeNode(selectedNode.id);
                  setSelectedNodeId(null);
                }
              }}
            />
          </div>
        </aside>
      </div>

      {/* Panel konfigurasi layar penuh */}
      {configExpanded && selectedNode && (
        <div className="fixed inset-0 z-50 flex flex-col bg-slate-950/95 p-3 backdrop-blur-sm">
          <div className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-xl border border-slate-700 bg-slate-900 shadow-2xl">
            <div className="flex shrink-0 items-center justify-between border-b border-slate-800 px-3 py-2">
              <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-400">
                Konfigurasi node — layar penuh
              </span>
              <button
                type="button"
                onClick={() => setConfigExpanded(false)}
                className="text-slate-500 hover:text-slate-200"
                aria-label="Tutup"
              >
                ×
              </button>
            </div>
            <div className="min-h-0 flex-1 overflow-hidden">
              <NodeConfigPanel
                node={selectedNode}
                allNodes={editor.flow.nodes}
                version={version}
                templates={templatesState.data ?? []}
                compileResult={compileResults[selectedNode.id]}
                compiling={compilingNode === selectedNode.id}
                onCompile={compileNode}
                onLabelChange={(label) => editor.setLabel(selectedNode.id, label)}
                onPatchConfig={(patch) => editor.patchConfig(selectedNode.id, patch)}
                onDisabledChange={(disabled) => editor.setDisabled(selectedNode.id, disabled)}
                onErrorPolicyChange={(policy) => editor.setErrorPolicy(selectedNode.id, policy)}
                onDelete={() => {
                  editor.removeNode(selectedNode.id);
                  setSelectedNodeId(null);
                  setConfigExpanded(false);
                }}
              />
            </div>
          </div>
          <p className="mt-1.5 text-center text-[10px] text-slate-500">
            Esc untuk menutup · editor kode punya tombol perbesar sendiri
          </p>
        </div>
      )}

      {/*
        Panel konfigurasi untuk layar kecil: pengguna sering hanya punya satu
        monitor. Tanpa ini, mengedit kode Java pada node tidak mungkin karena
        sidebar di atas disembunyikan pada breakpoint xl.
      */}
      {selectedNode && (
        <button
          type="button"
          onClick={() => setConfigDrawerOpen(true)}
          className="fixed bottom-4 right-4 z-40 rounded-full bg-indigo-600 px-4 py-2 text-xs font-semibold text-white shadow-xl shadow-indigo-600/30 xl:hidden"
        >
          Konfigurasi node
        </button>
      )}

      {configDrawerOpen && selectedNode && (
        <div className="fixed inset-0 z-40 flex justify-end bg-slate-950/70 xl:hidden">
          <div className="h-full w-[26rem] max-w-full border-l border-slate-800 bg-slate-900">
            <div className="flex items-center justify-between border-b border-slate-800 px-3 py-2">
              <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-400">
                Konfigurasi node
              </span>
              <button
                type="button"
                onClick={() => setConfigDrawerOpen(false)}
                className="text-slate-500 hover:text-slate-200"
                aria-label="Tutup panel"
              >
                ×
              </button>
            </div>
            <div className="h-[calc(100%-2.5rem)] overflow-hidden">
              <NodeConfigPanel
                node={selectedNode}
                allNodes={editor.flow.nodes}
                version={version}
                templates={templatesState.data ?? []}
                compileResult={compileResults[selectedNode.id]}
                compiling={compilingNode === selectedNode.id}
                onCompile={compileNode}
                onLabelChange={(label) => editor.setLabel(selectedNode.id, label)}
                onPatchConfig={(patch) => editor.patchConfig(selectedNode.id, patch)}
                onDisabledChange={(disabled) => editor.setDisabled(selectedNode.id, disabled)}
                onErrorPolicyChange={(policy) => editor.setErrorPolicy(selectedNode.id, policy)}
                onDelete={() => {
                  editor.removeNode(selectedNode.id);
                  setSelectedNodeId(null);
                  setConfigDrawerOpen(false);
                }}
              />
            </div>
          </div>
        </div>
      )}

      {/* Modal simpan test */}
      <Modal
        open={saveTestOpen}
        title="Simpan sebagai test case"
        description="Request terakhir dari simulasi disimpan ke test suite API ini."
        onClose={() => setSaveTestOpen(false)}
        width="max-w-md"
        footer={
          <>
            <Button variant="ghost" size="sm" onClick={() => setSaveTestOpen(false)}>
              Batal
            </Button>
            <Button
              variant="solid"
              size="sm"
              loading={busy === 'savetest'}
              disabled={!testName.trim()}
              onClick={saveAsTest}
            >
              Simpan
            </Button>
          </>
        }
      >
        <Stack gap={2}>
          <Field label="Nama test case" required>
            <TextInput
              value={testName}
              onChange={(event) => setTestName(event.target.value)}
              placeholder="Transfer sukses"
            />
          </Field>
          {lastSimulationPayload && (
            <Panel title="Ringkasan request">
              <pre className="max-h-40 overflow-auto font-mono text-[10px] text-slate-400">
                {prettyJson({
                  method: lastSimulationPayload.method,
                  path: lastSimulationPayload.path,
                  body: lastSimulationPayload.body,
                  assertions: lastSimulationPayload.assertions.length,
                })}
              </pre>
            </Panel>
          )}
          <Checkbox label="Jalankan otomatis pada test suite (blocking)" defaultChecked disabled />
        </Stack>
      </Modal>
    </div>
  );
}
