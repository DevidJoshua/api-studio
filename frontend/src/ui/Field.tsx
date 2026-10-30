import type {
  InputHTMLAttributes,
  ReactNode,
  SelectHTMLAttributes,
  TextareaHTMLAttributes,
} from 'react';

/** Label + kontrol dengan hint dan pesan error. */
export function Field({
  label,
  children,
  hint,
  error,
  required,
  className = '',
}: {
  label: ReactNode;
  children: ReactNode;
  hint?: ReactNode;
  error?: ReactNode;
  required?: boolean;
  className?: string;
}) {
  return (
    <label className={`block w-full ${className}`}>
      <span className="mb-1 block text-[11px] font-medium text-slate-400">
        {label}
        {required && <span className="ml-0.5 text-rose-400">*</span>}
      </span>
      {children}
      {error ? (
        <span className="mt-1 block text-[11px] text-rose-400">{error}</span>
      ) : hint ? (
        <span className="mt-1 block text-[11px] text-slate-500">{hint}</span>
      ) : null}
    </label>
  );
}

const INPUT_BASE =
  'w-full rounded-md border bg-slate-950 px-3 py-1.5 text-xs text-slate-200 placeholder:text-slate-600 ' +
  'transition-colors focus:outline-none focus:border-indigo-500 disabled:opacity-50';

export function TextInput({
  invalid = false,
  className = '',
  ...rest
}: InputHTMLAttributes<HTMLInputElement> & { invalid?: boolean }) {
  return (
    <input
      {...rest}
      className={`${INPUT_BASE} ${invalid ? 'border-rose-500' : 'border-slate-700'} ${className}`}
    />
  );
}

export function TextArea({
  rows = 4,
  mono = false,
  invalid = false,
  className = '',
  ...rest
}: TextareaHTMLAttributes<HTMLTextAreaElement> & { mono?: boolean; invalid?: boolean }) {
  return (
    <textarea
      {...rest}
      rows={rows}
      className={`${INPUT_BASE} resize-y leading-relaxed ${mono ? 'font-mono' : ''} ${
        invalid ? 'border-rose-500' : 'border-slate-700'
      } ${className}`}
    />
  );
}

export function Select({
  options,
  invalid = false,
  className = '',
  ...rest
}: SelectHTMLAttributes<HTMLSelectElement> & {
  options: { value: string; label: string }[];
  invalid?: boolean;
}) {
  return (
    <select
      {...rest}
      className={`${INPUT_BASE} ${invalid ? 'border-rose-500' : 'border-slate-700'} ${className}`}
    >
      {options.map((option) => (
        <option key={option.value} value={option.value}>
          {option.label}
        </option>
      ))}
    </select>
  );
}

export function Checkbox({
  label,
  className = '',
  ...rest
}: InputHTMLAttributes<HTMLInputElement> & { label: ReactNode }) {
  return (
    <label className={`flex cursor-pointer items-center gap-2 text-xs text-slate-300 ${className}`}>
      <input
        type="checkbox"
        {...rest}
        className="h-3.5 w-3.5 rounded border-slate-600 bg-slate-900 text-indigo-500 focus:ring-indigo-500/40"
      />
      {label}
    </label>
  );
}

export function Toggle({
  label,
  checked,
  onChange,
  disabled,
}: {
  label: ReactNode;
  checked: boolean;
  onChange: (value: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <label className="flex cursor-pointer items-center gap-2 text-xs text-slate-300">
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        disabled={disabled}
        onClick={() => onChange(!checked)}
        className={`relative h-4 w-8 shrink-0 rounded-full transition-colors ${
          checked ? 'bg-indigo-600' : 'bg-slate-700'
        } ${disabled ? 'opacity-50' : ''}`}
      >
        <span
          className={`absolute top-0.5 h-3 w-3 rounded-full bg-white transition-all ${
            checked ? 'left-4' : 'left-0.5'
          }`}
        />
      </button>
      {label}
    </label>
  );
}

/** Input pencarian dengan ikon. */
export function SearchInput({
  value,
  onChange,
  placeholder = 'Cari...',
  ariaLabel,
}: {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  ariaLabel?: string;
}) {
  return (
    <div className="relative w-full">
      <svg
        className="pointer-events-none absolute left-2.5 top-2 h-3.5 w-3.5 text-slate-500"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        aria-hidden="true"
      >
        <circle cx="11" cy="11" r="7" />
        <path d="m20 20-3.5-3.5" />
      </svg>
      <input
        type="search"
        value={value}
        aria-label={ariaLabel ?? placeholder}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
        className={`${INPUT_BASE} border-slate-700 pl-8`}
      />
    </div>
  );
}

/** Grup tombol pilihan (segmented control). */
export function SegmentedControl<T extends string>({
  options,
  value,
  onChange,
  size = 'xs',
}: {
  options: { value: T; label: ReactNode }[];
  value: T;
  onChange: (value: T) => void;
  size?: 'xs' | 'sm';
}) {
  return (
    <div className="inline-flex items-center gap-1 rounded-md border border-slate-800 bg-slate-950 p-1">
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          onClick={() => onChange(option.value)}
          className={`rounded transition-colors ${
            size === 'xs' ? 'px-2 py-0.5 text-[11px]' : 'px-2.5 py-1 text-xs'
          } font-medium ${
            value === option.value
              ? 'bg-indigo-600 text-white'
              : 'text-slate-400 hover:text-slate-200'
          }`}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}
