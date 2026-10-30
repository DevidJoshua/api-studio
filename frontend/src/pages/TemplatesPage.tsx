import { useCallback, useMemo, useState } from 'react';
import { templateApi } from '../api/endpoints';
import type { FunctionTemplateDetail, FunctionTemplateInfo, TemplateVersionInfo } from '../api/types';
import { useAsync } from '../app/hooks';
import { IconPlus } from '../app/icons';
import {
  Badge,
  Button,
  Card,
  CodeBlock,
  EmptyState,
  ExpressionEditor,
  Field,
  Grid,
  HintBox,
  JsonEditor,
  Modal,
  SearchInput,
  SegmentedControl,
  Select,
  Skeleton,
  Stack,
  Tabs,
  TextArea,
  TextInput,
} from '../ui';
import { errorMessage, formatDateTime, prettyJson } from '../lib/format';

/**
 * Katalog function template (BRD FR-TPL).
 *
 * Kiri: daftar template per kategori dengan pencarian. Kanan: detail versi,
 * definisi input/output, implementasi, dan dependensi pemakaian.
 */
export function TemplatesPage({
  onNotify,
  onUseInCanvas,
}: {
  onNotify: (message: string, tone: 'success' | 'error' | 'info') => void;
  onUseInCanvas: (templateKey: string, version: number) => void;
}) {
  const [category, setCategory] = useState<string>('ALL');
  const [search, setSearch] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);

  const loadTemplates = () => templateApi.list();
  const loadCategories = () => templateApi.categories();
  const templates = useAsync<FunctionTemplateInfo[]>(loadTemplates, []);
  const categories = useAsync(loadCategories, []);

  const loadDetail = useCallback(
    () => (selectedId ? templateApi.detail(selectedId) : Promise.resolve(null)),
    [selectedId],
  );
  const detail = useAsync<FunctionTemplateDetail | null>(loadDetail, [selectedId]);

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase();
    return (templates.data ?? []).filter((template) => {
      if (category !== 'ALL' && template.category !== category) {
        return false;
      }
      if (
        term &&
        !`${template.name} ${template.templateKey} ${template.category} ${template.description ?? ''}`
          .toLowerCase()
          .includes(term)
      ) {
        return false;
      }
      return true;
    });
  }, [templates.data, category, search]);

  const active = detail.data ?? filtered.find((item) => item.id === selectedId) ?? null;

  return (
    <div className="flex h-full min-h-0">
      {/* Daftar */}
      <aside className="flex w-64 shrink-0 flex-col border-r border-slate-800 bg-slate-900/40">
        <div className="border-b border-slate-800 p-2.5">
          <div className="mb-2 flex items-center justify-between">
            <h2 className="text-[11px] font-bold uppercase tracking-wider text-slate-500">
              Template Library
            </h2>
            <button
              type="button"
              onClick={() => setCreateOpen(true)}
              className="rounded p-0.5 text-slate-500 hover:bg-slate-800 hover:text-slate-200"
              title="Template baru"
              aria-label="Template baru"
            >
              <IconPlus size={13} />
            </button>
          </div>
          <SearchInput value={search} onChange={setSearch} placeholder="Cari template..." ariaLabel="Cari template" />
          <div className="mt-2">
            <Select
              value={category}
              onChange={(event) => setCategory(event.target.value)}
              options={[
                { value: 'ALL', label: 'Semua kategori' },
                ...(categories.data ?? []).map((item) => ({
                  value: item.category,
                  label: `${item.category} (${item.count})`,
                })),
              ]}
            />
          </div>
        </div>

        <div className="min-h-0 flex-1 overflow-y-auto p-1.5 custom-scrollbar">
          {templates.loading ? (
            <div className="p-2">
              <Skeleton lines={6} />
            </div>
          ) : filtered.length === 0 ? (
            <p className="px-2 py-4 text-center text-[11px] text-slate-500">Tidak ada template.</p>
          ) : (
            <ul className="space-y-0.5">
              {filtered.map((template) => (
                <li key={template.id}>
                  <button
                    type="button"
                    onClick={() => setSelectedId(template.id)}
                    className={`w-full rounded px-2 py-1.5 text-left transition-colors ${
                      selectedId === template.id
                        ? 'bg-indigo-600/20 text-indigo-300'
                        : 'text-slate-400 hover:bg-slate-800/60'
                    }`}
                  >
                    <span className="block truncate text-[11px] font-medium">{template.name}</span>
                    <span className="block truncate font-mono text-[10px] text-slate-500">
                      {template.templateKey}@{template.latestVersion ?? 1}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </aside>

      {/* Detail */}
      <div className="min-h-0 flex-1 overflow-y-auto p-4 custom-scrollbar">
        {!active ? (
          <EmptyState
            title="Pilih template"
            description="Pilih template di kiri untuk melihat implementasi, input/output, dan pemakaiannya."
          />
        ) : (
          <Stack gap={3}>
            <div className="flex flex-wrap items-start justify-between gap-2">
              <div className="min-w-0">
                <div className="mb-1 flex flex-wrap items-center gap-1.5">
                  <Badge tone="info" size="xs">
                    {active.category}
                  </Badge>
                  {active.builtin && (
                    <Badge tone="muted" size="xs">
                      bawaan
                    </Badge>
                  )}
                  <code className="rounded bg-slate-950 px-1.5 py-0.5 font-mono text-[10px] text-slate-400">
                    {active.templateKey}@{active.latestVersion ?? 1}
                  </code>
                </div>
                <h2 className="text-sm font-semibold text-slate-100">{active.name}</h2>
                <p className="mt-0.5 max-w-2xl text-[11px] text-slate-400">{active.description}</p>
              </div>
              <Button
                size="sm"
                variant="solid"
                onClick={() => onUseInCanvas(active.templateKey, active.latestVersion ?? 1)}
              >
                Pakai di kanvas
              </Button>
            </div>

            {detail.loading ? (
              <Skeleton lines={6} />
            ) : (
              <TemplateDetailView detail={detail.data} onNotify={onNotify} />
            )}
          </Stack>
        )}
      </div>

      <CreateTemplateModal
        open={createOpen}
        categories={(categories.data ?? []).map((item) => item.category)}
        onClose={() => setCreateOpen(false)}
        onCreated={(templateId) => {
          setCreateOpen(false);
          templates.reload();
          categories.reload();
          setSelectedId(templateId);
          onNotify('Template dibuat.', 'success');
        }}
        onError={(message) => onNotify(message, 'error')}
      />
    </div>
  );
}

/** Detail template: versi, input/output, implementasi, dependensi. */
function TemplateDetailView({
  detail,
  onNotify,
}: {
  detail: FunctionTemplateDetail | null;
  onNotify: (message: string, tone: 'success' | 'error' | 'info') => void;
}) {
  const [tab, setTab] = useState<'implementation' | 'contract' | 'versions' | 'usage'>('implementation');

  const loadDeps = useCallback(
    () => (detail ? templateApi.dependencies(detail.id) : Promise.resolve(null)),
    [detail],
  );
  const deps = useAsync(loadDeps, [detail?.id]);

  if (!detail) {
    return <EmptyState title="Detail tidak tersedia" />;
  }

  const latest: TemplateVersionInfo | undefined =
    detail.versions.find((version) => version.latest) ?? detail.versions[0];

  return (
    <Stack gap={2}>
      <Tabs
        active={tab}
        onChange={(key) => setTab(key as typeof tab)}
        tabs={[
          { key: 'implementation', label: 'Implementasi' },
          { key: 'contract', label: 'Input / Output' },
          { key: 'versions', label: `Versi (${detail.versions.length})` },
          { key: 'usage', label: 'Pemakaian' },
        ]}
      />

      {tab === 'implementation' && latest && (
        <Stack gap={2}>
          <Card
            title={`Versi ${latest.versionNumber} · ${latest.implType}`}
            subtitle={latest.changeNote ?? undefined}
            actions={
              <Badge tone={latest.latest ? 'success' : 'muted'} size="xs">
                {latest.latest ? 'aktif' : 'lama'}
              </Badge>
            }
          >
            <CodeBlock
              code={latest.implementation || '—'}
              language={latest.implType === 'SCRIPT' ? 'expression' : latest.implType.toLowerCase()}
              maxHeight="max-h-72"
            />
          </Card>
          <HintBox tone="info" title="Cara memakai">
            Flow memanggil template dengan kunci <code>{latest.qualifiedKey}</code> melalui node
            Function Template. Versi dikunci eksplisit sehingga perubahan template tidak mengubah
            perilaku flow yang berjalan.
          </HintBox>
        </Stack>
      )}

      {tab === 'contract' && latest && (
        <Grid cols={2}>
          <Card title="Input" subtitle="Parameter bertipe yang diharapkan template">
            {(latest.inputs ?? []).length === 0 ? (
              <p className="text-[11px] text-slate-500">Template ini tidak memerlukan input.</p>
            ) : (
              <ul className="space-y-1">
                {(latest.inputs ?? []).map((input) => (
                  <li
                    key={input.name}
                    className="flex items-start justify-between gap-2 rounded border border-slate-800 bg-slate-950/50 px-2 py-1"
                  >
                    <div className="min-w-0">
                      <p className="font-mono text-[11px] text-slate-200">{input.name}</p>
                      <p className="text-[10px] text-slate-500">{input.description}</p>
                    </div>
                    <div className="flex shrink-0 items-center gap-1">
                      <Badge tone="info" size="xs">
                        {input.type}
                      </Badge>
                      {input.required && (
                        <Badge tone="warning" size="xs">
                          wajib
                        </Badge>
                      )}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </Card>
          <Card title="Output" subtitle="Bentuk nilai yang dikembalikan">
            <CodeBlock code={prettyJson(latest.output)} language="json" maxHeight="max-h-48" />
          </Card>
        </Grid>
      )}

      {tab === 'versions' && (
        <Card title="Riwayat versi" subtitle="Versi lama tetap tersedia untuk flow yang menguncinya">
          <ul className="space-y-1">
            {detail.versions.map((version) => (
              <li
                key={version.id}
                className="flex items-center justify-between gap-2 rounded border border-slate-800 bg-slate-950/50 px-2 py-1.5"
              >
                <div className="min-w-0">
                  <p className="font-mono text-[11px] text-slate-200">
                    v{version.versionNumber} · {version.qualifiedKey}
                  </p>
                  <p className="text-[10px] text-slate-500">
                    {version.changeNote ?? '—'} · {formatDateTime(version.createdAt)}
                  </p>
                </div>
                <div className="flex shrink-0 items-center gap-1">
                  <Badge tone="info" size="xs">
                    {version.implType}
                  </Badge>
                  {version.latest && (
                    <Badge tone="success" size="xs">
                      aktif
                    </Badge>
                  )}
                </div>
              </li>
            ))}
          </ul>
        </Card>
      )}

      {tab === 'usage' && (
        <Card title="Dipakai oleh" subtitle="Flow yang memakai template ini (FR-TPL-05)">
          {deps.loading ? (
            <Skeleton lines={4} />
          ) : (deps.data?.count ?? 0) === 0 ? (
            <EmptyState
              title="Belum dipakai"
              description="Belum ada flow yang memanggil template ini."
            />
          ) : (
            <ul className="space-y-1">
              {(deps.data?.usedBy ?? []).map((usage, index) => (
                <li
                  key={index}
                  className="rounded border border-slate-800 bg-slate-950/50 px-2 py-1 font-mono text-[10px] text-slate-400"
                >
                  {prettyJson(usage)}
                </li>
              ))}
            </ul>
          )}
          <div className="mt-2">
            <Button
              size="xs"
              variant="ghost"
              onClick={() => {
                void navigator.clipboard.writeText(detail.templateKey).then(
                  () => onNotify('Kunci template disalin.', 'success'),
                  () => onNotify('Clipboard tidak tersedia.', 'error'),
                );
              }}
            >
              Salin kunci template
            </Button>
          </div>
        </Card>
      )}
    </Stack>
  );
}

/** Modal pembuatan template baru. */
function CreateTemplateModal({
  open,
  categories,
  onClose,
  onCreated,
  onError,
}: {
  open: boolean;
  categories: string[];
  onClose: () => void;
  onCreated: (templateId: string) => void;
  onError: (message: string) => void;
}) {
  const [templateKey, setTemplateKey] = useState('');
  const [name, setName] = useState('');
  const [category, setCategory] = useState('Utilities');
  const [description, setDescription] = useState('');
  const [implType, setImplType] = useState<'SCRIPT' | 'COMPOSITE' | 'JAVA'>('SCRIPT');
  const [implementation, setImplementation] = useState('=inputs.value');
  const [inputsText, setInputsText] = useState('[\n  { "name": "value", "type": "string", "required": true }\n]');
  const [outputText, setOutputText] = useState('{ "type": "string" }');
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    if (!templateKey.trim() || !name.trim()) {
      onError('Kunci dan nama template wajib diisi.');
      return;
    }
    setBusy(true);
    try {
      let inputs: unknown = [];
      let output: unknown = {};
      try {
        inputs = inputsText.trim() ? JSON.parse(inputsText) : [];
        output = outputText.trim() ? JSON.parse(outputText) : {};
      } catch {
        throw new Error('Format JSON input/output tidak valid.');
      }

      const created = await templateApi.create({
        templateKey: templateKey.trim(),
        name: name.trim(),
        category,
        description: description.trim() || undefined,
        implType,
        implementation,
        inputs,
        output,
      });
      onCreated(created.id);
      setTemplateKey('');
      setName('');
    } catch (cause) {
      onError(errorMessage(cause, 'Gagal membuat template'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={open}
      title="Template baru"
      description="Template SCRIPT memakai ekspresi engine; COMPOSITE memakai sub-flow."
      onClose={onClose}
      width="max-w-xl"
      footer={
        <>
          <Button variant="ghost" size="sm" onClick={onClose} disabled={busy}>
            Batal
          </Button>
          <Button variant="solid" size="sm" loading={busy} onClick={submit}>
            Simpan template
          </Button>
        </>
      }
    >
      <Stack gap={2}>
        <div className="grid grid-cols-2 gap-2">
          <Field label="Kunci template" required hint="mis. util.slugify">
            <TextInput
              value={templateKey}
              onChange={(event) => setTemplateKey(event.target.value)}
              className="font-mono"
              placeholder="util.slugify"
            />
          </Field>
          <Field label="Nama" required>
            <TextInput value={name} onChange={(event) => setName(event.target.value)} />
          </Field>
        </div>
        <div className="grid grid-cols-2 gap-2">
          <Field label="Kategori">
            <Select
              value={category}
              onChange={(event) => setCategory(event.target.value)}
              options={(categories.length ? categories : ['Utilities', 'Security', 'SNAP BI', 'Database', 'Integrasi']).map(
                (item) => ({ value: item, label: item }),
              )}
            />
          </Field>
          <Field label="Jenis implementasi">
            <SegmentedControl
              value={implType}
              onChange={(value) => setImplType(value)}
              size="xs"
              options={[
                { value: 'SCRIPT', label: 'Script' },
                { value: 'COMPOSITE', label: 'Composite' },
                { value: 'JAVA', label: 'Java' },
              ]}
            />
          </Field>
        </div>
        <Field label="Deskripsi">
          <TextArea rows={2} value={description} onChange={(event) => setDescription(event.target.value)} />
        </Field>
        {implType === 'SCRIPT' ? (
          <Field label="Implementasi" hint="Ekspresi diawali =, mis. =upper(inputs.value)">
            <ExpressionEditor
              value={implementation}
              onChange={setImplementation}
              placeholder="=upper(inputs.value)"
              height="7rem"
            />
          </Field>
        ) : (
          <JsonEditor
            title="FlowDefinition"
            value={safeParse(implementation)}
            onChange={(value) => setImplementation(value === null ? '' : JSON.stringify(value, null, 2))}
            placeholder={'{ "name": "Sub-flow", "nodes": [], "edges": [] }'}
            height="9rem"
          />
        )}
        <div className="grid grid-cols-2 gap-2">
          <JsonEditor
            title="Input"
            value={safeParse(inputsText)}
            onChange={(value) => setInputsText(value === null ? '' : JSON.stringify(value, null, 2))}
            placeholder={'[{ "name": "value", "type": "string", "required": true }]'}
            height="7rem"
          />
          <JsonEditor
            title="Output"
            value={safeParse(outputText)}
            onChange={(value) => setOutputText(value === null ? '' : JSON.stringify(value, null, 2))}
            placeholder='{ "type": "string" }'
            height="7rem"
          />
        </div>
      </Stack>
    </Modal>
  );
}
/** Parse JSON dengan aman untuk editor; mengembalikan null bila kosong/rusak. */
function safeParse(text: string): unknown {
  if (!text.trim()) {
    return null;
  }
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}