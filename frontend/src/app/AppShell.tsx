import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { useAuth } from './AuthContext';
import { Badge, Button, ToastStack } from '../ui';
import {
  IconAudit,
  IconBeaker,
  IconChart,
  IconChevronRight,
  IconCpu,
  IconDatabase,
  IconFolder,
  IconGlobe,
  IconLayers,
  IconLogout,
  IconRocket,
  IconSettings,
  IconShield,
  IconTemplate,
  IconTerminal,
  IconZap,
} from './icons';
import { initials } from '../lib/format';

/** Tab utama aplikasi (mengikuti struktur patokan UI). */
export type WorkspaceTab =
  | 'apis'
  | 'canvas'
  | 'databases'
  | 'templates'
  | 'playground'
  | 'deployments'
  | 'approvals'
  | 'observability'
  | 'audit'
  | 'settings';

const TAB_PERMISSIONS: Partial<Record<WorkspaceTab, string[]>> = {
  deployments: ['OBSERVE'],
  observability: ['OBSERVE'],
  audit: ['AUDIT_READ'],
  settings: ['ENVIRONMENT_WRITE', 'SECRET_READ_REFERENCE', 'USER_MANAGE'],
};

/**
 * Kerangka aplikasi: header dengan navigasi tab, indikator API aktif,
 * dan area konten.
 *
 * Tab disembunyikan bila peran pengguna tidak memiliki izinnya, sehingga
 * antarmuka konsisten dengan RBAC backend (BRD Bagian 4).
 */
export function AppShell({
  activeTab,
  onTabChange,
  activeApiLabel,
  dirty,
  children,
  toasts,
  onDismissToast,
  headerActions,
}: {
  activeTab: WorkspaceTab;
  onTabChange: (tab: WorkspaceTab) => void;
  activeApiLabel?: string | null;
  dirty?: boolean;
  children: ReactNode;
  toasts: { id: number; tone: 'default' | 'primary' | 'success' | 'warning' | 'error' | 'info' | 'muted'; message: string }[];
  onDismissToast: (id: number) => void;
  headerActions?: ReactNode;
}) {
  const { user, logout, canAny } = useAuth();
  const [clock, setClock] = useState(() => new Date());

  useEffect(() => {
    const timer = window.setInterval(() => setClock(new Date()), 30_000);
    return () => window.clearInterval(timer);
  }, []);

  const tabs: { key: WorkspaceTab; label: string; icon: ReactNode }[] = [
    { key: 'apis', label: 'APIs & Archives', icon: <IconFolder size={14} /> },
    { key: 'canvas', label: 'Workflow Canvas', icon: <IconLayers size={14} /> },
    { key: 'databases', label: 'Data Sources', icon: <IconDatabase size={14} /> },
    { key: 'templates', label: 'Templates', icon: <IconTemplate size={14} /> },
    { key: 'playground', label: 'Playground', icon: <IconTerminal size={14} /> },
    { key: 'deployments', label: 'Deployments', icon: <IconRocket size={14} /> },
    { key: 'approvals', label: 'Approvals', icon: <IconShield size={14} /> },
    { key: 'observability', label: 'Observability', icon: <IconChart size={14} /> },
    { key: 'audit', label: 'Audit', icon: <IconAudit size={14} /> },
    { key: 'settings', label: 'Settings', icon: <IconSettings size={14} /> },
  ];

  const visibleTabs = tabs.filter((tab) => {
    const required = TAB_PERMISSIONS[tab.key];
    return !required || canAny(required);
  });

  return (
    <div className="flex h-screen select-none flex-col overflow-hidden bg-slate-950 text-slate-300">
      {/* Header */}
      <header className="flex shrink-0 flex-wrap items-center justify-between gap-3 border-b border-slate-800 bg-slate-900/90 px-4 py-2 backdrop-blur">
        <div className="flex min-w-0 items-center gap-3">
          <div className="flex items-center gap-2">
            <div className="grid h-8 w-8 place-items-center rounded-lg bg-gradient-to-tr from-indigo-600 via-indigo-500 to-cyan-400 text-white shadow-lg shadow-indigo-500/25">
              <IconZap size={17} />
            </div>
            <div className="leading-tight">
              <div className="flex items-center gap-1.5">
                <span className="text-sm font-bold tracking-tight text-slate-100">JavaFlow</span>
                <span className="rounded border border-indigo-500/25 bg-indigo-500/10 px-1.5 py-0.5 text-[9px] font-semibold uppercase tracking-wider text-indigo-400">
                  Studio
                </span>
              </div>
              <span className="text-[10px] text-slate-500">Platform API Management Low-Code</span>
            </div>
          </div>

          <div className="hidden h-5 w-px bg-slate-800 lg:block" />

          {/* Tab */}
          <nav className="flex flex-wrap items-center gap-0.5 rounded-lg border border-slate-800/80 bg-slate-950/60 p-1">
            {visibleTabs.map((tab) => (
              <button
                key={tab.key}
                type="button"
                onClick={() => onTabChange(tab.key)}
                className={`flex items-center gap-1.5 rounded-md px-2.5 py-1.5 text-[11px] font-medium transition-colors ${
                  activeTab === tab.key
                    ? 'bg-indigo-600 text-white shadow-md'
                    : 'text-slate-400 hover:bg-slate-800/60 hover:text-slate-200'
                }`}
              >
                {tab.icon}
                <span className="hidden sm:inline">{tab.label}</span>
              </button>
            ))}
          </nav>
        </div>

        <div className="flex items-center gap-2">
          {activeApiLabel && (
            <div className="hidden items-center gap-1.5 rounded-md border border-slate-800 bg-slate-950 px-2.5 py-1 md:flex">
              <span className="h-2 w-2 animate-pulse rounded-full bg-emerald-400" />
              <span className="font-mono text-[10px] text-slate-500">API aktif</span>
              <span className="max-w-[160px] truncate text-[11px] font-semibold text-slate-200">
                {activeApiLabel}
              </span>
              {dirty && <Badge tone="warning" size="xs">belum disimpan</Badge>}
            </div>
          )}

          {headerActions}

          <div className="hidden items-center gap-1.5 rounded-md border border-slate-800 bg-slate-950 px-2 py-1 text-[10px] text-slate-500 lg:flex">
            <IconCpu size={12} />
            {clock.toLocaleTimeString('id-ID', { hour: '2-digit', minute: '2-digit' })}
          </div>

          <div className="flex items-center gap-1.5 rounded-md border border-slate-800 bg-slate-950 px-2 py-1">
            <span className="grid h-6 w-6 place-items-center rounded-full bg-indigo-600/20 text-[10px] font-bold text-indigo-300">
              {initials(user?.displayName ?? user?.username)}
            </span>
            <div className="hidden leading-tight sm:block">
              <p className="text-[11px] font-medium text-slate-200">{user?.displayName}</p>
              <p className="text-[9px] uppercase tracking-wider text-slate-500">{user?.roleLabel}</p>
            </div>
            <Button
              variant="ghost"
              size="xs"
              onClick={() => void logout()}
              title="Keluar"
              aria-label="Keluar"
            >
              <IconLogout size={13} />
            </Button>
          </div>
        </div>
      </header>

      {/* Konten */}
      <main className="min-h-0 flex-1 overflow-hidden">{children}</main>

      <ToastStack toasts={toasts} onDismiss={onDismissToast} />
    </div>
  );
}

