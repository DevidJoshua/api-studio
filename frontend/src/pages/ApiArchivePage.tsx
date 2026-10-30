import { useCallback, useMemo, useState } from 'react';
import { apiApi, groupApi } from '../api/endpoints';
import type { ApiDetail, ApiGroup, ApiSummary, HttpMethod } from '../api/types';
import { useAsync } from '../app/hooks';
import { ArrowLink, FolderButton, SidebarGroup, SidebarHint } from '../app/AppShell';
import {
  IconLayers,
  IconPlus,
  IconSearch,
  IconShield,
  IconZap,
} from '../app/icons';
import {
  Badge,
  Button,
  Card,
  ConfirmDialog,
  EmptyState,
  Field,
  Modal,
  SearchInput,
  Select,
  Skeleton,
  TextArea,
  TextInput,
} from '../ui';
import { errorMessage, timeAgo, truncate } from '../lib/format';
import { methodTone, versionTone } from '../ui/types';

const METHOD_OPTIONS: HttpMethod[] = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];

/**
 * Arsip API: daftar endpoint, filter berdasarkan grup (proyek) dan status,
 * serta pembuatan API baru.
 */
export function ApiArchivePage({
  selectedGroupId,
  onSelectGroup,
  onOpenApi,
  onNotify,
}: {
  selectedGroupId: string | null;
  onSelectGroup: (groupId: string | null) => void;
  onOpenApi: (apiId: string) => void;
  onNotify: (message: string, tone: 'success' | 'error' | 'info') => void;
}) {
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'PUBLISHED' | 'DRAFT'>('ALL');
  const [createOpen, setCreateOpen] = useState(false);
  const [groupModalOpen, setGroupModalOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<ApiSummary | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const loadGroups = () => groupApi.list();
  const loadApis = () => apiApi.list();
  const groups = useAsync<ApiGroup[]>(loadGroups, []);
  const apis = useAsync<ApiSummary[]>(loadApis, []);

  /** Detail version untuk menentukan status tiap API (dipakai filter). */
  const loadDetails = useCallback(async () => {
    const list = apis.data ?? [];
    const results = await Promise.all(
      list.map(async (api) => {
        try {
          return { id: api.id, detail: await apiApi.detail(api.id) };
        } catch {
          return { id: api.id, detail: null as ApiDetail | null };
        }
      }),
    );
    const map: Record<string, ApiDetail | null> = {};
    results.forEach((item) => {
      map[item.id] = item.detail;
    });
    return map;
  }, [apis.data]);

  const details = useAsync<Record<string, ApiDetail | null>>(loadDetails, [apis.data]);

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase();
    return (apis.data ?? []).filter((api) => {
      if (selectedGroupId && api.groupId !== selectedGroupId) {
        return false;
      }
      if (term && !`${api.name} ${api.path} ${api.tags ?? ''}`.toLowerCase().includes(term)) {
        return false;
      }
      if (statusFilter !== 'ALL') {
        const detail = details.data?.[api.id];
        const status = detail?.latestStatus ?? 'DRAFT';
        if (status !== statusFilter) {
          return false;
        }
      }
      return true;
    });
  }, [apis.data, details.data, search, selectedGroupId, statusFilter]);

  const reload = () => {
    apis.reload();
    groups.reload();
  };

  return (
    <div className="flex h-full min-h-0">
      {/* Sidebar proyek */}
      <aside className="hidden w-60 shrink-0 flex-col overflow-y-auto border-r border-slate-800 bg-slate-900/40 p-3 custom-scrollbar lg:flex">
        <SidebarGroup
          title="Projects / Folders"
          action={
            <button
              type="button"
              onClick={() => setGroupModalOpen(true)}
              title="Tambah grup"
              aria-label="Tambah grup"
              className="rounded p-0.5 text-slate-500 hover:bg-slate-800 hover:text-slate-200"
            >
              <IconPlus size={13} />
            </button>
          }
        >
          <FolderButton
            label="Semua Proyek"
            count={apis.data?.length ?? 0}
            active={selectedGroupId === null}
            onClick={() => onSelectGroup(null)}
          />
          <div className="mt-1 space-y-0.5">
            {(groups.data ?? []).map((group) => (
              <FolderButton
                key={group.id}
                label={group.name}
                count={group.apiCount ?? (apis.data ?? []).filter((api) => api.groupId === group.id).length}
                active={selectedGroupId === group.id}
                onClick={() => onSelectGroup(group.id)}
              />
            ))}
          </div>
        </SidebarGroup>

        <div className="mt-auto pt-3">
          <SidebarHint icon={<IconShield size={12} />} title="Siap Spring Boot">
            Kelompokkan API ke microservice dan ekspor definisi untuk arsitektur Java.
          </SidebarHint>
        </div>
      </aside>

      {/* Daftar API */}
      <div className="flex min-h-0 flex-1 flex-col overflow-hidden p-4">
        <div className="mb-3 flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="text-base font-bold tracking-tight text-slate-100">
              API Archive &amp; Management
            </h2>
            <p className="text-[11px] text-slate-400">
              Kelola endpoint, kelompokkan per proyek, dan buka kanvas untuk mengedit flow.
            </p>
          </div>
          <Button variant="solid" size="sm" icon={<IconPlus size={14} />} onClick={() => setCreateOpen(true)}>
            Buat API
          </Button>
        </div>

        {/* Filter */}
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2 rounded-lg border border-slate-800 bg-slate-900/60 px-3 py-2">
          <div className="flex flex-1 flex-wrap items-center gap-2">
            <div className="w-full max-w-xs">
              <SearchInput
                value={search}
                onChange={setSearch}
                placeholder="Filter API berdasarkan nama atau path..."
                ariaLabel="Cari API"
              />
            </div>
            <div className="flex items-center gap-1 rounded-md border border-slate-800 bg-slate-950 p-1">
              {(['ALL', 'PUBLISHED', 'DRAFT'] as const).map((status) => (
                <button
                  key={status}
                  type="button"
                  onClick={() => setStatusFilter(status)}
                  className={`rounded px-2 py-0.5 text-[10px] font-medium transition-colors ${
                    statusFilter === status
                      ? 'bg-slate-800 text-slate-100'
                      : 'text-slate-400 hover:text-slate-200'
                  }`}
                >
                  {status === 'ALL' ? 'Semua' : status === 'PUBLISHED' ? 'Aktif' : 'Draft'}
                </button>
              ))}
            </div>
          </div>
          <div className="flex items-center gap-2 font-mono text-[10px] text-slate-500">
            <span>
              Menampilkan <strong className="text-slate-300">{filtered.length}</strong> endpoint
            </span>
            <button
              type="button"
              onClick={reload}
              className="rounded p-1 text-slate-500 hover:bg-slate-800 hover:text-slate-200"
              title="Muat ulang"
              aria-label="Muat ulang"
            >
              <IconSearch size={12} />
            </button>
          </div>
        </div>

        {apis.error && (
          <p className="mb-2 rounded border border-rose-800/60 bg-rose-950/40 px-2 py-1 text-[11px] text-rose-300">
            {apis.error}
          </p>
        )}

        {/* Kartu API */}
        <div className="min-h-0 flex-1 overflow-y-auto pr-1 custom-scrollbar">
          {apis.loading ? (
            <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
              {Array.from({ length: 6 }).map((_, index) => (
                <Card key={index}>
                  <Skeleton lines={4} />
                </Card>
              ))}
            </div>
          ) : filtered.length === 0 ? (
            <EmptyState
              title="Belum ada API"
              description="Buat endpoint pertama Anda: tentukan path dan method, lalu susun flow di kanvas."
              action={
                <Button size="sm" variant="soft" icon={<IconPlus size={13} />} onClick={() => setCreateOpen(true)}>
                  Buat API sekarang
                </Button>
              }
            />
          ) : (
            <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
              {filtered.map((api) => {
                const detail = details.data?.[api.id];
                const status = api.disabled ? 'DISABLED' : (detail?.latestStatus ?? 'DRAFT');
                const nodeCount = detail?.versions?.[0]?.flowSummary?.nodes ?? 0;
                return (
                  <div
                    key={api.id}
                    onClick={() => onOpenApi(api.id)}
                    className="group flex cursor-pointer flex-col justify-between overflow-hidden rounded-xl border border-slate-800 bg-slate-900/80 p-3.5 transition-colors hover:border-indigo-500/50 hover:bg-slate-900"
                  >
                    <div>
                      <div className="mb-2 flex items-center justify-between gap-2">
                        <Badge tone={methodTone(api.method)} size="xs">
                          {api.method}
                        </Badge>
                        <div className="flex items-center gap-1.5">
                          {detail?.latestLabel && (
                            <span className="rounded border border-slate-800 bg-slate-950 px-1.5 py-0.5 font-mono text-[9px] text-slate-500">
                              {detail.latestLabel}
                            </span>
                          )}
                          <Badge tone={versionTone(status)} size="xs">
                            {status}
                          </Badge>
                        </div>
                      </div>

                      <h3 className="mb-1 truncate text-xs font-semibold text-slate-100 group-hover:text-indigo-300">
                        {api.name}
                      </h3>
                      <p className="mb-2 truncate rounded border border-slate-800/80 bg-slate-950 px-2 py-1 font-mono text-[10px] text-indigo-300/80">
                        {api.path}
                      </p>
                      <p className="mb-3 line-clamp-2 min-h-[2rem] text-[11px] leading-relaxed text-slate-400">
                        {truncate(api.description, 120) || 'Tanpa deskripsi.'}
                      </p>
                    </div>

                    <div className="flex items-center justify-between border-t border-slate-800/80 pt-2 text-[10px] text-slate-500">
                      <span className="flex items-center gap-1 font-mono">
                        <IconLayers size={11} /> {nodeCount} node
                      </span>
                      <div className="flex items-center gap-2">
                        <span className="text-[10px] text-slate-600">{timeAgo(api.updatedAt ?? api.createdAt)}</span>
                        <ArrowLink label="Edit kanvas" onClick={() => onOpenApi(api.id)} />
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>

      <CreateApiModal
        open={createOpen}
        groups={groups.data ?? []}
        defaultGroupId={selectedGroupId}
        onClose={() => setCreateOpen(false)}
        onCreated={(apiId) => {
          setCreateOpen(false);
          reload();
          onNotify('API berhasil dibuat. Menyiapkan kanvas...', 'success');
          onOpenApi(apiId);
        }}
        onError={(message) => onNotify(message, 'error')}
        busy={busy === 'create'}
      />

      <CreateGroupModal
        open={groupModalOpen}
        onClose={() => setGroupModalOpen(false)}
        onCreated={() => {
          setGroupModalOpen(false);
          groups.reload();
          onNotify('Grup dibuat.', 'success');
        }}
        onError={(message) => onNotify(message, 'error')}
      />

      <ConfirmDialog
        open={!!deleteTarget}
        title="Hapus API?"
        tone="error"
        confirmLabel="Hapus"
        busy={busy === 'delete'}
        onCancel={() => setDeleteTarget(null)}
        onConfirm={async () => {
          if (!deleteTarget) {
            return;
          }
          setBusy('delete');
          try {
            await apiApi.remove(deleteTarget.id);
            onNotify('API dihapus.', 'success');
            setDeleteTarget(null);
            reload();
          } catch (cause) {
            onNotify(errorMessage(cause, 'Gagal menghapus API'), 'error');
          } finally {
            setBusy(null);
          }
        }}
      >
        API <strong>{deleteTarget?.name}</strong> beserta seluruh versinya akan dihapus permanen.
      </ConfirmDialog>
    </div>
  );
}

/** Modal pembuatan API. */
function CreateApiModal({
  open,
  groups,
  defaultGroupId,
  busy,
  onClose,
  onCreated,
  onError,
}: {
  open: boolean;
  groups: ApiGroup[];
  defaultGroupId: string | null;
  busy: boolean;
  onClose: () => void;
  onCreated: (apiId: string) => void;
  onError: (message: string) => void;
}) {
  const [name, setName] = useState('');
  const [path, setPath] = useState('/api/v1/');
  const [method, setMethod] = useState<HttpMethod>('POST');
  const [description, setDescription] = useState('');
  const [tags, setTags] = useState('');
  const [groupId, setGroupId] = useState(defaultGroupId ?? '');
  const [auth, setAuth] = useState<'none' | 'apiKey'>('apiKey');
  const [submitting, setSubmitting] = useState(false);

  const submit = async () => {
    if (!name.trim() || !path.trim()) {
      onError('Nama dan path wajib diisi.');
      return;
    }
    setSubmitting(true);
    try {
      const created = await apiApi.create({
        name: name.trim(),
        path: path.trim(),
        method,
        description: description.trim() || undefined,
        tags: tags.trim() || undefined,
        groupId: groupId || undefined,
        policyJson: { auth, rateLimitPerMinute: 120, ipWhitelist: [] },
      });
      onCreated(created.id);
      setName('');
    } catch (cause) {
      onError(errorMessage(cause, 'Gagal membuat API'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title="Buat API baru"
      description="API baru langsung mendapat versi draft berisi node TRIGGER."
      onClose={onClose}
      width="max-w-lg"
      footer={
        <>
          <Button variant="ghost" size="sm" onClick={onClose} disabled={submitting || busy}>
            Batal
          </Button>
          <Button variant="solid" size="sm" loading={submitting || busy} onClick={submit}>
            Buat API
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <Field label="Nama API" required>
          <TextInput
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="Transfer Antar Bank"
          />
        </Field>
        <div className="grid grid-cols-3 gap-2">
          <div className="col-span-1">
            <Field label="Method">
              <Select
                value={method}
                onChange={(event) => setMethod(event.target.value as HttpMethod)}
                options={METHOD_OPTIONS.map((value) => ({ value, label: value }))}
              />
            </Field>
          </div>
          <div className="col-span-2">
            <Field label="Path" required>
              <TextInput
                value={path}
                onChange={(event) => setPath(event.target.value)}
                className="font-mono"
                placeholder="/api/v1/transfer"
              />
            </Field>
          </div>
        </div>
        <Field label="Grup / Proyek">
          <Select
            value={groupId}
            onChange={(event) => setGroupId(event.target.value)}
            options={[
              { value: '', label: '— grup bawaan —' },
              ...groups.map((group) => ({ value: group.id, label: group.name })),
            ]}
          />
        </Field>
        <Field label="Autentikasi">
          <Select
            value={auth}
            onChange={(event) => setAuth(event.target.value as 'none' | 'apiKey')}
            options={[
              { value: 'apiKey', label: 'API key (disarankan)' },
              { value: 'none', label: 'Tanpa autentikasi' },
            ]}
          />
        </Field>
        <Field label="Deskripsi">
          <TextArea
            rows={2}
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            placeholder="Ringkasan fungsi endpoint"
          />
        </Field>
        <Field label="Tag" hint="Pisahkan dengan koma, mis. payment,snapbi">
          <TextInput value={tags} onChange={(event) => setTags(event.target.value)} />
        </Field>
      </div>
    </Modal>
  );
}

/** Modal pembuatan grup/proyek. */
function CreateGroupModal({
  open,
  onClose,
  onCreated,
  onError,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: () => void;
  onError: (message: string) => void;
}) {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const submit = async () => {
    if (!name.trim()) {
      onError('Nama grup wajib diisi.');
      return;
    }
    setSubmitting(true);
    try {
      await groupApi.create({ name: name.trim(), description: description.trim() || undefined });
      setName('');
      setDescription('');
      onCreated();
    } catch (cause) {
      onError(errorMessage(cause, 'Gagal membuat grup'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title="Tambah grup / proyek"
      onClose={onClose}
      width="max-w-md"
      footer={
        <>
          <Button variant="ghost" size="sm" onClick={onClose} disabled={submitting}>
            Batal
          </Button>
          <Button variant="solid" size="sm" loading={submitting} onClick={submit}>
            Simpan
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <Field label="Nama grup" required>
          <TextInput
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="Payments"
          />
        </Field>
        <Field label="Deskripsi">
          <TextArea
            rows={2}
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            placeholder="API pembayaran dan transfer"
          />
        </Field>
        <p className="flex items-start gap-1.5 text-[10px] text-slate-500">
          <IconZap size={11} className="mt-0.5 shrink-0" />
          Grup membantu mengelompokkan API per microservice dan memfilter arsip.
        </p>
      </div>
    </Modal>
  );
}
