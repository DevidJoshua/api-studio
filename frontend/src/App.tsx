import { useCallback, useEffect, useState } from 'react';
import { AppShell, type WorkspaceTab } from './app/AppShell';
import { AuthProvider, useAuth } from './app/AuthContext';
import { useToasts } from './app/hooks';
import { apiApi } from './api/endpoints';
import type { ApiDetail } from './api/types';
import { LoginPage } from './pages/LoginPage';
import { ApiArchivePage } from './pages/ApiArchivePage';
import { WorkflowCanvasPage } from './pages/WorkflowCanvasPage';
import { DataSourcesPage, SettingsPage } from './pages/DataSourcesPage';
import { TemplatesPage } from './pages/TemplatesPage';
import { PlaygroundPage } from './pages/PlaygroundPage';
import { DeploymentsPage } from './pages/DeploymentsPage';
import { ApprovalsPage } from './pages/ApprovalsPage';
import { ObservabilityPage } from './pages/ObservabilityPage';
import { AuditPage } from './pages/AuditPage';
import { Button, Spinner } from './ui';
import { IconZap } from './app/icons';

/**
 * Akar aplikasi: menyediakan sesi, navigasi tab, dan notifikasi global.
 *
 * Navigasi memakai state tab (bukan router) agar sesuai struktur patokan UI
 * enterprise: satu kerangka dengan tab workspace, bukan halaman terpisah.
 */
function StudioApp() {
  const { user, loading, canAny } = useAuth();
  const { toasts, push, dismiss } = useToasts();

  const [tab, setTab] = useState<WorkspaceTab>('apis');
  const [selectedGroupId, setSelectedGroupId] = useState<string | null>(null);
  const [selectedApiId, setSelectedApiId] = useState<string | null>(null);
  const [selectedApi, setSelectedApi] = useState<ApiDetail | null>(null);

  /** Muat detail API aktif agar label header selalu segar. */
  const refreshActiveApi = useCallback(async () => {
    if (!selectedApiId) {
      setSelectedApi(null);
      return;
    }
    try {
      setSelectedApi(await apiApi.detail(selectedApiId));
    } catch {
      setSelectedApi(null);
    }
  }, [selectedApiId]);

  useEffect(() => {
    void refreshActiveApi();
  }, [refreshActiveApi]);

  const notify = useCallback(
    (message: string, tone: 'success' | 'error' | 'info') => push(message, tone),
    [push],
  );

  const openApi = useCallback((apiId: string) => {
    setSelectedApiId(apiId);
    setTab('canvas');
  }, []);

  const useTemplateInCanvas = useCallback(
    (templateKey: string, version: number) => {
      if (!selectedApiId) {
        notify('Pilih API terlebih dahulu di tab APIs & Archives.', 'error');
        return;
      }
      // node ditambahkan oleh pengguna di kanvas; arahkan ke kanvas dengan info
      notify(
        `Template ${templateKey}@${version} siap dipakai: tambahkan node Function Template di kanvas.`,
        'info',
      );
      setTab('canvas');
    },
    [selectedApiId, notify],
  );

  if (loading) {
    return (
      <div className="grid min-h-screen place-items-center bg-slate-950">
        <div className="flex flex-col items-center gap-3 text-slate-400">
          <div className="grid h-10 w-10 place-items-center rounded-lg bg-gradient-to-tr from-indigo-600 to-cyan-400 text-white">
            <IconZap size={20} />
          </div>
          <Spinner size={18} />
          <p className="text-[11px]">Memuat sesi...</p>
        </div>
      </div>
    );
  }

  if (!user) {
    return <LoginPage />;
  }

  // penjaga tab: bila peran tidak berizin, kembali ke arsip
  const guard: Partial<Record<WorkspaceTab, string[]>> = {
    deployments: ['OBSERVE'],
    observability: ['OBSERVE'],
    audit: ['AUDIT_READ'],
    settings: ['ENVIRONMENT_WRITE', 'SECRET_READ_REFERENCE', 'USER_MANAGE'],
  };
  const required = guard[tab];
  const allowed = !required || canAny(required);

  return (
    <AppShell
      activeTab={allowed ? tab : 'apis'}
      onTabChange={setTab}
      activeApiLabel={selectedApi ? `${selectedApi.name}` : null}
      toasts={toasts}
      onDismissToast={dismiss}
      headerActions={
        selectedApi ? (
          <Button
            size="xs"
            variant="ghost"
            onClick={() => {
              setTab('apis');
              setSelectedApiId(null);
            }}
          >
            Tutup API
          </Button>
        ) : null
      }
    >
      {!allowed ? (
        <div className="grid h-full place-items-center p-6">
          <div className="max-w-md text-center">
            <h2 className="text-sm font-semibold text-slate-200">Akses ditolak</h2>
            <p className="mt-1 text-[11px] text-slate-400">
              Peran {user.roleLabel} tidak memiliki izin untuk membuka tab ini.
            </p>
          </div>
        </div>
      ) : tab === 'apis' ? (
        <ApiArchivePage
          selectedGroupId={selectedGroupId}
          onSelectGroup={setSelectedGroupId}
          onOpenApi={openApi}
          onNotify={notify}
        />
      ) : tab === 'canvas' ? (
        <WorkflowCanvasPage
          apiId={selectedApiId}
          onNotify={notify}
          onSaved={() => void refreshActiveApi()}
        />
      ) : tab === 'databases' ? (
        <DataSourcesPage onNotify={notify} />
      ) : tab === 'templates' ? (
        <TemplatesPage onNotify={notify} onUseInCanvas={useTemplateInCanvas} />
      ) : tab === 'playground' ? (
        <PlaygroundPage apiId={selectedApiId} onNotify={notify} />
      ) : tab === 'deployments' ? (
        <DeploymentsPage apiId={selectedApiId} onNotify={notify} />
      ) : tab === 'approvals' ? (
        <ApprovalsPage onNotify={notify} />
      ) : tab === 'observability' ? (
        <ObservabilityPage onNotify={notify} />
      ) : tab === 'audit' ? (
        <AuditPage />
      ) : (
        <SettingsPage onNotify={notify} />
      )}
    </AppShell>
  );
}

/** Akar aplikasi dengan penyedia sesi. */
export function App() {
  return (
    <AuthProvider>
      <StudioApp />
    </AuthProvider>
  );
}
