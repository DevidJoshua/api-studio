import type { ButtonHTMLAttributes, ReactNode } from 'react';

/** Varian tombol yang tersedia. */
export type ButtonVariant = 'solid' | 'outline' | 'ghost' | 'soft' | 'danger';
export type ButtonSize = 'xs' | 'sm' | 'md';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  icon?: ReactNode;
  iconRight?: ReactNode;
  loading?: boolean;
  block?: boolean;
}

const VARIANT: Record<ButtonVariant, string> = {
  solid:
    'bg-indigo-600 text-white hover:bg-indigo-500 border border-indigo-500 shadow-lg shadow-indigo-600/20',
  outline:
    'bg-slate-800 text-slate-200 hover:bg-slate-700 border border-slate-700',
  ghost:
    'bg-transparent text-slate-400 hover:text-slate-100 hover:bg-slate-800/60 border border-transparent',
  soft:
    'bg-indigo-500/10 text-indigo-300 hover:bg-indigo-500/20 border border-indigo-500/25',
  danger:
    'bg-rose-600/90 text-white hover:bg-rose-500 border border-rose-500/60',
};

const SIZE: Record<ButtonSize, string> = {
  xs: 'text-[11px] px-2 py-1 gap-1 rounded',
  sm: 'text-xs px-3 py-1.5 gap-1.5 rounded-md',
  md: 'text-sm px-4 py-2 gap-2 rounded-md',
};

const DISABLED =
  'disabled:opacity-45 disabled:cursor-not-allowed disabled:hover:bg-slate-800';

/** Tombol standar aplikasi. */
export function Button({
  variant = 'outline',
  size = 'md',
  icon,
  iconRight,
  loading = false,
  block = false,
  className = '',
  children,
  disabled,
  type = 'button',
  ...rest
}: ButtonProps) {
  const classes = [
    'inline-flex items-center justify-center font-medium transition-colors',
    'focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500/60',
    VARIANT[variant],
    SIZE[size],
    block ? 'w-full' : '',
    DISABLED,
    className,
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <button type={type} className={classes} disabled={disabled || loading} {...rest}>
      {loading ? <Spinner size={size === 'md' ? 14 : 12} /> : icon}
      {children}
      {iconRight && !loading ? iconRight : null}
    </button>
  );
}

/** Indikator loading kecil (SVG, tanpa dependensi). */
export function Spinner({ size = 14 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      className="animate-spin"
      aria-hidden="true"
    >
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeOpacity="0.25" strokeWidth="3" />
      <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
    </svg>
  );
}
