import { useEffect, useMemo, useState } from 'react';
import type {
  ApiVersionDetail,
  CompileNodeProblem,
  CompileNodeResult,
  FlowNode,
  FunctionTemplateInfo,
} from '../../api/types';
import { NODE_META } from './nodeCatalog';
import {
  Badge,
  Button,
  Checkbox,
  ExpressionEditor,
  extractBodyFields,
  extractFlowVariables,
  Field,
  HintBox,
  JavaEditor,
  JsonEditor,
  Panel,
  SegmentedControl,
  Select,
  SqlEditor,
  Stack,
  TextArea,
  TextInput,
  schemaFromSeeds,
} from '../../ui';

interface ConfigPanelProps {
  /** Node yang dikonfigurasi; {@code null} menampilkan keadaan kosong. */
  node: FlowNode | null;
  allNodes: FlowNode[];
  version: ApiVersionDetail | null;
  templates: FunctionTemplateInfo[];
  compileResult?: CompileNodeResult;
  compiling: boolean;
  onCompile: (nodeId: string, source: string, libraries: string[]) => void;
  onLabelChange: (label: string) => void;
  onPatchConfig: (patch: Record<string, unknown>) => void;
  onDisabledChange: (disabled: boolean) => void;
  onErrorPolicyChange: (policy: FlowNode['onError']) => void;
  onDelete: () => void;
  /** Buka konfigurasi dalam mode layar penuh. */
  onExpand?: () => void;
}

/**
 * Panel konfigurasi node.
 *
 * <p>Setiap jenis isi memakai editor khusus (Java, SQL, JSON, ekspresi) dengan
 * pewarnaan sintaks dan autocomplete, sehingga pengguna tidak menulis kode di
 * kotak teks polos. Tata letak dibuat seragam: judul node, konten per jenis,
 * lalu kebijakan error.
 */
