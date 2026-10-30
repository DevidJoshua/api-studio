import { useEffect, useState } from 'react';
import type {
  AssertionOutcome,
  DbSeedRow,
  HttpMockConfig,
  SimulationResult,
  TraceStep,
} from '../../api/types';
import {
  Badge,
  Button,
  CodeBlock,
  EmptyState,
  HintBox,
  JsonEditor,
  Panel,
  Select,
  Stack,
  TextInput,
  Tabs,
} from '../../ui';
import { formatDuration, parseJsonOrThrow, prettyJson } from '../../lib/format';
import { traceTone, httpTone } from '../../ui/types';

/** Payload simulasi yang dikirim ke backend. */
export interface SimulationPayload {
  method: string;
  path: string;
  headers: Record<string, string>;
  body: unknown;
  mocks: HttpMockConfig[];
  seed: DbSeedRow[];
  assertions: { type: string; target?: string; expected?: unknown; operator?: string; sql?: string }[];
}

/**
 * Panel simulasi & trace (BRD FR-SIM-01/02/03).
 *
 * Tiga bagian: input request (method, path, header, body), mock & seed, lalu
 * hasil berisi trace per node yang dapat diklik untuk melihat detail.
 */
export function SimulationPanel({
  running,
  result,
  error,
  defaultMethod,
  defaultPath,
  onRun,
  onSelectTraceNode,
}: {
  running: boolean;
  result: SimulationResult | null;
  error: string | null;
  defaultMethod: string;
  defaultPath: string;
  onRun: (payload: SimulationPayload) => void;
  onSelectTraceNode: (nodeId: string) => void;
}) {
  const [method, setMethod] = useState(defaultMethod);
  const [path, setPath] = useState(defaultPath);
  const [headersText, setHeadersText] = useState('{\n  "Content-Type": "application/json"\n}');
  const [bodyText, setBodyText] = useState('{\n  \n}');
  const [mocksText, setMocksText] = useState('[]');
  const [seedText, setSeedText] = useState('[]');
  const [assertionsText, setAssertionsText] = useState('[]');
  const [jsonError, setJsonError] = useState<string | null>(null);
  const [tab, setTab] = useState<'request' | 'dependencies' | 'assertions'>('request');
  const [selectedStep, setSelectedStep] = useState<TraceStep | null>(null);

  useEffect(() => {
    setMethod(defaultMethod);
    setPath(defaultPath);
  }, [defaultMethod, defaultPath]);

  useEffect(() => {
    setSelectedStep(null);
  }, [result]);

  /** Parse JSON dengan pesan error berlabel; kosong berarti null. */
  const parse = (text: string, label: string): unknown => parseJsonOrThrow(text, label);

  const run = () => {
    setJsonError(null);
    try {
      const headers = (parse(headersText, 'Headers') ?? {}) as Record<string, string>;
      const body = parse(bodyText, 'Body');
      const mocks = (parse(mocksText, 'Mock') ?? []) as HttpMockConfig[];
      const seed = (parse(seedText, 'Seed') ?? []) as DbSeedRow[];
      const assertions = (parse(assertionsText, 'Assertions') ?? []) as SimulationPayload['assertions'];

      onRun({
        method,
        path,
        headers,
        body,
        mocks: Array.isArray(mocks) ? mocks : [],
        seed: Array.isArray(seed) ? seed : [],
        assertions: Array.isArray(assertions) ? assertions : [],
      });
    } catch (cause) {
      setJsonError(cause instanceof Error ? cause.message : 'Input tidak valid');
    }
  };

  const statusTone = result ? httpTone(result.statusCode) : 'default';

  return (
    <div className="flex h-full min-h-0">
      {/* Input */}
      <div className="flex w-[420px] shrink-0 flex-col border-r border-slate-800">
        <div className="flex items-center justify-between gap-2 border-b border-slate-800 px-2 py-1.5">
          <Tabs
            size="xs"
            active={tab}
            onChange={(key) => setTab(key as typeof tab)}
            tabs={[
              { key: 'request', label: 'Request' },
              { key: 'dependencies', label: 'Mock & Seed' },
              { key: 'assertions', label: 'Assertion' },
            ]}
          />
        </div>

        <div className="min-h-0 flex-1 space-y-2 overflow-y-auto p-2 custom-scrollbar">
          {tab === 'request' && (
            <>
              <div className="flex gap-1.5">
                <div className="w-24 shrink-0">
                  <Select
                    value={method}
                    onChange={(event) => setMethod(event.target.value)}
                    options={['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((value) => ({
                      value,
                      label: value,
                    }))}
                  />
                </div>
                <TextInput
                  value={path}
                  onChange={(event) => setPath(event.target.value)}
                  className="font-mono"
                  placeholder="/path"
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
            </>
          )}

          {tab === 'dependencies' && (
            <>
              <JsonEditor
                title="Mock HTTP"
                value={parseJsonOrThrow(mocksText, 'Mock') ?? []}
                onChange={(value) => setMocksText(value === null ? '' : JSON.stringify(value, null, 2))}
                placeholder={'[\n  { "urlPattern": "https://api.x/**", "status": 200, "body": {} }\n]'}
                height="7rem"
              />
              <JsonEditor
                title="Seed database"
                value={parseJsonOrThrow(seedText, 'Seed') ?? []}
                onChange={(value) => setSeedText(value === null ? '' : JSON.stringify(value, null, 2))}
                placeholder={'[\n  { "table": "account", "rows": [{ "account_no": "123" }] }\n]'}
                height="7rem"
              />
            </>
          )}

          {tab === 'assertions' && (
            <JsonEditor
              title="Assertion"
              value={parseJsonOrThrow(assertionsText, 'Assertions') ?? []}
              onChange={(value) => setAssertionsText(value === null ? '' : JSON.stringify(value, null, 2))}
              placeholder={'[\n  { "type": "response.status", "expected": 200, "operator": "equals" }\n]'}
              height="12rem"
            />
          )}
        </div>

        <div className="space-y-1.5 border-t border-slate-800 p-2">
          {jsonError && (
            <p className="text-[11px] text-rose-400">{jsonError}</p>
          )}
          {error && <p className="text-[11px] text-rose-400">{error}</p>}
          <Button variant="solid" size="sm" block loading={running} onClick={run}>
            Jalankan simulasi
          </Button>
        </div>
      </div>

      {/* Hasil */}
      <div className="flex min-w-0 flex-1 flex-col">
        {!result ? (
          <div className="grid h-full place-items-center p-4">
            <EmptyState
              title="Belum ada hasil"
              description='Isi request lalu klik "Jalankan simulasi" untuk melihat trace per node.'
            />
          </div>
        ) : (
          <>
            <div className="flex flex-wrap items-center gap-3 border-b border-slate-800 px-3 py-1.5">
              <Badge tone={statusTone} size="sm">
                HTTP {result.statusCode}
              </Badge>
              <span className="text-[11px] text-slate-400">
                Durasi <strong className="text-slate-200">{formatDuration(result.durationMs)}</strong>
              </span>
              <span className="text-[11px] text-slate-400">
                Node <strong className="text-slate-200">{result.trace.length}</strong>
              </span>
              {result.assertions.length > 0 && (
                <span className="text-[11px] text-slate-400">
                  Assertion{' '}
                  <strong className={result.failed > 0 ? 'text-rose-400' : 'text-emerald-400'}>
                    {result.passed}/{result.assertions.length}
                  </strong>
                </span>
              )}
              <span className="ml-auto font-mono text-[10px] text-slate-600">{result.traceId}</span>
            </div>

            <div className="grid min-h-0 flex-1 grid-cols-1 lg:grid-cols-[1fr_1fr]">
              {/* trace */}
              <div className="min-h-0 overflow-y-auto border-r border-slate-800 p-2 custom-scrollbar">
                <p className="mb-1 text-[10px] font-semibold uppercase tracking-wider text-slate-500">
                  Trace per node
                </p>
                <ol className="space-y-1">
                  {result.trace.map((step, index) => (
                    <li key={`${step.nodeId}-${index}`}>
                      <button
                        type="button"
                        onClick={() => {
                          setSelectedStep(step);
                          onSelectTraceNode(step.nodeId);
                        }}
                        className={`flex w-full items-center gap-2 rounded border px-2 py-1 text-left transition-colors ${
                          selectedStep === step
                            ? 'border-indigo-500/50 bg-indigo-500/10'
                            : 'border-slate-800 bg-slate-950/40 hover:bg-slate-800/40'
                        }`}
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
                      </button>
                    </li>
                  ))}
                </ol>

                {result.assertions.length > 0 && (
                  <div className="mt-3">
                    <p className="mb-1 text-[10px] font-semibold uppercase tracking-wider text-slate-500">
                      Assertion
                    </p>
                    <ul className="space-y-1">
                      {result.assertions.map((assertion, index) => (
                        <AssertionRow key={index} assertion={assertion} />
                      ))}
                    </ul>
                  </div>
                )}
              </div>

              {/* detail */}
              <div className="min-h-0 overflow-y-auto p-2 custom-scrollbar">
                {selectedStep ? (
                  <Stack gap={2}>
                    <Panel title={`${selectedStep.label} · ${selectedStep.nodeType}`}>
                      <div className="space-y-1.5">
                        <div className="flex items-center gap-2">
                          <Badge tone={traceTone(selectedStep.status)} size="xs">
                            {selectedStep.status}
                          </Badge>
                          <span className="text-[11px] text-slate-400">
                            {formatDuration(selectedStep.durationMs)}
                          </span>
                          {selectedStep.port && (
                            <span className="text-[11px] text-slate-500">
                              port: {selectedStep.port}
                            </span>
                          )}
                          {typeof selectedStep.attempt === 'number' && selectedStep.attempt > 1 && (
                            <span className="text-[11px] text-amber-400">
                              percobaan {selectedStep.attempt}
                            </span>
                          )}
                        </div>
                        {selectedStep.error && (
                          <p className="rounded border border-rose-800/60 bg-rose-950/40 px-2 py-1 text-[11px] text-rose-300">
                            {selectedStep.error}
                          </p>
                        )}
                      </div>
                    </Panel>

                    <div>
                      <p className="mb-1 text-[10px] font-semibold uppercase tracking-wider text-slate-500">
                        Input
                      </p>
                      <CodeBlock code={prettyJson(selectedStep.input)} maxHeight="max-h-40" />
                    </div>
                    <div>
                      <p className="mb-1 text-[10px] font-semibold uppercase tracking-wider text-slate-500">
                        Output
                      </p>
                      <CodeBlock code={prettyJson(selectedStep.output)} maxHeight="max-h-56" />
                    </div>
                    {selectedStep.logs && selectedStep.logs.length > 0 && (
                      <div>
                        <p className="mb-1 text-[10px] font-semibold uppercase tracking-wider text-slate-500">
                          Log
                        </p>
                        <ul className="space-y-0.5 font-mono text-[10px] text-slate-400">
                          {selectedStep.logs.map((log, index) => (
                            <li key={index}>· {log}</li>
                          ))}
                        </ul>
                      </div>
                    )}
                  </Stack>
                ) : (
                  <Stack gap={2}>
                    <div>
                      <p className="mb-1 text-[10px] font-semibold uppercase tracking-wider text-slate-500">
                        Response body
                      </p>
                      <CodeBlock code={prettyJson(result.body)} maxHeight="max-h-64" />
                    </div>
                    <div>
                      <p className="mb-1 text-[10px] font-semibold uppercase tracking-wider text-slate-500">
                        Headers
                      </p>
                      <CodeBlock code={prettyJson(result.headers)} maxHeight="max-h-32" />
                    </div>
                    {result.logs.length > 0 && (
                      <HintBox tone="info" title="Log flow">
                        <ul className="space-y-0.5 font-mono">
                          {result.logs.map((log, index) => (
                            <li key={index}>· {log}</li>
                          ))}
                        </ul>
                      </HintBox>
                    )}
                  </Stack>
                )}
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function AssertionRow({ assertion }: { assertion: AssertionOutcome }) {
  const [expanded, setExpanded] = useState(false);
  return (
    <li>
      <button
        type="button"
        onClick={() => setExpanded((value) => !value)}
        className={`flex w-full items-center gap-2 rounded border px-2 py-1 text-left ${
          assertion.passed
            ? 'border-emerald-900/60 bg-emerald-950/20'
            : 'border-rose-900/60 bg-rose-950/20'
        }`}
      >
        <Badge tone={assertion.passed ? 'success' : 'error'} size="xs">
          {assertion.passed ? 'lulus' : 'gagal'}
        </Badge>
        <span className="min-w-0 flex-1 truncate font-mono text-[10px] text-slate-300">
          {assertion.type}
          {assertion.target ? ` · ${assertion.target}` : ''}
        </span>
      </button>
      {expanded && (
        <div className="mt-1 space-y-1 rounded border border-slate-800 bg-slate-950/60 px-2 py-1.5">
          <p className="text-[10px] text-slate-400">{assertion.message}</p>
          <div className="grid grid-cols-2 gap-1 font-mono text-[10px]">
            <span className="text-slate-500">harapan</span>
            <span className="text-slate-300">{prettyJson(assertion.expected, '—')}</span>
            <span className="text-slate-500">aktual</span>
            <span className="text-slate-300">{prettyJson(assertion.actual, '—')}</span>
          </div>
        </div>
      )}
    </li>
  );
}
