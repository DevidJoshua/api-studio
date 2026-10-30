import type { ReactNode } from 'react';
import { TONE_BG, TONE_BORDER, TONE_TEXT, type Tone } from './types';

/** Kartu panel dengan header opsional. */
export function Card({
  title,
  subtitle,
  actions,
  children,
  footer,
  className = '',
  dense = false,
}: {
  title?: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  className?: string;
  dense?: boolean;
}) {
  return (
    <section
      className={`overflow-hidden rounded-xl border border-slate-800 bg-slate-900/70 shadow-lg ${className}`}
    >
      {(title || actions) && (
        <header className="flex flex-wrap items-start justify-between gap-2 border-b border-slate-800 px-4 py-2.5">
          <div className="min-w-0">
            {title && <h3 className="text-sm font-semibold text-slate-100">{title}</h3>}
            {subtitle && <p className="mt-0.5 text-[11px] text-slate-400">{subtitle}</p>}
          </div>
          {actions && <div className="flex shrink-0 items-center gap-1.5">{actions}</div>}
        </header>
      )}
      <div className={dense ? 'p-2' : 'p-4'}>{children}</div>
      {footer && (
        <footer className="border-t border-slate-800 bg-slate-900/60 px-4 py-2 text-[11px] text-slate-400">
          {footer}
        </footer>
      )}
    </section>
  );
}

/** Kartu statistik ringkas. */
export function StatCard({
  label,
  value,
  hint,
  tone = 'default',
  icon,
}: {
  label: string;
  value: ReactNode;
  hint?: ReactNode;
  tone?: Tone;
  icon?: ReactNode;
}) {
  return (
    <div className={`rounded-xl border bg-slate-900/70 px-3 py-2.5 ${TONE_BORDER[tone]}`}>
      <div className="flex items-center justify-between gap-2">
        <p className="text-[10px] font-semibold uppercase tracking-wider text-slate-500">{label}</p>
        {icon && <span className={TONE_TEXT[tone]}>{icon}</span>}
      </div>
      <p className={`mt-1 text-xl font-semibold tabular-nums ${TONE_TEXT[tone]}`}>{value}</p>
      {hint && <p className="mt-0.5 text-[11px] text-slate-500">{hint}</p>}
    </div>
  );
}

/** Panel berbingkai untuk mengelompokkan kontrol di dalam kartu. */
export function Panel({
  title,
  actions,
  children,
  className = '',
}: {
  title?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <div className={`rounded-lg border border-slate-800 bg-slate-950/50 ${className}`}>
      {(title || actions) && (
        <div className="flex items-center justify-between gap-2 border-b border-slate-800 px-3 py-2">
          {title && <h4 className="text-[11px] font-semibold uppercase tracking-wider text-slate-400">{title}</h4>}
          {actions && <div className="flex items-center gap-1">{actions}</div>}
        </div>
      )}
      <div className="p-3">{children}</div>
    </div>
  );
}

/** Kotak info ringkas dengan ikon (mis. petunjuk). */
export function HintBox({
  tone = 'info',
  title,
  children,
}: {
  tone?: Tone;
  title?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className={`rounded-lg border px-3 py-2 ${TONE_BORDER[tone]} ${TONE_BG[tone]}`}>
      {title && <p className={`text-[11px] font-semibold ${TONE_TEXT[tone]}`}>{title}</p>}
      <div className="text-[11px] leading-relaxed text-slate-300">{children}</div>
    </div>
  );
}