export function NodeConfigPanel({
  node,
  allNodes,
  version,
  templates,
  compileResult,
  compiling,
  onCompile,
  onLabelChange,
  onPatchConfig,
  onDisabledChange,
  onErrorPolicyChange,
  onDelete,
  onExpand,
}: ConfigPanelProps) {
  // --------------------------------------------------------------- hooks
  // Semua hook dipanggil sebelum early-return agar urutannya stabil.

  const javaSource = useMemo(() => {
    if (!node) {
      return '';
    }
    const fromNode = typeof node.config.source === 'string' ? node.config.source : '';
    return fromNode || version?.javaSources?.[node.id]?.source || '';
  }, [node, version]);

  const [draftSource, setDraftSource] = useState(javaSource);
  useEffect(() => {
    setDraftSource(javaSource);
  }, [javaSource, node?.id]);

  const [librariesText, setLibrariesText] = useState('');
  useEffect(() => {
    const libs = node?.config.libraries;
    setLibrariesText(Array.isArray(libs) ? (libs as string[]).join('\n') : '');
  }, [node?.id, node?.config.libraries]);

  /** Variabel flow & field body untuk autocomplete ekspresi. */
  const context = useMemo(() => {
    const variables = extractFlowVariables(
      allNodes.map((candidate) => ({ type: candidate.type, config: candidate.config })),
    );
    const bodyFields = [...extractBodyFields(allNodes.map((candidate) => candidate.config))].sort();
    return { variables, bodyFields };
  }, [allNodes]);

  const schema = useMemo(
    () => schemaFromSeeds(version?.seeds ?? []),
    [version?.seeds],
  );

  if (!node) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-2 p-4 text-center">
        <p className="text-[11px] text-slate-500">Pilih node di kanvas untuk mengubah konfigurasinya.</p>
        <p className="max-w-[15rem] text-[10px] text-slate-600">
          Tambahkan node dari palette, lalu klik untuk membuka editornya.
        </p>
      </div>
    );
  }

  const meta = NODE_META[node.type];
  const e = (key: string, fallback = '') => String(node.config[key] ?? fallback);
  const set = (key: string, value: unknown) => onPatchConfig({ [key]: value });

  /** Editor ekspresi dengan konteks flow. */
  const expression = (key: string, placeholder: string, height = '8rem') => (
    <ExpressionEditor
      value={e(key)}
      onChange={(value) => set(key, value)}
      placeholder={placeholder}
      height={height}
      flowVariables={context.variables}
      bodyFields={context.bodyFields}
    />
  );

  return (
    <div className="flex h-full flex-col overflow-hidden bg-slate-900">
      {/* Header */}
      <header className="flex shrink-0 items-center justify-between gap-2 border-b border-slate-800 px-3 py-2">
        <div className="min-w-0">
          <h2 className="truncate text-[11px] font-bold uppercase tracking-wider text-slate-400">
            {meta?.label ?? node.type}
          </h2>
          <p className="truncate font-mono text-[10px] text-slate-600">{node.id}</p>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <Checkbox
            label="nonaktif"
            checked={!!node.disabled}
            onChange={(event) => onDisabledChange(event.target.checked)}
          />
          {onExpand && (
            <button
              type="button"
              onClick={onExpand}
              title="Perbesar panel konfigurasi"
              aria-label="Perbesar panel konfigurasi"
              className="rounded px-1 py-0.5 text-[11px] text-slate-400 hover:bg-slate-800 hover:text-slate-100"
            >
              ?
            </button>
          )}
        </div>
      </header>

      <div className="min-h-0 flex-1 space-y-3 overflow-y-auto p-3 custom-scrollbar">
        <Field label="Judul node">
          <TextInput value={node.label} onChange={(event) => onLabelChange(event.target.value)} />
        </Field>

        {/* ------------------------------------------------ editor per jenis */}

        {node.type === 'CONDITION' && (
          <Field label="Kondisi (true / false)" hint="Hasil menentukan cabang ya/tidak.">
            {expression('expression', '=number(request.body.amount) > 0')}
          </Field>
        )}

        {node.type === 'SWITCH' && (
          <>
            <Field label="Nilai yang di-switch">{expression('expression', '=request.body.type', '6rem')}</Field>
            <JsonEditor
              title="Case"
              value={node.config.cases ?? []}
              onChange={(value) => set('cases', value)}
              placeholder='["transfer", "payment"]'
              height="6rem"
            />
          </>
        )}

        {(node.type === 'SET_VARIABLE' || node.type === 'TRANSFORM') && (
          <JsonEditor
            title="Assignments"
            value={node.config.assignments ?? {}}
            onChange={(value) => set('assignments', value)}
            placeholder={'{\n  "total": "=request.body.price * request.body.qty"\n}'}
            height="9rem"
          />
        )}

        {node.type === 'LOOP' && (
          <>
            <Field label="Koleksi">{expression('items', '=request.body.items', '6rem')}</Field>
            <Field label="Nama variabel item">
              <TextInput
                value={e('itemName', 'item')}
                onChange={(event) => set('itemName', event.target.value)}
                className="font-mono"
              />
            </Field>
          </>
        )}

        {node.type === 'PARALLEL' && (
          <Field label="Timeout cabang (ms)">
            <TextInput
              type="number"
              value={e('timeoutMs', '30000')}
              onChange={(event) => set('timeoutMs', Number(event.target.value))}
            />
          </Field>
        )}

        {node.type === 'HTTP_CALL' && (
          <>
            <div className="grid grid-cols-[6rem_1fr] gap-2">
              <Field label="Method">
                <Select
                  value={e('method', 'POST')}
                  onChange={(event) => set('method', event.target.value)}
                  options={['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((value) => ({ value, label: value }))}
                />
              </Field>
              <Field label="URL">
                <TextInput
                  value={e('url')}
                  onChange={(event) => set('url', event.target.value)}
                  className="font-mono"
                  placeholder="https://api.contoh.com/endpoint"
                />
              </Field>
            </div>
            <JsonEditor
              title="Headers"
              value={node.config.headers ?? {}}
              onChange={(value) => set('headers', value)}
              height="6rem"
            />
            <JsonEditor
              title="Body"
              value={node.config.body ?? {}}
              onChange={(value) => set('body', value)}
              placeholder={'{\n  "id": "=request.body.id"\n}'}
              height="8rem"
            />
            <Field label="Timeout (ms)">
              <TextInput
                type="number"
                value={e('timeoutMs', '10000')}
                onChange={(event) => set('timeoutMs', Number(event.target.value))}
              />
            </Field>
          </>
        )}

        {node.type === 'DATABASE_QUERY' && (
          <>
            <Field label="SQL" hint="Hanya SELECT; query lain ditolak sandbox.">
              <SqlEditor
                value={e('sql')}
                onChange={(value) => set('sql', value)}
                schema={schema}
                height="9rem"
              />
            </Field>
            <JsonEditor
              title="Parameter"
              value={node.config.params ?? []}
              onChange={(value) => set('params', value)}
              placeholder='["=request.body.accountNo"]'
              height="5rem"
            />
          </>
        )}

        {node.type === 'TEMPLATE_CALL' && (
          <>
            <Field label="Template">
              <Select
                value={e('templateKey')}
                onChange={(event) => set('templateKey', event.target.value)}
                options={[
                  { value: '', label: '— pilih template —' },
                  ...templates.map((template) => ({
                    value: template.templateKey,
                    label: `${template.templateKey} — ${template.name}`,
                  })),
                ]}
              />
            </Field>
            <div className="grid grid-cols-[1fr_5rem] gap-2">
              <Field label="Kunci (manual)">
                <TextInput
                  value={e('templateKey')}
                  onChange={(event) => set('templateKey', event.target.value)}
                  className="font-mono"
                />
              </Field>
              <Field label="Versi">
                <TextInput
                  type="number"
                  value={e('version', '1')}
                  onChange={(event) => set('version', Number(event.target.value))}
                />
              </Field>
            </div>
            <JsonEditor
              title="Argumen"
              value={node.config.args ?? {}}
              onChange={(value) => set('args', value)}
              placeholder={'{\n  "payload": "=request.body"\n}'}
              height="7rem"
            />
          </>
        )}

        {node.type === 'JAVA_CODE' && (
          <>
            <JavaEditor
              value={draftSource}
              onChange={setDraftSource}
              onBlur={() => set('source', draftSource)}
              flowVariables={context.variables}
              height="24rem"
              actions={
                <>
                  {compileResult && (
                    <Badge tone={compileResult.success ? 'success' : 'error'} size="xs">
                      {compileResult.success ? 'terkompilasi' : 'gagal'}
                    </Badge>
                  )}
                  <Button
                    size="xs"
                    variant="soft"
                    loading={compiling}
                    onClick={() =>
                      onCompile(
                        node.id,
                        draftSource,
                        librariesText
                          .split('\n')
                          .map((line) => line.trim())
                          .filter(Boolean),
                      )
                    }
                  >
                    Check
                  </Button>
                </>
              }
            />

            <Field label="Library Maven" hint="Satu koordinat per baris.">
              <TextArea
                mono
                rows={2}
                value={librariesText}
                onChange={(event) => setLibrariesText(event.target.value)}
                onBlur={() =>
                  set(
                    'libraries',
                    librariesText
                      .split('\n')
                      .map((line) => line.trim())
                      .filter(Boolean),
                  )
                }
                placeholder="com.google.guava:guava:33.0.0-jre"
              />
            </Field>

            {compileResult && <CompileProblems result={compileResult} />}
          </>
        )}

        {node.type === 'RESPONSE' && (
          <>
            <Field label="Status HTTP">
              <TextInput
                type="number"
                value={e('status', '200')}
                onChange={(event) => set('status', Number(event.target.value))}
              />
            </Field>
            <JsonEditor
              title="Headers"
              value={node.config.headers ?? {}}
              onChange={(value) => set('headers', value)}
              height="5rem"
            />
            <JsonEditor
              title="Body"
              value={node.config.body ?? {}}
              onChange={(value) => set('body', value)}
              placeholder={'{\n  "responseCode": "2007300",\n  "data": "=request.body"\n}'}
              height="10rem"
            />
          </>
        )}

        {node.type === 'LOG' && (
          <>
            <Field label="Pesan" hint="Mendukung ${...}, mis. id=${request.body.id}">
              <TextInput value={e('message')} onChange={(event) => set('message', event.target.value)} />
            </Field>
            <Field label="Level">
              <Select
                value={e('level', 'info')}
                onChange={(event) => set('level', event.target.value)}
                options={['debug', 'info', 'warn', 'error'].map((value) => ({ value, label: value }))}
              />
            </Field>
          </>
        )}

        {node.type === 'DELAY' && (
          <Field label="Durasi (ms)">
            <TextInput
              type="number"
              value={e('ms', '200')}
              onChange={(event) => set('ms', Number(event.target.value))}
            />
          </Field>
        )}

        {node.type === 'FAIL' && (
          <Field label="Pesan kegagalan">
            <TextInput value={e('message')} onChange={(event) => set('message', event.target.value)} />
          </Field>
        )}

        {node.type === 'TRIGGER' && (
          <HintBox tone="info" title="Titik masuk">
            Method dan path diatur pada metadata API, bukan di node ini.
          </HintBox>
        )}

        {node.type === 'TRY_CATCH' && (
          <HintBox tone="info" title="Try / Catch">
            Hubungkan port <code>out</code> ke alur utama dan <code>error</code> ke penangan kegagalan.
          </HintBox>
        )}

        {/* ------------------------------------------ kebijakan error */}
        <Panel title="Retry, Timeout, Fallback">
          <Stack gap={2}>
            <div className="grid grid-cols-3 gap-2">
              <Field label="Retries">
                <TextInput
                  type="number"
                  min={0}
                  value={String(node.onError?.retries ?? 0)}
                  onChange={(event) =>
                    onErrorPolicyChange({ ...(node.onError ?? {}), retries: Number(event.target.value) })
                  }
                />
              </Field>
              <Field label="Timeout ms">
                <TextInput
                  type="number"
                  value={String(node.onError?.timeoutMs ?? '')}
                  onChange={(event) =>
                    onErrorPolicyChange({
                      ...(node.onError ?? {}),
                      timeoutMs: event.target.value === '' ? null : Number(event.target.value),
                    })
                  }
                />
              </Field>
              <Field label="Backoff ms">
                <TextInput
                  type="number"
                  min={0}
                  value={String(node.onError?.backoffMs ?? 0)}
                  onChange={(event) =>
                    onErrorPolicyChange({ ...(node.onError ?? {}), backoffMs: Number(event.target.value) })
                  }
                />
              </Field>
            </div>
            <Field label="Saat gagal">
              <SegmentedControl
                value={node.onError?.onError ?? 'FAIL'}
                onChange={(value) => onErrorPolicyChange({ ...(node.onError ?? {}), onError: value })}
                size="xs"
                options={[
                  { value: 'FAIL', label: 'Gagal' },
                  { value: 'CONTINUE', label: 'Lanjut' },
                  { value: 'FALLBACK', label: 'Fallback' },
                ]}
              />
            </Field>
            {node.onError?.onError === 'FALLBACK' && (
              <Field label="Node fallback">
                <Select
                  value={String(node.onError?.fallbackNodeId ?? '')}
                  onChange={(event) =>
                    onErrorPolicyChange({ ...(node.onError ?? {}), fallbackNodeId: event.target.value })
                  }
                  options={[
                    { value: '', label: '— pilih node —' },
                    ...allNodes
                      .filter((candidate) => candidate.id !== node.id)
                      .map((candidate) => ({ value: candidate.id, label: candidate.label })),
                  ]}
                />
              </Field>
            )}
          </Stack>
        </Panel>

        <Button variant="danger" size="xs" block onClick={onDelete}>
          Hapus node
        </Button>
      </div>
    </div>
  );
}

/** Daftar masalah kompilasi dengan penanda baris kode pengguna. */
function CompileProblems({ result }: { result: CompileNodeResult }) {
  if (result.problems.length === 0) {
    return (
      <HintBox tone="success" title="Kompilasi berhasil">
        Bytecode siap dipakai saat simulasi dan publish.
      </HintBox>
    );
  }
  return (
    <Panel title={`Kompilasi: ${result.problems.length} masalah`}>
      <ul className="space-y-1">
        {result.problems.map((problem, index) => (
          <li key={index} className="flex items-start gap-1.5">
            <Badge tone={problem.severity === 'ERROR' ? 'error' : 'warning'} size="xs">
              {problem.line > 0 ? `baris ${problem.line}` : problem.severity}
            </Badge>
            <span className="min-w-0 flex-1 break-words text-[11px] leading-relaxed text-slate-300">
              {problem.message}
            </span>
          </li>
        ))}
      </ul>
    </Panel>
  );
}

/** Tipe masalah kompilasi untuk dipakai halaman. */
export type { CompileNodeProblem };
