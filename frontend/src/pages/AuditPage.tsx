import { useCallback, useMemo, useState } from 'react';
import { adminApi } from '../api/endpoints';
import type { AuditLogEntry } from '../api/types';
import { useAsync } from '../app/hooks';
import { IconAudit, IconRefresh } from '../app/icons';
import {
  Badge,
  Button,
  Card,
  CodeBlock,
  DataTable,
  EmptyState,
  Field,
  Modal,
  SearchInput,
  Select,
  Skeleton,
  Stack,
  StatCard,
  type Column,
} from '../ui';
import { formatDateTime, prettyJson, timeAgo } from '../lib/format';

const ENTITY_TYPES = [
  'Api',
  'ApiGroup',
  'ApiVersion',
  'ReleaseBundle',
  'Deployment',
  'Approval',
  'FunctionTemplate',
  'TemplateVersion',
  'TestCase',
  'SecretRef',
  'Consumer',
  'AppUser',
  'Environment',
];

/**
 * Audit log (BRD FR-SEC-03, NFR-07).
 *
 * Menampilkan seluruh perubahan beserta aktor dan diff, dapat difilter per
 * jenis entitas dan dicari berdasarkan aksi atau pelaku.
 */
export function AuditPage() {
  const [entityType, setEntityType] = useState('');
  const [search, setSearch] = useState('');
  const [selected, setSelected] = useState<AuditLogEntry | null>(null);

  const load = useCallback(
    () => adminApi.audit(entityType ? { entityType } : undefined),
    [entityType],
  );
  const logs = useAsync<AuditLogEntry[]>(load, [entityType]);

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase();
    if (!term) {
      return logs.data ?? [];
    }
    return (logs.data ?? []).filter((entry) =>
      `${entry.action} ${entry.actor ?? ''} ${entry.summary ?? ''} ${entry.entityType}`
        .toLowerCase()
        .includes(term),
    );
  }, [logs.data, search]);

  const actorCount = useMemo(
    () => new Set((logs.data ?? []).map((entry) => entry.actor ?? 'system')).size,
    [logs.data],
  );

  const columns: Column<AuditLogEntry>[] = [
    {
      key: 'action',
      header: 'Aksi',
      render: (row) => (
        <span className="whitespace-nowrap font-mono text-[10px] text-indigo-300">{row.action}</span>
      ),
    },
    {
      key: 'entityType',
      header: 'Entitas',
      render: (row) => (
        <span className="text-[11px] text-slate-300">
          {row.entityType}
          {row.entityId ? (
            <span className="ml-1 font-mono text-[10px] text-slate-500">
              {row.entityId.slice(0, 8)}
            </span>
          ) : null}
        </span>
      ),
    },
    {
      key: 'summary',
      header: 'Ringkasan',
      render: (row) => (
        <span className="block max-w-[280px] truncate text-[11px] text-slate-400">
          {row.summary ?? '—'}
        </span>
      ),
    },
    {
      key: 'actor',
      header: 'Aktor',
      hideOnMobile: true,
      render: (row) => (
        <span className="text-[11px] text-slate-300">
          {row.actor ?? 'system'}
          {row.actorRole ? (
            <span className="ml-1 text-[10px] uppercase text-slate-500">{row.actorRole}</span>
          ) : null}
        </span>
      ),
    },
    {
      key: 'createdAt',
      header: 'Waktu',
      hideOnMobile: true,
      render: (row) => <span className="text-[10px] text-slate-500">{timeAgo(row.createdAt)}</span>,
    },
    {
      key: 'action2',
      header: '',
      render: (row) =>
        row.diff ? (
          <Button size="xs" variant="ghost" onClick={() => setSelected(row)}>
            Diff
          </Button>
        ) : null,
    },
  ];

  return (
    <div className="h-full overflow-y-auto p-4 custom-scrollbar">
      <div className="mb-3 flex flex-wrap items-start justify-between gap-2">
        <div>
          <h2 className="text-base font-bold tracking-tight text-slate-100">Audit Log</h2>
          <p className="text-[11px] text-slate-400">
            Seluruh perubahan konfigurasi tercatat: siapa, kapan, dan apa yang berubah.
          </p>
        </div>
        <Button
          size="xs"
          variant="ghost"
          icon={<IconRefresh size={12} />}
          onClick={() => logs.reload()}
          loading={logs.loading}
        >
          Muat ulang
        </Button>
      </div>

      <div className="mb-3 grid grid-cols-2 gap-2 md:grid-cols-4">
        <StatCard label="Entri" value={logs.data?.length ?? 0} hint="200 terakhir" tone="info" />
        <StatCard label="Aktor unik" value={actorCount} tone="primary" />
        <StatCard
          label="Aksi terbanyak"
          value={mostCommonAction(logs.data ?? [])}
          tone="success"
        />
        <StatCard
          label="Filter"
          value={entityType || 'semua'}
          hint="jenis entitas"
          tone="muted"
        />
      </div>

      <Card
        title="Riwayat perubahan"
        actions={
          <div className="flex items-center gap-1.5">
            <div className="w-40">
              <Select
                value={entityType}
                onChange={(event) => setEntityType(event.target.value)}
                options={[
                  { value: '', label: 'Semua entitas' },
                  ...ENTITY_TYPES.map((type) => ({ value: type, label: type })),
                ]}
              />
            </div>
            <div className="w-48">
              <SearchInput
                value={search}
                onChange={setSearch}
                placeholder="Cari aksi/aktor..."
                ariaLabel="Cari audit"
              />
            </div>
          </div>
        }
      >
        {logs.loading ? (
          <Skeleton lines={6} />
        ) : filtered.length === 0 ? (
          <EmptyState
            title="Belum ada catatan audit"
            description="Aktivitas perubahan akan tercatat di sini."
            icon={<IconAudit size={22} />}
          />
        ) : (
          <DataTable
            columns={columns}
            rows={filtered}
            rowKey={(row) => row.id}
            dense
            onRowClick={(row) => (row.diff ? setSelected(row) : undefined)}
          />
        )}
      </Card>

      <Modal
        open={!!selected}
        title={selected?.action ?? 'Detail audit'}
        description={selected ? `${selected.entityType} · ${formatDateTime(selected.createdAt)}` : undefined}
        onClose={() => setSelected(null)}
        width="max-w-2xl"
      >
        {selected && (
          <Stack gap={2}>
            <div className="rounded border border-slate-800 bg-slate-950/50 px-2 py-1.5">
              <p className="text-[11px] text-slate-300">{selected.summary ?? '—'}</p>
              <p className="mt-0.5 text-[10px] text-slate-500">
                oleh {selected.actor ?? 'system'}
                {selected.actorRole ? ` (${selected.actorRole})` : ''}
              </p>
            </div>
            <Field label="Diff">
              <CodeBlock code={prettyJson(selected.diff)} maxHeight="max-h-80" wrap />
            </Field>
            {selected.entityId && (
              <Badge tone="muted" size="xs">
                id: {selected.entityId}
              </Badge>
            )}
          </Stack>
        )}
      </Modal>
    </div>
  );
}

/** Aksi yang paling sering muncul (ringkasan cepat). */
function mostCommonAction(entries: AuditLogEntry[]): string {
  if (entries.length === 0) {
    return '—';
  }
  const counts = new Map<string, number>();
  entries.forEach((entry) => counts.set(entry.action, (counts.get(entry.action) ?? 0) + 1));
  let best = '—';
  let bestCount = 0;
  counts.forEach((count, action) => {
    if (count > bestCount) {
      best = action;
      bestCount = count;
    }
  });
  return best;
}
