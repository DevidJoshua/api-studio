import type { ReactNode } from 'react';
import { TONE_BG, TONE_BORDER, TONE_TEXT, type Tone } from './types';

/** Badge status kecil. */
export function Badge({
  children,
  tone = 'default',
  outline = false,
  size = 'sm',
  className = '',
}: {
  children: ReactNode;
  tone?: Tone;
  outline?: boolean;
  size?: 'xs' | 'sm';
  className?: string;
}) {
  const classes = outline
    ? `border ${TONE_BORDER[tone]} ${TONE_TEXT[tone]} bg-transparent`
    : `${TONE_BG[tone]} ${TONE_TEXT[tone]} border ${TONE_BORDER[tone]}`;
  return (
    <span
      className={`inline-flex items-center gap-1 rounded font-semibold ${
        size === 'xs' ? 'px-1.5 py-0.5 text-[10px]' : 'px-2 py-0.5 text-[11px]'
      } ${classes} ${className}`}
    >
      {children}
    </span>
  );
}

/** Alert satu baris untuk pesan penting. */
export function Alert({
  tone = 'info',
  title,
  children,
  onClose,
}: {
  tone?: Tone;
  title?: ReactNode;
  children: ReactNode;
  onClose?: () => void;
}) {
  return (
    <div
      role="alert"
      className={`flex items-start gap-2 rounded-lg border px-3 py-2 ${TONE_BORDER[tone]} ${TONE_BG[tone]}`}
    >
      <div className="min-w-0 flex-1">
        {title && <p className={`text-xs font-semibold ${TONE_TEXT[tone]}`}>{title}</p>}
        <div className="text-[11px] leading-relaxed text-slate-300">{children}</div>
      </div>
      {onClose && (
        <button
          type="button"
          onClick={onClose}
          aria-label="Tutup"
          className="shrink-0 text-slate-500 hover:text-slate-200"
        >
          ×
        </button>
      )}
    </div>
  );
}

/** Kerangka loading. */
export function Skeleton({ lines = 3 }: { lines?: number }) {
  return (
    <div className="space-y-2" aria-hidden="true">
      {Array.from({ length: lines }).map((_, index) => (
        <div key={index} className="h-3.5 w-full animate-pulse rounded bg-slate-800" />
      ))}
    </div>
  );
}

/** Tampilan saat data kosong. */
export function EmptyState({
  title,
  description,
  action,
  icon,
}: {
  title: ReactNode;
  description?: ReactNode;
  action?: ReactNode;
  icon?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 rounded-lg border border-dashed border-slate-800 px-4 py-8 text-center">
      {icon && <div className="text-slate-600">{icon}</div>}
      <p className="text-xs font-semibold text-slate-300">{title}</p>
      {description && <p className="max-w-md text-[11px] leading-relaxed text-slate-500">{description}</p>}
      {action}
    </div>
  );
}

/** Progress bar sederhana (0..100). */
export function Progress({
  value,
  tone = 'primary',
  label,
}: {
  value: number;
  tone?: Tone;
  label?: ReactNode;
}) {
  const clamped = Math.max(0, Math.min(100, Number.isFinite(value) ? value : 0));
  const barColor =
    tone === 'error'
      ? 'bg-rose-500'
      : tone === 'warning'
        ? 'bg-amber-500'
        : tone === 'success'
          ? 'bg-emerald-500'
          : 'bg-indigo-500';
  return (
    <div>
      {label && <div className="mb-1 flex justify-between text-[11px] text-slate-400">{label}</div>}
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-slate-800">
        <div className={`h-full rounded-full transition-all ${barColor}`} style={{ width: `${clamped}%` }} />
      </div>
    </div>
  );
}