/** Item grup untuk sidebar pada tab workspace. */
export function SidebarGroup({
  title,
  action,
  children,
}: {
  title: string;
  action?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="mb-3">
      <div className="mb-1 flex items-center justify-between px-2">
        <span className="text-[10px] font-bold uppercase tracking-wider text-slate-500">{title}</span>
        {action}
      </div>
      {children}
    </div>
  );
}

/** Tombol folder pada sidebar (dipakai di API archive). */
export function FolderButton({
  label,
  count,
  active,
  onClick,
}: {
  label: string;
  count: number;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`flex w-full items-center justify-between rounded-md px-2.5 py-1.5 text-[11px] font-medium transition-colors ${
        active
          ? 'border border-indigo-500/30 bg-indigo-600/20 text-indigo-300'
          : 'text-slate-400 hover:bg-slate-800/60'
      }`}
    >
      <span className="flex min-w-0 items-center gap-1.5">
        <IconFolder size={13} className="shrink-0" />
        <span className="truncate">{label}</span>
      </span>
      <span className="shrink-0 rounded bg-slate-800 px-1.5 py-0.5 font-mono text-[9px] text-slate-400">
        {count}
      </span>
    </button>
  );
}

/** Petunjuk kecil di sidebar. */
export function SidebarHint({
  icon,
  title,
  children,
}: {
  icon?: ReactNode;
  title: string;
  children: ReactNode;
}) {
  return (
    <div className="rounded-lg border border-indigo-800/30 bg-indigo-950/30 p-2.5">
      <p className="mb-0.5 flex items-center gap-1.5 text-[11px] font-semibold text-indigo-300">
        {icon}
        {title}
      </p>
      <p className="text-[10px] leading-relaxed text-slate-400">{children}</p>
    </div>
  );
}

/** Tautan kecil dengan panah. */
export function ArrowLink({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="inline-flex items-center gap-1 text-[11px] font-semibold text-indigo-400 hover:text-indigo-300"
    >
      {label}
      <IconChevronRight size={11} />
    </button>
  );
}

export { IconGlobe, IconBeaker };
