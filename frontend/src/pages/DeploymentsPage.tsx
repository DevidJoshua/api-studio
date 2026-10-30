import { useCallback, useMemo, useState } from 'react';
import { adminApi, apiApi, releaseApi } from '../api/endpoints';
import type {
  ApiDetail,
  ApiSummary,
  Deployment,
  EnvironmentInfo,
  PreflightCheck,
  ReleaseBundle,
} from '../api/types';
import { useAsync } from '../app/hooks';
import { IconRefresh, IconRocket } from '../app/icons';
import {
  Alert,
  Badge,
  Button,
  Card,
  ConfirmDialog,
  DataTable,
  EmptyState,
  Field,
  Grid,
  HintBox,
  Modal,
  Panel,
  Progress,
  SegmentedControl,
  Select,
  Skeleton,
  Stack,
  StatCard,
  TextInput,
  Timeline,
  type Column,
} from '../ui';
import { errorMessage, formatDateTime, timeAgo } from '../lib/format';
import { deploymentTone } from '../ui/types';

/**
 * Halaman deployment: publish versi, deploy ke environment, canary, rollback.
 *
 * Semua aksi berisiko (deploy, rollback) memakai dialog konfirmasi dan
 * menampilkan hasil kesiapan instance agar operator tahu apa yang terjadi.
 */
