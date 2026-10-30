import { useCallback, useMemo, useState } from 'react';
import { apiApi, testApi } from '../api/endpoints';
import type { ApiDetail, ApiSummary, SimulationResult, TestCase, TestRunSummary } from '../api/types';
import { useAsync } from '../app/hooks';
import { IconPlay, IconRefresh, IconSave } from '../app/icons';
import {
  Badge,
  Button,
  Card,
  CodeBlock,
  DataTable,
  EmptyState,
  Field,
  HintBox,
  JsonEditor,
  Panel,
  Select,
  Skeleton,
  Stack,
  Tabs,
  TextInput,
  type Column,
} from '../ui';
import { errorMessage, formatDateTime, formatDuration, parseJsonOrThrow, prettyJson } from '../lib/format';
import { httpTone, runTone, traceTone } from '../ui/types';

/**
 * Playground: simulasi request terhadap API terpilih dan riwayat test run.
 *
 * Berbeda dari panel simulasi di kanvas, halaman ini berfokus pada pengujian
 * cepat terhadap versi mana pun (draft maupun published) tanpa membuka editor.
 */
export function PlaygroundPage({
  apiId,
  onNotify,
}: {
  apiId: string | null;
  onNotify: (message: string, tone: 'success' | 'error' | 'info') => void;
}) {
  const loadApis = () => apiApi.list();
  const apis = useAsync<ApiSummary[]>(loadApis, []);

  const [selectedApiId, setSelectedApiId] = useState(apiId ?? '');
  const effectiveApiId = selectedApiId || apiId || apis.data?.[0]?.id || '';

  const loadDetail = useCallback(
    () => (effectiveApiId ? apiApi.detail(effectiveApiId) : Promise.resolve(null)),
    [effectiveApiId],
  );
  const detail = useAsync<ApiDetail | null>(loadDetail, [effectiveApiId]);

  const [versionId, setVersionId] = useState('');
  const versions = detail.data?.versions ?? [];
  const effectiveVersionId =
    versionId || versions.find((version) => version.status === 'DRAFT')?.id || versions[0]?.id || '';

  const [method, setMethod] = useState('POST');
  const [path, setPath] = useState('');
  const [headersText, setHeadersText] = useState('{\n  "Content-Type": "application/json"\n}');
  const [bodyText, setBodyText] = useState('{\n  \n}');
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<SimulationResult | null>(null);

  const loadHistory = useCallback(
    () => (effectiveApiId ? testApi.history(effectiveApiId) : Promise.resolve([])),
    [effectiveApiId],
  );
  const history = useAsync<TestRunSummary[]>(loadHistory, [effectiveApiId]);

  const loadTests = useCallback(
    () => (effectiveApiId ? testApi.list(effectiveApiId) : Promise.resolve([])),
    [effectiveApiId],
  );
  const testCases = useAsync<TestCase[]>(loadTests, [effectiveApiId]);

  const currentApi = useMemo(
    () => apis.data?.find((api) => api.id === effectiveApiId) ?? null,
    [apis.data, effectiveApiId],
  );

  const run = async () => {
    if (!effectiveVersionId) {
      onNotify('Pilih API dan versi terlebih dahulu.', 'error');
      return;
    }
    setRunning(true);
    try {
      const headers = (parseJsonOrThrow(headersText, 'Headers') ?? {}) as Record<string, string>;
      const body = parseJsonOrThrow(bodyText, 'Body');
      const response = await testApi.simulate({
        versionId: effectiveVersionId,
        method,
        path: path || currentApi?.path || '/',
        headers,
        body,
      });
      setResult(response);
      onNotify(
        response.statusCode >= 500
          ? `Eksekusi gagal (HTTP ${response.statusCode})`
          : `Selesai dalam ${formatDuration(response.durationMs)}`,
        response.statusCode >= 500 ? 'error' : 'success',
      );
      history.reload();
    } catch (cause) {
      onNotify(
        cause instanceof SyntaxError
          ? 'JSON body/header tidak valid.'
          : errorMessage(cause, 'Simulasi gagal'),
        'error',
      );
    } finally {
      setRunning(false);
    }
  };

  const runSavedTest = async (testId: string) => {
    try {
      const runResult = await testApi.run(testId, 'dev');
      onNotify(
        `Test ${runResult.testCaseName ?? ''}: ${runResult.status}`,
        runResult.status === 'PASSED' ? 'success' : 'error',
      );
      history.reload();
    } catch (cause) {
      onNotify(errorMessage(cause, 'Gagal menjalankan test'), 'error');
    }
  };

  const historyColumns: Column<TestRunSummary>[] = [
    {
      key: 'testCaseName',
      header: 'Test',
      render: (row) => (
        <span className="text-[11px] text-slate-200">{row.testCaseName ?? row.id.slice(0, 8)}</span>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <Badge tone={runTone(row.status)} size="xs">
          {row.status}
        </Badge>
      ),
    },
    {
      key: 'statusCode',
      header: 'HTTP',
      render: (row) => (
        <Badge tone={httpTone(row.statusCode)} size="xs">
          {row.statusCode}
        </Badge>
      ),
    },
    {
      key: 'durationMs',
      header: 'Durasi',
      hideOnMobile: true,
      render: (row) => <span className="tabular-nums text-[11px]">{formatDuration(row.durationMs)}</span>,
    },
    {
      key: 'createdAt',
      header: 'Waktu',
      hideOnMobile: true,
      render: (row) => (
        <span className="text-[10px] text-slate-500">{formatDateTime(row.createdAt)}</span>
      ),
    },
  ];

  return (
    <div className="h-full overflow-y-auto p-4 custom-scrollbar">
      <div className="mb-3 flex flex-wrap items-start justify-between gap-2">
        <div>
          <h2 className="text-base font-bold tracking-tight text-slate-100">API Playground</h2>
          <p className="text-[11px] text-slate-400">
            Jalankan request terhadap versi mana pun dan periksa trace per node.
          </p>
        </div>
        <Button
          size="xs"
          variant="ghost"
          icon={<IconRefresh size={12} />}
          onClick={() => {
            apis.reload();
            history.reload();
            testCases.reload();
          }}
        >
          Muat ulang
        </Button>
      </div>

      <div className="grid gap-3 xl:grid-cols-2">
        {/* Form */}
        <Card title="Request" subtitle="Header dan body dikirim sebagai JSON">
          <Stack gap={2}>
            <div className="grid grid-cols-2 gap-2">
              <Field label="API">
                <Select
                  value={effectiveApiId}
                  onChange={(event) => {
                    setSelectedApiId(event.target.value);
                    setVersionId('');
                  }}
                  options={(apis.data ?? []).map((api) => ({
                    value: api.id,
                    label: `${api.method} ${api.path}`,
                  }))}
                />
              </Field>
              <Field label="Versi">
                <Select
                  value={effectiveVersionId}
                  onChange={(event) => setVersionId(event.target.value)}
                  options={versions.map((version) => ({
                    value: version.id,
                    label: `${version.label} · ${version.status}`,
                  }))}
                />
              </Field>
            </div>

            <div className="flex gap-1.5">
              <div className="w-24 shrink-0">
                <Select
                  value={method}
                  onChange={(event) => setMethod(event.target.value)}
                  options={['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((item) => ({
                    value: item,
                    label: item,
                  }))}
                />
              </div>
              <TextInput
                value={path}
                onChange={(event) => setPath(event.target.value)}
                placeholder={currentApi?.path ?? '/path'}
                className="font-mono"
              />
            </div>

            <JsonEditor
              title="Headers"
              value={parseJsonOrThrow(headersText, 'Headers') ?? {}}
              onChange={(value) => setHeadersText(value === null ? '' : JSON.stringify(value, null, 2))}
              height="6rem"
            />

            <JsonEditor
              title="Body"
              value={parseJsonOrThrow(bodyText, 'Body') ?? {}}
              onChange={(value) => setBodyText(value === null ? '' : JSON.stringify(value, null, 2))}
              height="11rem"
            />

            <Button variant="solid" size="sm" block loading={running} onClick={run} icon={<IconPlay size={13} />}>
              Kirim request
            </Button>
          </Stack>
        </Card>

        {/* Hasil */}
        <Stack gap={3}>
          <Card
            title="Response"
            subtitle={result ? `${formatDuration(result.durationMs)} · ${result.trace.length} node` : undefined}
            actions={
              result && (
                <Badge tone={httpTone(result.statusCode)} size="sm">
                  HTTP {result.statusCode}
                </Badge>
              )
            }
          >
            {!result ? (
              <EmptyState
                title="Belum ada response"
                description='Kirim request untuk melihat body dan trace per node.'
                icon={<IconPlay size={22} />}
              />
            ) : (
              <Stack gap={2}>
                <Tabs
                  active="body"
                  onChange={() => undefined}
                  tabs={[
                    { key: 'body', label: 'Body' },
                    { key: 'headers', label: 'Headers' },
                    { key: 'trace', label: `Trace (${result.trace.length})` },
                  ]}
                />
                <CodeBlock code={prettyJson(result.body)} maxHeight="max-h-56" />
                <Panel title="Headers">
                  <CodeBlock code={prettyJson(result.headers)} maxHeight="max-h-28" />
                </Panel>
                <Panel title="Trace">
                  <ul className="space-y-0.5">
                    {result.trace.map((step, index) => (
                      <li
                        key={`${step.nodeId}-${index}`}
                        className="flex items-center gap-2 rounded border border-slate-800 bg-slate-950/40 px-2 py-1"
                      >
                        <Badge tone={traceTone(step.status)} size="xs">
                          {step.status}
                        </Badge>
                        <span className="min-w-0 flex-1 truncate text-[11px] text-slate-300">
                          {step.label}
                        </span>
                        <span className="shrink-0 text-[10px] tabular-nums text-slate-500">
                          {formatDuration(step.durationMs)}
                        </span>
                      </li>
                    ))}
                  </ul>
                </Panel>
              </Stack>
            )}
          </Card>

          <Card title="Test suite" subtitle="Jalankan test case tersimpan">
            {testCases.loading ? (
              <Skeleton lines={3} />
            ) : (testCases.data ?? []).length === 0 ? (
              <EmptyState
                title="Belum ada test case"
                description="Simpan hasil simulasi sebagai test case dari kanvas."
              />
            ) : (
              <ul className="space-y-1">
                {(testCases.data ?? []).map((testCase) => (
                  <li
                    key={testCase.id}
                    className="flex items-center justify-between gap-2 rounded border border-slate-800 bg-slate-950/50 px-2 py-1"
                  >
                    <div className="min-w-0">
                      <p className="truncate text-[11px] text-slate-200">{testCase.name}</p>
                      <p className="truncate font-mono text-[10px] text-slate-500">
                        {testCase.method} {testCase.path}
                      </p>
                    </div>
                    <Button
                      size="xs"
                      variant="ghost"
                      icon={<IconSave size={11} />}
                      onClick={() => void runSavedTest(testCase.id)}
                    >
                      Jalankan
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </Card>

          <Card title="Riwayat run" subtitle="50 hasil terakhir">
            <DataTable
              columns={historyColumns}
              rows={history.data ?? []}
              rowKey={(row) => row.id}
              dense
              emptyMessage="Belum ada riwayat"
            />
          </Card>

          <HintBox tone="info" title="Tips">
            Versi draft dapat diuji tanpa publish. Untuk memanggil lewat gateway, deploy versi ke
            environment terlebih dahulu.
          </HintBox>
        </Stack>
      </div>
    </div>
  );
}
