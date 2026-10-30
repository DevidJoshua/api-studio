import { useState } from 'react';
import { releaseApi } from '../api/endpoints';
import type { Approval } from '../api/types';
import { useAsync } from '../app/hooks';
import { IconCheck, IconRefresh, IconX } from '../app/icons';
import {
  Alert,
  Badge,
  Button,
  Card,
  CodeBlock,
  DataTable,
  EmptyState,
  Field,
  Modal,
  Skeleton,
  Stack,
  Tabs,
  TextArea,
  type Column,
} from '../ui';
import { errorMessage, formatDateTime, prettyJson, timeAgo } from '../lib/format';

/**
 * Halaman approval publish (BRD FR-DEP-02, UC-05 langkah 2).
 *
 * Reviewer melihat diff flow dan kode Java sebelum menyetujui, sehingga
 * keputusan dapat diambil tanpa membandingkan manual.
 */
export function ApprovalsPage({
  onNotify,
}: {
  onNotify: (message: string, tone: 'success' | 'error' | 'info') => void;
}) {
  const [tab, setTab] = useState<'pending' | 'history'>('pending');
  const [selected, setSelected] = useState<Approval | null>(null);
  const [decision, setDecision] = useState<{ approval: Approval; approve: boolean } | null>(null);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);

  const loadPending = () => releaseApi.approvals(false);
  const loadAll = () => releaseApi.approvals(true);
  const pending = useAsync<Approval[]>(loadPending, []);
  const all = useAsync<Approval[]>(loadAll, []);

  const rows = tab === 'pending' ? (pending.data ?? []) : (all.data ?? []);

  const decide = async () => {
    if (!decision) {
      return;
    }
    setBusy(true);
    try {
      await releaseApi.decide(decision.approval.id, decision.approve, note.trim() || undefined);
      onNotify(
        decision.approve ? 'Publish disetujui.' : 'Publish ditolak.',
        decision.approve ? 'success' : 'info',
      );
      setDecision(null);
      setNote('');
      setSelected(null);
      pending.reload();
      all.reload();
    } catch (cause) {
      onNotify(errorMessage(cause, 'Gagal menyimpan keputusan'), 'error');
    } finally {
      setBusy(false);
    }
  };

  const columns: Column<Approval>[] = [
    {
      key: 'versionId',
      header: 'Versi',
      render: (row) => (
        <span className="font-mono text-[10px] text-slate-400">{row.versionId.slice(0, 8)}</span>
      ),
    },
    {
      key: 'environment',
      header: 'Environment',
      render: (row) => (
        <Badge tone={row.environment === 'production' ? 'error' : 'info'} size="xs">
          {row.environment}
        </Badge>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <Badge
          tone={row.status === 'APPROVED' ? 'success' : row.status === 'REJECTED' ? 'error' : 'warning'}
          size="xs"
        >
          {row.status}
        </Badge>
      ),
    },
    {
      key: 'requestedBy',
      header: 'Diminta oleh',
      hideOnMobile: true,
      render: (row) => <span className="text-[11px] text-slate-400">{row.requestedBy ?? '—'}</span>,
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
      render: (row) => (
        <div className="flex justify-end gap-1">
          <Button size="xs" variant="ghost" onClick={() => setSelected(row)}>
            Diff
          </Button>
          {row.status === 'PENDING' && (
            <>
              <Button
                size="xs"
                variant="soft"
                onClick={() => {
                  setDecision({ approval: row, approve: true });
                  setNote('');
                }}
              >
                Setujui
              </Button>
              <Button
                size="xs"
                variant="ghost"
                onClick={() => {
                  setDecision({ approval: row, approve: false });
                  setNote('');
                }}
              >
                Tolak
              </Button>
            </>
          )}
        </div>
      ),
    },
  ];

  return (
    <div className="h-full overflow-y-auto p-4 custom-scrollbar">
      <div className="mb-3 flex flex-wrap items-start justify-between gap-2">
        <div>
          <h2 className="text-base font-bold tracking-tight text-slate-100">Approvals</h2>
          <p className="text-[11px] text-slate-400">
            Tinjau diff flow dan kode sebelum rilis ke environment yang memerlukan persetujuan.
          </p>
        </div>
        <Button
          size="xs"
          variant="ghost"
          icon={<IconRefresh size={12} />}
          onClick={() => {
            pending.reload();
            all.reload();
          }}
        >
          Muat ulang
        </Button>
      </div>

      <Card
        title={tab === 'pending' ? 'Menunggu persetujuan' : 'Riwayat approval'}
        actions={
          <Tabs
            size="xs"
            active={tab}
            onChange={(key) => setTab(key as typeof tab)}
            tabs={[
              { key: 'pending', label: `Pending (${pending.data?.length ?? 0})` },
              { key: 'history', label: 'Riwayat' },
            ]}
          />
        }
      >
        {pending.loading && tab === 'pending' ? (
          <Skeleton lines={4} />
        ) : rows.length === 0 ? (
          <EmptyState
            title={tab === 'pending' ? 'Tidak ada approval menunggu' : 'Belum ada riwayat'}
            description="Pengajuan publish ke environment dengan approval wajib akan tampil di sini."
          />
        ) : (
          <DataTable columns={columns} rows={rows} rowKey={(row) => row.id} />
        )}
      </Card>

      {/* Modal diff */}
      <Modal
        open={!!selected}
        title="Diff perubahan"
        description={
          selected
            ? `${selected.environment} · diminta oleh ${selected.requestedBy ?? '—'}`
            : undefined
        }
        onClose={() => setSelected(null)}
        width="max-w-3xl"
        footer={
          <>
            <Button variant="ghost" size="sm" onClick={() => setSelected(null)}>
              Tutup
            </Button>
            {selected?.status === 'PENDING' && (
              <Button
                variant="solid"
                size="sm"
                onClick={() => {
                  setDecision({ approval: selected, approve: true });
                  setNote('');
                  setSelected(null);
                }}
              >
                Setujui
              </Button>
            )}
          </>
        }
      >
        {selected && (
          <Stack gap={2}>
            {selected.reason && (
              <Alert tone="info" title="Alasan pengajuan">
                {selected.reason}
              </Alert>
            )}
            <div>
              <p className="mb-1 text-[10px] font-semibold uppercase tracking-wider text-slate-500">
                Diff flow
              </p>
              <CodeBlock code={prettyJson(selected.flowDiff)} maxHeight="max-h-64" />
            </div>
            <div>
              <p className="mb-1 text-[10px] font-semibold uppercase tracking-wider text-slate-500">
                Diff kode Java
              </p>
              <CodeBlock code={prettyJson(selected.javaDiff)} maxHeight="max-h-40" />
            </div>
            {selected.status !== 'PENDING' && (
              <Alert tone={selected.status === 'APPROVED' ? 'success' : 'error'}>
                {selected.status} oleh {selected.decidedBy ?? '—'} pada{' '}
                {formatDateTime(selected.decidedAt)}
                {selected.decisionNote ? ` — "${selected.decisionNote}"` : ''}
              </Alert>
            )}
          </Stack>
        )}
      </Modal>

      {/* Modal keputusan */}
      <Modal
        open={!!decision}
        title={decision?.approve ? 'Setujui publish?' : 'Tolak publish?'}
        description={decision ? `Environment ${decision.approval.environment}` : undefined}
        onClose={() => setDecision(null)}
        width="max-w-md"
        footer={
          <>
            <Button variant="ghost" size="sm" onClick={() => setDecision(null)} disabled={busy}>
              Batal
            </Button>
            <Button
              variant={decision?.approve ? 'solid' : 'danger'}
              size="sm"
              loading={busy}
              icon={decision?.approve ? <IconCheck size={12} /> : <IconX size={12} />}
              onClick={decide}
            >
              {decision?.approve ? 'Setujui' : 'Tolak'}
            </Button>
          </>
        }
      >
        <Stack gap={2}>
          <Field label="Catatan (opsional)" hint="Alasan keputusan akan tercatat di audit log.">
            <TextArea rows={3} value={note} onChange={(event) => setNote(event.target.value)} />
          </Field>
          <p className="text-[11px] text-slate-400">
            {decision?.approve
              ? 'Setelah disetujui, developer dapat mempublish versi ini ke environment tersebut.'
              : 'Penolakan membuat versi tetap draft; developer perlu memperbaiki lalu mengajukan ulang.'}
          </p>
        </Stack>
      </Modal>
    </div>
  );
}