export function DeploymentsPage({
  apiId,
  onNotify,
}: {
  apiId: string | null;
  onNotify: (message: string, tone: 'success' | 'error' | 'info') => void;
}) {
  const loadApis = () => apiApi.list();
  const apis = useAsync<ApiSummary[]>(loadApis, []);
  const [selectedApiId] = useState(apiId ?? '');
  const effectiveApiId = selectedApiId || apiId || apis.data?.[0]?.id || '';

  const loadDetail = useCallback(
    () => (effectiveApiId ? apiApi.detail(effectiveApiId) : Promise.resolve(null)),
    [effectiveApiId],
  );
  const detail = useAsync<ApiDetail | null>(loadDetail, [effectiveApiId]);

  const loadReleases = useCallback(
    () => (effectiveApiId ? releaseApi.releases(effectiveApiId) : Promise.resolve([])),
    [effectiveApiId],
  );
  const releases = useAsync<ReleaseBundle[]>(loadReleases, [effectiveApiId]);

  const loadDeployments = useCallback(
    () => (effectiveApiId ? releaseApi.deployments(effectiveApiId) : Promise.resolve([])),
    [effectiveApiId],
  );
  const deployments = useAsync<Deployment[]>(loadDeployments, [effectiveApiId]);

  const loadEnvironments = () => adminApi.environments();
  const environments = useAsync<EnvironmentInfo[]>(loadEnvironments, []);

  const [environment, setEnvironment] = useState('dev');
  const [publishOpen, setPublishOpen] = useState(false);
  const [deployOpen, setDeployOpen] = useState<ReleaseBundle | null>(null);
  const [rollbackOpen, setRollbackOpen] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [preflight, setPreflight] = useState<PreflightCheck | null>(null);

  const draftVersion = useMemo(
    () => detail.data?.versions.find((version) => version.status === 'DRAFT') ?? null,
    [detail.data],
  );

  const activeDeployments = useMemo(
    () => (deployments.data ?? []).filter((deployment) => deployment.status === 'ACTIVE'),
    [deployments.data],
  );

  const activeInEnv = activeDeployments.find((deployment) => deployment.environment === environment);

  const runPreflight = async () => {
    if (!draftVersion) {
      onNotify('Tidak ada versi draft untuk dipublish.', 'error');
      return;
    }
    setBusy('preflight');
    try {
      const result = await releaseApi.preflight(draftVersion.id, environment);
      setPreflight(result);
      setPublishOpen(true);
    } catch (cause) {
      onNotify(errorMessage(cause, 'Gagal memeriksa preflight'), 'error');
    } finally {
      setBusy(null);
    }
  };

  const publish = async () => {
    if (!draftVersion) {
      return;
    }
    setBusy('publish');
    try {
      const release = await releaseApi.publish(draftVersion.id, environment);
      onNotify(`Release ${release.versionNumber} dipublish.`, 'success');
      setPublishOpen(false);
      releases.reload();
      detail.reload();
    } catch (cause) {
      onNotify(errorMessage(cause, 'Gagal publish'), 'error');
    } finally {
      setBusy(null);
    }
  };

  const deploy = async (
    release: ReleaseBundle,
    strategy: 'DIRECT' | 'CANARY',
    canaryPercent: number,
  ) => {
    setBusy('deploy');
    try {
      const deployment = await releaseApi.deploy(release.id, environment, strategy, canaryPercent);
      onNotify(
        `Versi ${deployment.versionNumber} aktif di ${environment} (${deployment.status}).`,
        'success',
      );
      setDeployOpen(null);
      deployments.reload();
      detail.reload();
    } catch (cause) {
      onNotify(errorMessage(cause, 'Gagal deploy'), 'error');
    } finally {
      setBusy(null);
    }
  };

  const rollback = async () => {
    setBusy('rollback');
    try {
      const deployment = await releaseApi.rollback(effectiveApiId, environment);
      onNotify(`Rollback ke versi ${deployment.versionNumber} berhasil.`, 'success');
      setRollbackOpen(false);
      deployments.reload();
      detail.reload();
    } catch (cause) {
      onNotify(errorMessage(cause, 'Gagal rollback'), 'error');
    } finally {
      setBusy(null);
    }
  };

  const updateCanary = async (deploymentId: string, percent: number) => {
    try {
      await releaseApi.updateCanary(deploymentId, percent);
      onNotify(`Bobot canary menjadi ${percent}%.`, 'success');
      deployments.reload();
    } catch (cause) {
      onNotify(errorMessage(cause, 'Gagal mengubah canary'), 'error');
    }
  };

  const deploymentColumns: Column<Deployment>[] = [
    {
      key: 'versionNumber',
      header: 'Versi',
      render: (row) => <span className="font-mono text-[11px] text-slate-200">v{row.versionNumber}</span>,
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
        <Badge tone={deploymentTone(row.status)} size="xs">
          {row.status}
        </Badge>
      ),
    },
    {
      key: 'strategy',
      header: 'Strategi',
      hideOnMobile: true,
      render: (row) => (
        <span className="text-[11px] text-slate-400">
          {row.strategy === 'CANARY' ? `canary ${row.canaryPercent}%` : 'langsung'}
        </span>
      ),
    },
    {
      key: 'createdAt',
      header: 'Waktu',
      hideOnMobile: true,
      render: (row) => <span className="text-[10px] text-slate-500">{timeAgo(row.createdAt)}</span>,
    },
  ];

  return (
    <div className="h-full overflow-y-auto p-4 custom-scrollbar">
      <div className="mb-3 flex flex-wrap items-start justify-between gap-2">
        <div>
          <h2 className="text-base font-bold tracking-tight text-slate-100">Deployments</h2>
          <p className="text-[11px] text-slate-400">
            Publish versi immutable, deploy bertahap, dan rollback tanpa downtime.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-1.5">
          <div className="w-32">
            <Select
              value={environment}
              onChange={(event) => setEnvironment(event.target.value)}
              options={['dev', 'staging', 'production'].map((item) => ({ value: item, label: item }))}
            />
          </div>
          <Button
            size="xs"
            variant="ghost"
            icon={<IconRefresh size={12} />}
            onClick={() => {
              releases.reload();
              deployments.reload();
              detail.reload();
            }}
          >
            Muat ulang
          </Button>
          <Button
            size="xs"
            variant="solid"
            icon={<IconRocket size={12} />}
            loading={busy === 'preflight'}
            disabled={!draftVersion}
            onClick={runPreflight}
          >
            Publish draft
          </Button>
        </div>
      </div>

      <Grid cols={4} gap={2}>
        <StatCard
          label="Release"
          value={releases.data?.length ?? 0}
          hint="bundle immutable"
          tone="info"
        />
        <StatCard
          label="Aktif"
          value={activeDeployments.length}
          hint={`${activeDeployments.map((item) => item.environment).join(', ') || 'belum ada'}`}
          tone="success"
        />
        <StatCard
          label="Draft"
          value={draftVersion ? draftVersion.label : '—'}
          hint={draftVersion ? 'siap dipublish' : 'tidak ada draft'}
          tone={draftVersion ? 'warning' : 'muted'}
        />
        <StatCard
          label="Versi aktif"
          value={activeInEnv ? `v${activeInEnv.versionNumber}` : '—'}
          hint={environment}
          tone={activeInEnv ? 'primary' : 'muted'}
        />
      </Grid>

      <div className="mt-3 grid gap-3 xl:grid-cols-3">
        <div className="space-y-3 xl:col-span-2">
          <Card title="Riwayat deployment" subtitle="Semua environment untuk API ini">
            {deployments.loading ? (
              <Skeleton lines={4} />
            ) : (
              <DataTable
                columns={deploymentColumns}
                rows={deployments.data ?? []}
                rowKey={(row) => row.id}
                emptyMessage="Belum ada deployment"
              />
            )}
          </Card>

          <Card
            title="Release tersedia"
            subtitle="Bundle immutable hasil publish; dapat dideploy berulang"
          >
            {releases.loading ? (
              <Skeleton lines={3} />
            ) : (releases.data ?? []).length === 0 ? (
              <EmptyState
                title="Belum ada release"
                description="Jalankan preflight lalu publish versi draft."
              />
            ) : (
              <ul className="space-y-1.5">
                {(releases.data ?? []).map((release) => (
                  <li
                    key={release.id}
                    className="flex flex-wrap items-center justify-between gap-2 rounded border border-slate-800 bg-slate-950/50 px-2 py-1.5"
                  >
                    <div className="min-w-0">
                      <p className="text-[11px] font-medium text-slate-200">
                        v{release.versionNumber}
                        <span className="ml-1.5 font-mono text-[10px] text-slate-500">
                          {release.artifactHash?.slice(0, 12) ?? '—'}
                        </span>
                      </p>
                      <p className="text-[10px] text-slate-500">
                        {formatDateTime(release.createdAt)} · {release.createdBy ?? '—'}
                        {release.seedCount ? ` · ${release.seedCount} seed` : ''}
                      </p>
                    </div>
                    <Button size="xs" variant="soft" onClick={() => setDeployOpen(release)}>
                      Deploy
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </div>

        <div className="space-y-3">
          <Card title="Kontrol cepat" subtitle={`Environment ${environment}`}>
            <Stack gap={2}>
              {activeInEnv?.strategy === 'CANARY' && (
                <Panel title="Bobot canary">
                  <div className="space-y-2">
                    <Progress
                      value={activeInEnv.canaryPercent}
                      tone="primary"
                      label={
                        <>
                          <span>versi baru</span>
                          <span className="tabular-nums">{activeInEnv.canaryPercent}%</span>
                        </>
                      }
                    />
                    <div className="flex flex-wrap gap-1">
                      {[10, 25, 50, 75, 100].map((percent) => (
                        <Button
                          key={percent}
                          size="xs"
                          variant={activeInEnv.canaryPercent === percent ? 'solid' : 'ghost'}
                          onClick={() => void updateCanary(activeInEnv.id, percent)}
                        >
                          {percent}%
                        </Button>
                      ))}
                    </div>
                  </div>
                </Panel>
              )}

              <Button
                size="sm"
                variant="outline"
                block
                disabled={!activeInEnv}
                loading={busy === 'rollback'}
                onClick={() => setRollbackOpen(true)}
              >
                Rollback ke versi sebelumnya
              </Button>

              <HintBox tone="info" title="Drain & rollback">
                Versi lama tidak diputus: request yang sedang berjalan diselesaikan (drain) lalu
                dilepas, sehingga rollback selalu tersedia.
              </HintBox>
            </Stack>
          </Card>

          <Card title="Health instance" subtitle="Seluruh instance harus siap sebelum routing dialihkan">
            {(environments.data ?? [])
              .filter((env) => env.code === environment)
              .map((env) => (
                <ul key={env.id} className="space-y-1">
                  {(env.instances ?? []).map((instance) => (
                    <li
                      key={instance.id}
                      className="flex items-center justify-between gap-2 rounded border border-slate-800 bg-slate-950/50 px-2 py-1"
                    >
                      <span className="truncate font-mono text-[10px] text-slate-400">
                        {instance.id}
                      </span>
                      <Badge tone="success" size="xs">
                        siap
                      </Badge>
                    </li>
                  ))}
                  {(env.instances ?? []).length === 0 && (
                    <li className="text-[10px] text-slate-500">
                      Tidak ada instance terdaftar (engine in-process).
                    </li>
                  )}
                </ul>
              ))}
          </Card>

          <Card title="Linimasa" subtitle="Aktivitas deployment terakhir">
            <Timeline
              items={(deployments.data ?? []).slice(0, 8).map((deployment) => ({
                key: deployment.id,
                title: `v${deployment.versionNumber} → ${deployment.environment}`,
                subtitle: deployment.message ?? deployment.strategy,
                meta: timeAgo(deployment.createdAt),
                tone: deploymentTone(deployment.status),
              }))}
            />
          </Card>
        </div>
      </div>

      {/* Modal publish */}
      <Modal
        open={publishOpen}
        title="Publish versi"
        description={draftVersion ? `${draftVersion.label} ke ${environment}` : undefined}
        onClose={() => setPublishOpen(false)}
        width="max-w-xl"
        footer={
          <>
            <Button variant="ghost" size="sm" onClick={() => setPublishOpen(false)}>
              Batal
            </Button>
            <Button
              variant="solid"
              size="sm"
              loading={busy === 'publish'}
              disabled={!preflight?.ready}
              onClick={publish}
            >
              Publish sekarang
            </Button>
          </>
        }
      >
        {preflight ? (
          <Stack gap={2}>
            <Alert tone={preflight.ready ? 'success' : 'warning'}>
              {preflight.ready
                ? 'Semua pemeriksaan terpenuhi. Publish akan membuat release immutable.'
                : 'Ada pemeriksaan yang belum terpenuhi; publish akan ditolak backend.'}
            </Alert>
            <Panel title="Checklist">
              <ul className="space-y-1">
                {preflight.checks.map((check) => (
                  <li key={check.name} className="flex items-start gap-2">
                    <Badge tone={check.passed ? 'success' : 'warning'} size="xs">
                      {check.passed ? 'OK' : 'BLOCKED'}
                    </Badge>
                    <div className="min-w-0">
                      <p className="text-[11px] font-medium capitalize text-slate-200">{check.name}</p>
                      <p className="text-[10px] text-slate-400">{check.message}</p>
                    </div>
                  </li>
                ))}
              </ul>
            </Panel>
            {!preflight.ready && (
              <HintBox tone="warning" title="Cara memperbaiki">
                Untuk production: ajukan approval lalu setujui sebagai reviewer. Untuk flow:
                perbaiki error validasi atau kompilasi terlebih dahulu.
              </HintBox>
            )}
          </Stack>
        ) : (
          <Skeleton lines={5} />
        )}
      </Modal>

      {/* Modal deploy */}
      <DeployModal
        release={deployOpen}
        environment={environment}
        busy={busy === 'deploy'}
        onClose={() => setDeployOpen(null)}
        onDeploy={deploy}
      />

      <ConfirmDialog
        open={rollbackOpen}
        title="Rollback deployment?"
        tone="warning"
        confirmLabel="Rollback"
        busy={busy === 'rollback'}
        onCancel={() => setRollbackOpen(false)}
        onConfirm={rollback}
      >
        Routing pada environment <strong>{environment}</strong> akan dikembalikan ke versi
        sebelumnya yang masih tersedia. Request yang sedang berjalan diselesaikan lebih dahulu
        (drain).
      </ConfirmDialog>
    </div>
  );
}

/** Modal pemilihan strategi deploy. */
function DeployModal({
  release,
  environment,
  busy,
  onClose,
  onDeploy,
}: {
  release: ReleaseBundle | null;
  environment: string;
  busy: boolean;
  onClose: () => void;
  onDeploy: (release: ReleaseBundle, strategy: 'DIRECT' | 'CANARY', canaryPercent: number) => void;
}) {
  const [strategy, setStrategy] = useState<'DIRECT' | 'CANARY'>('DIRECT');
  const [canary, setCanary] = useState('10');

  return (
    <Modal
      open={!!release}
      title="Deploy release"
      description={
        release ? `Versi ${release.versionNumber} ke environment ${environment}` : undefined
      }
      onClose={onClose}
      width="max-w-md"
      footer={
        <>
          <Button variant="ghost" size="sm" onClick={onClose}>
            Batal
          </Button>
          <Button
            variant="solid"
            size="sm"
            loading={busy}
            onClick={() =>
              release && onDeploy(release, strategy, Number(canary) || 10)
            }
          >
            Deploy
          </Button>
        </>
      }
    >
      <Stack gap={2}>
        <Field label="Strategi">
          <SegmentedControl
            value={strategy}
            onChange={(value) => setStrategy(value)}
            size="sm"
            options={[
              { value: 'DIRECT', label: 'Langsung 100%' },
              { value: 'CANARY', label: 'Canary bertahap' },
            ]}
          />
        </Field>
        {strategy === 'CANARY' && (
          <Field label="Bobot awal (%)" hint="Dapat diubah setelah deploy.">
            <TextInput
              type="number"
              min={1}
              max={100}
              value={canary}
              onChange={(event) => setCanary(event.target.value)}
            />
          </Field>
        )}
        <HintBox tone="info" title="Aktivasi atomik">
          Semua instance environment diperiksa kesiapannya sebelum routing dialihkan (FR-DEP-07).
          Versi lama masuk status DRAINING.
        </HintBox>
      </Stack>
    </Modal>
  );
}
