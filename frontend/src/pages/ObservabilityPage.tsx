import { useCallback, useMemo, useState } from 'react';
import { adminApi } from '../api/endpoints';
import type {
  AlertInfo,
  MetricsSeries,
  RequestLogEntry,
  TraceStep,
} from '../api/types';
import { useAsync } from '../app/hooks';
import { IconChart, IconRefresh } from '../app/icons';
import {
  Badge,
  Button,
  Card,
  CodeBlock,
  DataTable,
  EmptyState,
  Grid,
  Modal,
  Panel,
  Select,
  Skeleton,
  Stack,
  StatCard,
  Tabs,
  type Column,
} from '../ui';
import { formatDateTime, formatDuration, formatNumber, prettyJson, timeAgo } from '../lib/format';
import { httpTone, traceTone } from '../ui/types';

type ObservabilityTab = 'metrics' | 'logs' | 'alerts';

/**
 * Observability: metrik, log request, dan alert (BRD FR-SEC-05/06).
 *
 * Grafik digambar manual dengan SVG (tanpa library chart) agar ringan dan
 * tidak menambah dependensi.
 */
export function ObservabilityPage({
  onNotify,
}: {
  onNotify: (message: string, tone: 'success' | 'error' | 'info') => void;
}) {
  const [tab, setTab] = useState<ObservabilityTab>('metrics');
  const [environment, setEnvironment] = useState('');
  const [minutes, setMinutes] = useState('60');
  const [traceId, setTraceId] = useState<string | null>(null);

  const loadMetrics = useCallback(
    () => adminApi.metrics({ environment: environment || undefined, minutes: Number(minutes) || 60 }),
    [environment, minutes],
  );
  const metrics = useAsync<MetricsSeries>(loadMetrics, [environment, minutes]);

  const loadLogs = () => adminApi.logs();
  const logs = useAsync<RequestLogEntry[]>(loadLogs, []);

  const loadAlerts = () => adminApi.alerts();
  const alerts = useAsync<AlertInfo[]>(loadAlerts, []);

  const [trace, setTrace] = useState<TraceStep[] | null>(null);
  const openTrace = async (id: string) => {
    setTraceId(id);
    try {
      const detail = await adminApi.trace(id);
      setTrace((detail.trace as TraceStep[]) ?? []);
    } catch (cause) {
      onNotify(cause instanceof Error ? cause.message : 'Gagal memuat trace', 'error');
      setTrace(null);
    }
  };

  const summary = metrics.data?.summary;

  const logColumns: Column<RequestLogEntry>[] = [
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <Badge tone={httpTone(row.status)} size="xs">
          {row.status}
        </Badge>
      ),
    },
    {
      key: 'method',
      header: 'Method',
      render: (row) => (
        <span className="font-mono text-[10px] text-slate-400">{row.method}</span>
      ),
    },
    {
      key: 'path',
      header: 'Path',
      render: (row) => (
        <span className="block max-w-[220px] truncate font-mono text-[11px] text-slate-300">
          {row.path}
        </span>
      ),
    },
    {
      key: 'environment',
      header: 'Env',
      hideOnMobile: true,
      render: (row) => (
        <span className="text-[10px] uppercase text-slate-500">{row.environment}</span>
      ),
    },
    {
      key: 'latencyMs',
      header: 'Latensi',
      hideOnMobile: true,
      render: (row) => (
        <span className="tabular-nums text-[11px] text-slate-300">{row.latencyMs} ms</span>
      ),
    },
    {
      key: 'createdAt',
      header: 'Waktu',
      hideOnMobile: true,
      render: (row) => <span className="text-[10px] text-slate-500">{timeAgo(row.createdAt)}</span>,
    },
    {
      key: 'action',
      header: '',
      render: (row) =>
        row.traceId ? (
          <Button size="xs" variant="ghost" onClick={() => void openTrace(row.traceId!)}>
            Trace
          </Button>
        ) : null,
    },
  ];

  return (
    <div className="h-full overflow-y-auto p-4 custom-scrollbar">
      <div className="mb-3 flex flex-wrap items-start justify-between gap-2">
        <div>
          <h2 className="text-base font-bold tracking-tight text-slate-100">Observability</h2>
          <p className="text-[11px] text-slate-400">
            Throughput, error rate, latensi, dan telusur request per node.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-1.5">
          <div className="w-32">
            <Select
              value={environment}
              onChange={(event) => setEnvironment(event.target.value)}
              options={[
                { value: '', label: 'Semua env' },
                { value: 'dev', label: 'dev' },
                { value: 'staging', label: 'staging' },
                { value: 'production', label: 'production' },
              ]}
            />
          </div>
          <div className="w-28">
            <Select
              value={minutes}
              onChange={(event) => setMinutes(event.target.value)}
              options={[
                { value: '15', label: '15 menit' },
                { value: '60', label: '1 jam' },
                { value: '360', label: '6 jam' },
                { value: '1440', label: '24 jam' },
              ]}
            />
          </div>
          <Button
            size="xs"
            variant="ghost"
            icon={<IconRefresh size={12} />}
            onClick={() => {
              metrics.reload();
              logs.reload();
              alerts.reload();
            }}
          >
            Muat ulang
          </Button>
        </div>
      </div>

      <Grid cols={4} gap={2}>
        <StatCard
          label="Total request"
          value={formatNumber(summary?.totalRequests)}
          hint={`${minutes} menit terakhir`}
          tone="info"
        />
        <StatCard
          label="Error rate"
          value={`${summary?.errorRate ?? 0}%`}
          hint={`${formatNumber(summary?.totalErrors)} error`}
          tone={(summary?.errorRate ?? 0) > 5 ? 'error' : 'success'}
        />
        <StatCard
          label="Latensi rata-rata"
          value={`${summary?.avgLatencyMs ?? 0} ms`}
          tone="primary"
        />
        <StatCard
          label="Latensi maks"
          value={`${summary?.maxLatencyMs ?? 0} ms`}
          tone={(summary?.maxLatencyMs ?? 0) > 2000 ? 'warning' : 'default'}
        />
      </Grid>

      <div className="mt-3">
        <Tabs
          active={tab}
          onChange={(key) => setTab(key as ObservabilityTab)}
          tabs={[
            { key: 'metrics', label: 'Metrik' },
            { key: 'logs', label: `Log (${logs.data?.length ?? 0})` },
            {
              key: 'alerts',
              label: 'Alert',
              badge: (alerts.data?.length ?? 0) > 0 ? (
                <Badge tone="warning" size="xs">
                  {alerts.data?.length}
                </Badge>
              ) : undefined,
            },
          ]}
        />

        <div className="mt-2">
          {tab === 'metrics' && (
            <Card title="Seri waktu" subtitle="Agregasi per menit">
              {metrics.loading ? (
                <Skeleton lines={6} />
              ) : (metrics.data?.points ?? []).length === 0 ? (
                <EmptyState
                  title="Belum ada metrik"
                  description="Panggil API lewat gateway untuk menghasilkan metrik."
                  icon={<IconChart size={22} />}
                />
              ) : (
                <Stack gap={3}>
                  <MetricsChart series={metrics.data!} />
                  <Panel title="Detail per bucket">
                    <div className="max-h-56 overflow-y-auto custom-scrollbar">
                      <table className="w-full text-left">
                        <thead>
                          <tr className="border-b border-slate-800">
                            {['Waktu', 'Request', 'Error', 'Avg', 'Max', 'Error %'].map((header) => (
                              <th
                                key={header}
                                className="px-2 py-1 text-[10px] uppercase tracking-wider text-slate-500"
                              >
                                {header}
                              </th>
                            ))}
                          </tr>
                        </thead>
                        <tbody>
                          {metrics.data!.points.map((point, index) => (
                            <tr key={index} className="border-b border-slate-800/60 text-[11px]">
                              <td className="px-2 py-1 text-slate-400">
                                {formatDateTime(point.bucket)}
                              </td>
                              <td className="px-2 py-1 tabular-nums text-slate-300">
                                {point.requests}
                              </td>
                              <td className="px-2 py-1 tabular-nums text-slate-300">{point.errors}</td>
                              <td className="px-2 py-1 tabular-nums text-slate-300">
                                {point.avgLatencyMs} ms
                              </td>
                              <td className="px-2 py-1 tabular-nums text-slate-300">
                                {point.maxLatencyMs} ms
                              </td>
                              <td className="px-2 py-1 tabular-nums text-slate-300">
                                {point.errorRate}%
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </Panel>
                </Stack>
              )}
            </Card>
          )}

          {tab === 'logs' && (
            <Card title="Log request" subtitle="100 request terakhir">
              {logs.loading ? (
                <Skeleton lines={5} />
              ) : (
                <DataTable
                  columns={logColumns}
                  rows={logs.data ?? []}
                  rowKey={(row) => row.id}
                  dense
                  emptyMessage="Belum ada log request"
                />
              )}
            </Card>
          )}

          {tab === 'alerts' && (
            <Card title="Alert" subtitle="Dihitung dari ambang error rate dan latensi">
              {alerts.loading ? (
                <Skeleton lines={3} />
              ) : (alerts.data ?? []).length === 0 ? (
                <EmptyState
                  title="Tidak ada alert"
                  description="Semua metrik berada di bawah ambang."
                />
              ) : (
                <ul className="space-y-1">
                  {(alerts.data ?? []).map((alert, index) => (
                    <li
                      key={index}
                      className={`flex items-start gap-2 rounded border px-2 py-1.5 ${
                        alert.severity === 'HIGH'
                          ? 'border-rose-900/60 bg-rose-950/30'
                          : 'border-amber-900/60 bg-amber-950/20'
                      }`}
                    >
                      <Badge tone={alert.severity === 'HIGH' ? 'error' : 'warning'} size="xs">
                        {alert.severity}
                      </Badge>
                      <div className="min-w-0">
                        <p className="text-[11px] text-slate-200">{alert.message}</p>
                        <p className="text-[10px] text-slate-500">
                          {alert.type} · {alert.environment ?? 'semua env'}
                        </p>
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </Card>
          )}
        </div>
      </div>

      {/* Modal trace */}
      <Modal
        open={!!traceId}
        title="Trace request"
        description={traceId ?? undefined}
        onClose={() => {
          setTraceId(null);
          setTrace(null);
        }}
        width="max-w-2xl"
      >
        {trace === null ? (
          <Skeleton lines={5} />
        ) : trace.length === 0 ? (
          <EmptyState title="Trace tidak ditemukan" />
        ) : (
          <Stack gap={1}>
            {trace.map((step, index) => (
              <details
                key={`${step.nodeId}-${index}`}
                className="rounded border border-slate-800 bg-slate-950/50 px-2 py-1"
              >
                <summary className="flex cursor-pointer items-center gap-2">
                  <Badge tone={traceTone(step.status)} size="xs">
                    {step.status}
                  </Badge>
                  <span className="min-w-0 flex-1 truncate text-[11px] text-slate-300">
                    {step.label}
                  </span>
                  <span className="text-[10px] tabular-nums text-slate-500">
                    {formatDuration(step.durationMs)}
                  </span>
                </summary>
                <div className="mt-1 space-y-1">
                  {step.error && <p className="text-[10px] text-rose-400">{step.error}</p>}
                  <CodeBlock code={prettyJson(step.input)} language="input" maxHeight="max-h-28" />
                  <CodeBlock code={prettyJson(step.output)} language="output" maxHeight="max-h-40" />
                </div>
              </details>
            ))}
          </Stack>
        )}
      </Modal>
    </div>
  );
}

/**
 * Grafik SVG sederhana: batang request per bucket dan garis error rate.
 *
 * Dibuat manual agar tidak menambah dependensi chart dan tetap ringan.
 */
function MetricsChart({ series }: { series: MetricsSeries }) {
  const points = series.points.slice(-40);
  const maxRequests = Math.max(1, ...points.map((point) => point.requests));
  const width = 720;
  const height = 160;
  const barWidth = points.length > 0 ? width / points.length : width;

  const errorPath = useMemo(() => {
    if (points.length === 0) {
      return '';
    }
    const maxError = Math.max(1, ...points.map((point) => point.errorRate));
    return points
      .map((point, index) => {
        const x = index * barWidth + barWidth / 2;
        const y = height - (point.errorRate / maxError) * (height - 10);
        return `${index === 0 ? 'M' : 'L'} ${x.toFixed(1)} ${y.toFixed(1)}`;
      })
      .join(' ');
  }, [points, barWidth, height]);

  return (
    <div className="rounded-lg border border-slate-800 bg-slate-950/60 p-2">
      <svg viewBox={`0 0 ${width} ${height}`} className="w-full" role="img" aria-label="Grafik metrik">
        {points.map((point, index) => {
          const barHeight = (point.requests / maxRequests) * (height - 10);
          return (
            <rect
              key={index}
              x={index * barWidth + 1}
              y={height - barHeight}
              width={Math.max(1, barWidth - 2)}
              height={barHeight}
              fill={point.errors > 0 ? '#f43f5e' : '#6366f1'}
              opacity={0.75}
            />
          );
        })}
        {errorPath && (
          <path d={errorPath} fill="none" stroke="#fbbf24" strokeWidth={1.5} opacity={0.9} />
        )}
      </svg>
      <div className="mt-1 flex items-center justify-between text-[10px] text-slate-500">
        <span>{points.length} bucket</span>
        <span className="flex items-center gap-2">
          <span className="flex items-center gap-1">
            <span className="h-1.5 w-3 rounded bg-indigo-500" /> request
          </span>
          <span className="flex items-center gap-1">
            <span className="h-1.5 w-3 rounded bg-rose-500" /> ada error
          </span>
          <span className="flex items-center gap-1">
            <span className="h-1.5 w-3 rounded bg-amber-400" /> error rate
          </span>
        </span>
      </div>
    </div>
  );
}
