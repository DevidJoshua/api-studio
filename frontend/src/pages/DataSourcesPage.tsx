import { useCallback, useMemo, useState } from 'react';
import { adminApi, apiApi } from '../api/endpoints';
import type { ApiDetail, ConsumerInfo, EnvironmentInfo, SecretInfo } from '../api/types';
import { useAsync } from '../app/hooks';
import { IconPlus } from '../app/icons';
import {
  Badge,
  Button,
  Card,
  DataTable,
  EmptyState,
  Field,
  Grid,
  HintBox,
  Modal,
  Panel,
  Select,
  Skeleton,
  Stack,
  Tabs,
  TextArea,
  TextInput,
  Toggle,
  type Column,
} from '../ui';
import { errorMessage, formatDateTime } from '../lib/format';

type SourcesTab = 'environments' | 'secrets' | 'consumers';

/**
 * Sumber data & konfigurasi: environment, secret, dan consumer.
 *
 * Halaman ini menggabungkan tiga area administratif yang saling terkait:
 * instance Flow Engine per environment, kredensial yang dipakai flow, dan
 * klien yang memanggil gateway.
 */
export function DataSourcesPage({
  onNotify,
}: {
  onNotify: (message: string, tone: 'success' | 'error' | 'info') => void;
}) {
  const [tab, setTab] = useState<SourcesTab>('environments');

  return (
    <div className="h-full overflow-y-auto p-4 custom-scrollbar">
      <div className="mb-3">
        <h2 className="text-base font-bold tracking-tight text-slate-100">
          Data Sources &amp; Configuration
        </h2>
        <p className="text-[11px] text-slate-400">
          Environment, secret terenkripsi, dan consumer API key untuk gateway.
        </p>
      </div>

      <Tabs
        active={tab}
        onChange={(key) => setTab(key as SourcesTab)}
        tabs={[
          { key: 'environments', label: 'Environment' },
          { key: 'secrets', label: 'Secret' },
          { key: 'consumers', label: 'Consumer' },
        ]}
      />

      <div className="mt-3">
        {tab === 'environments' && <EnvironmentsSection onNotify={onNotify} />}
        {tab === 'secrets' && <SecretsSection onNotify={onNotify} />}
        {tab === 'consumers' && <ConsumersSection onNotify={onNotify} />}
      </div>
    </div>
  );
}

/** Environment dan instance Flow Engine. */
function EnvironmentsSection({
  onNotify,
}: {
  onNotify: (message: string, tone: 'success' | 'error' | 'info') => void;
}) {
  const load = useCallback(() => adminApi.environments(), []);
  const environments = useAsync<EnvironmentInfo[]>(load, []);
  const [busy, setBusy] = useState<string | null>(null);

  const patch = async (code: string, body: Record<string, unknown>) => {
    setBusy(code);
    try {
      await adminApi.updateEnvironment(code, body);
      onNotify(`Environment ${code} diperbarui.`, 'success');
      environments.reload();
    } catch (cause) {
      onNotify(errorMessage(cause, 'Gagal memperbarui environment'), 'error');
    } finally {
      setBusy(null);
    }
  };

  if (environments.loading) {
    return <Skeleton lines={6} />;
  }

  return (
    <Grid cols={3}>
      {(environments.data ?? []).map((environment) => (
        <Card
          key={environment.id}
          title={environment.name}
          subtitle={environment.description}
          actions={
            <Badge tone={environment.requiresApproval ? 'warning' : 'success'} size="xs">
              {environment.requiresApproval ? 'approval wajib' : 'tanpa approval'}
            </Badge>
          }
        >
          <Stack gap={2}>
            <div className="flex items-center justify-between text-[11px]">
              <span className="text-slate-500">Kode</span>
              <code className="rounded bg-slate-950 px-1.5 py-0.5 font-mono text-slate-300">
                {environment.code}
              </code>
            </div>

            <Panel title={`Instance (${environment.instances?.length ?? 0})`}>
              {environment.instances?.length ? (
                <ul className="space-y-0.5 font-mono text-[10px] text-slate-400">
                  {environment.instances.map((instance) => (
                    <li key={instance.id} className="truncate">
                      · {instance.id} {instance.zone ? `(${instance.zone})` : ''}
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="text-[10px] text-slate-500">
                  Tidak ada instance terdaftar; memakai engine in-process.
                </p>
              )}
            </Panel>

            <div className="space-y-1.5">
              <Toggle
                label="Wajib approval"
                checked={environment.requiresApproval}
                disabled={busy === environment.code}
                onChange={(value) => void patch(environment.code, { requiresApproval: value })}
              />
              <Toggle
                label="Simulasi diizinkan"
                checked={environment.simulationEnabled}
                disabled={busy === environment.code}
                onChange={(value) => void patch(environment.code, { simulationEnabled: value })}
              />
              <Toggle
                label="Canary diizinkan"
                checked={environment.canaryEnabled}
                disabled={busy === environment.code}
                onChange={(value) => void patch(environment.code, { canaryEnabled: value })}
              />
            </div>
          </Stack>
        </Card>
      ))}
    </Grid>
  );
}

/** Secret terenkripsi (nilai tidak pernah ditampilkan). */
function SecretsSection({
  onNotify,
}: {
  onNotify: (message: string, tone: 'success' | 'error' | 'info') => void;
}) {
  const [environment, setEnvironment] = useState('dev');
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [value, setValue] = useState('');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => adminApi.secrets(environment), [environment]);
  const secrets = useAsync<SecretInfo[]>(load, [environment]);

  const columns: Column<SecretInfo>[] = [
    {
      key: 'name',
      header: 'Nama',
      render: (row) => <span className="font-mono text-[11px] text-slate-200">{row.name}</span>,
    },
    {
      key: 'hint',
      header: 'Nilai',
      render: (row) => (
        <span className="font-mono text-[11px] text-slate-500">{row.hint ?? '****'}</span>
      ),
    },
    {
      key: 'description',
      header: 'Deskripsi',
      hideOnMobile: true,
      render: (row) => <span className="text-[11px] text-slate-400">{row.description ?? '—'}</span>,
    },
    {
      key: 'updatedAt',
      header: 'Diperbarui',
      hideOnMobile: true,
      render: (row) => (
        <span className="text-[10px] text-slate-500">{formatDateTime(row.updatedAt ?? row.createdAt)}</span>
      ),
    },
  ];

  const submit = async () => {
    if (!name.trim() || !value) {
      onNotify('Nama dan nilai secret wajib diisi.', 'error');
      return;
    }
    setBusy(true);
    try {
      await adminApi.putSecret({
        name: name.trim(),
        environment,
        value,
        description: description.trim() || undefined,
      });
      onNotify('Secret disimpan (nilai tidak dapat dibaca kembali).', 'success');
      setOpen(false);
      setName('');
      setValue('');
      setDescription('');
      secrets.reload();
    } catch (cause) {
      onNotify(errorMessage(cause, 'Gagal menyimpan secret'), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card
      title="Secret"
      subtitle="Nilai dienkripsi AES-GCM; flow hanya menyimpan nama referensi."
      actions={
        <div className="flex items-center gap-1.5">
          <div className="w-28">
            <Select
              value={environment}
              onChange={(event) => setEnvironment(event.target.value)}
              options={['dev', 'staging', 'production'].map((item) => ({ value: item, label: item }))}
            />
          </div>
          <Button size="xs" variant="solid" icon={<IconPlus size={12} />} onClick={() => setOpen(true)}>
            Tambah
          </Button>
        </div>
      }
    >
      <DataTable
        columns={columns}
        rows={secrets.data ?? []}
        rowKey={(row) => row.id}
        emptyMessage="Belum ada secret pada environment ini"
      />

      <Modal
        open={open}
        title="Tambah / perbarui secret"
        description="Nilai disimpan terenkripsi dan tidak ditampilkan lagi setelah disimpan."
        onClose={() => setOpen(false)}
        width="max-w-md"
        footer={
          <>
            <Button variant="ghost" size="sm" onClick={() => setOpen(false)}>
              Batal
            </Button>
            <Button variant="solid" size="sm" loading={busy} onClick={submit}>
              Simpan
            </Button>
          </>
        }
      >
        <Stack gap={2}>
          <Field label="Nama secret" required hint="Dipakai flow sebagai referensi, mis. partnerToken">
            <TextInput
              value={name}
              onChange={(event) => setName(event.target.value)}
              className="font-mono"
              placeholder="partnerToken"
            />
          </Field>
          <Field label="Environment">
            <Select
              value={environment}
              onChange={(event) => setEnvironment(event.target.value)}
              options={['dev', 'staging', 'production'].map((item) => ({ value: item, label: item }))}
            />
          </Field>
          <Field label="Nilai" required>
            <TextInput
              type="password"
              value={value}
              onChange={(event) => setValue(event.target.value)}
              placeholder="••••••••"
            />
          </Field>
          <Field label="Deskripsi">
            <TextArea
              rows={2}
              value={description}
              onChange={(event) => setDescription(event.target.value)}
            />
          </Field>
        </Stack>
      </Modal>
    </Card>
  );
}

/** Consumer dan API key gateway. */
function ConsumersSection({
  onNotify,
}: {
  onNotify: (message: string, tone: 'success' | 'error' | 'info') => void;
}) {
  const load = useCallback(() => adminApi.consumers(), []);
  const consumers = useAsync<ConsumerInfo[]>(load, []);
  const loadApis = () => apiApi.list();
  const apis = useAsync(loadApis, []);

  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [allowed, setAllowed] = useState<string[]>([]);
  const [quota, setQuota] = useState('0');
  const [busy, setBusy] = useState(false);
  const [createdKey, setCreatedKey] = useState<string | null>(null);

  const apiOptions = useMemo(
    () => (apis.data ?? []).map((api) => ({ id: api.id, label: `${api.method} ${api.path}` })),
    [apis.data],
  );

  const columns: Column<ConsumerInfo>[] = [
    { key: 'name', header: 'Nama', render: (row) => <span className="text-[11px] text-slate-200">{row.name}</span> },
    {
      key: 'apiKeyHint',
      header: 'API key',
      render: (row) => <span className="font-mono text-[11px] text-slate-500">{row.apiKeyHint ?? '—'}</span>,
    },
    {
      key: 'allowedApiIds',
      header: 'Akses',
      hideOnMobile: true,
      render: (row) => (
        <span className="text-[11px] text-slate-400">
          {row.allowedApiIds ? `${row.allowedApiIds.split(',').length} API` : 'semua API'}
        </span>
      ),
    },
    {
      key: 'dailyQuota',
      header: 'Kuota',
      hideOnMobile: true,
      render: (row) => (
        <span className="text-[11px] text-slate-400">{row.dailyQuota ? row.dailyQuota : '∞'}</span>
      ),
    },
  ];

  const submit = async () => {
    if (!name.trim()) {
      onNotify('Nama consumer wajib diisi.', 'error');
      return;
    }
    setBusy(true);
    try {
      const created = await adminApi.createConsumer({
        name: name.trim(),
        description: description.trim() || undefined,
        allowedApiIds: allowed.join(','),
        dailyQuota: Number(quota) || 0,
      });
      setCreatedKey(created.apiKey ?? null);
      onNotify('Consumer dibuat. Salin API key sekarang — hanya ditampilkan sekali.', 'success');
      setName('');
      setDescription('');
      setAllowed([]);
      setQuota('0');
      consumers.reload();
    } catch (cause) {
      onNotify(errorMessage(cause, 'Gagal membuat consumer'), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card
      title="Consumer & API key"
      subtitle="API key hanya ditampilkan sekali saat dibuat; yang disimpan hanya hash SHA-256."
      actions={
        <Button size="xs" variant="solid" icon={<IconPlus size={12} />} onClick={() => setOpen(true)}>
          Consumer baru
        </Button>
      }
    >
      <DataTable
        columns={columns}
        rows={consumers.data ?? []}
        rowKey={(row) => row.id}
        emptyMessage="Belum ada consumer"
      />

      <Modal
        open={open}
        title="Consumer baru"
        description="Batasi akses ke API tertentu atau biarkan kosong untuk semua API."
        onClose={() => {
          setOpen(false);
          setCreatedKey(null);
        }}
        width="max-w-lg"
        footer={
          <>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                setOpen(false);
                setCreatedKey(null);
              }}
            >
              Tutup
            </Button>
            <Button variant="solid" size="sm" loading={busy} onClick={submit}>
              Buat consumer
            </Button>
          </>
        }
      >
        <Stack gap={2}>
          <Field label="Nama" required>
            <TextInput
              value={name}
              onChange={(event) => setName(event.target.value)}
              placeholder="Partner SNAP BI"
            />
          </Field>
          <Field label="Deskripsi">
            <TextArea rows={2} value={description} onChange={(event) => setDescription(event.target.value)} />
          </Field>
          <Field label="Kuota harian" hint="0 berarti tanpa batas">
            <TextInput type="number" value={quota} onChange={(event) => setQuota(event.target.value)} />
          </Field>
          <Field label="API yang diizinkan" hint="Kosongkan untuk mengizinkan semua API.">
            <div className="space-y-1">
              {apiOptions.map((option) => (
                <label
                  key={option.id}
                  className="flex cursor-pointer items-center gap-2 text-[11px] text-slate-300"
                >
                  <input
                    type="checkbox"
                    checked={allowed.includes(option.id)}
                    onChange={(event) =>
                      setAllowed((current) =>
                        event.target.checked
                          ? [...current, option.id]
                          : current.filter((id) => id !== option.id),
                      )
                    }
                    className="h-3 w-3 rounded border-slate-600 bg-slate-900"
                  />
                  <span className="font-mono">{option.label}</span>
                </label>
              ))}
              {apiOptions.length === 0 && (
                <p className="text-[10px] text-slate-500">Belum ada API terdaftar.</p>
              )}
            </div>
          </Field>

          {createdKey && (
            <HintBox tone="success" title="API key (salin sekarang)">
              <code className="break-all font-mono">{createdKey}</code>
            </HintBox>
          )}
        </Stack>
      </Modal>
    </Card>
  );
}

/** Halaman ringkasan konfigurasi (dipakai tab Settings). */
export function SettingsPage({
  onNotify,
}: {
  onNotify: (message: string, tone: 'success' | 'error' | 'info') => void;
}) {
  const loadApis = () => apiApi.list();
  const apis = useAsync(loadApis, []);
  const loadDetails = useCallback(async () => {
    const list = apis.data ?? [];
    const results = await Promise.all(
      list.slice(0, 12).map(async (api) => {
        try {
          return await apiApi.detail(api.id);
        } catch {
          return null;
        }
      }),
    );
    return results.filter((item): item is ApiDetail => item !== null);
  }, [apis.data]);
  const details = useAsync<ApiDetail[]>(loadDetails, [apis.data]);

  return (
    <div className="h-full overflow-y-auto p-4 custom-scrollbar">
      <div className="mb-3">
        <h2 className="text-base font-bold tracking-tight text-slate-100">Pengaturan Platform</h2>
        <p className="text-[11px] text-slate-400">
          Ringkasan konfigurasi API dan kebijakan keamanan.
        </p>
      </div>

      <Grid cols={2}>
        <Card title="Kebijakan API" subtitle="Autentikasi dan rate limit per API">
          {details.loading ? (
            <Skeleton lines={5} />
          ) : (
            <Stack gap={2}>
              {(details.data ?? []).map((api) => (
                <div
                  key={api.id}
                  className="flex items-center justify-between gap-2 rounded border border-slate-800 bg-slate-950/50 px-2 py-1.5"
                >
                  <div className="min-w-0">
                    <p className="truncate text-[11px] text-slate-200">{api.name}</p>
                    <p className="truncate font-mono text-[10px] text-slate-500">{api.path}</p>
                  </div>
                  <div className="flex shrink-0 items-center gap-1.5">
                    <Badge tone={api.policy?.auth === 'none' ? 'muted' : 'success'} size="xs">
                      {api.policy?.auth ?? 'none'}
                    </Badge>
                    {api.policy?.rateLimitPerMinute ? (
                      <Badge tone="info" size="xs">
                        {api.policy.rateLimitPerMinute}/mnt
                      </Badge>
                    ) : null}
                  </div>
                </div>
              ))}
              {(details.data ?? []).length === 0 && (
                <EmptyState title="Belum ada API" description="Kebijakan akan tampil di sini." />
              )}
            </Stack>
          )}
        </Card>

        <Card title="Panduan cepat" subtitle="Alur kerja yang disarankan">
          <Stack gap={2}>
            <HintBox tone="info" title="1. Susun & validasi">
              Buat API, buka kanvas, seret node dari palette, lalu klik Validasi untuk memeriksa
              graph.
            </HintBox>
            <HintBox tone="info" title="2. Uji dengan simulasi">
              Jalankan simulasi dengan body dan mock; trace per node dapat diklik untuk melihat
              input/output.
            </HintBox>
            <HintBox tone="info" title="3. Publish & deploy">
              Buka Preflight, publish versi, lalu deploy ke environment. Production memerlukan
              persetujuan reviewer.
            </HintBox>
            <Button
              size="sm"
              variant="soft"
              onClick={() => {
                onNotify('Panduan lengkap ada di CONTEXT.MD.', 'info');
              }}
            >
              Lihat dokumentasi
            </Button>
          </Stack>
        </Card>
      </Grid>
    </div>
  );
}

