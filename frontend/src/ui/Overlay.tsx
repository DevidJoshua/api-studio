import { useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import { Button } from './Button';
import type { Tone } from './types';

/** Modal dengan penangkapan fokus sederhana dan tutup via Escape. */
export function Modal({
  open,
  title,
  description,
  children,
  footer,
  onClose,
  width = 'max-w-2xl',
}: {
  open: boolean;
  title: ReactNode;
  description?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  onClose: () => void;
  width?: string;
}) {
  const dialogRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) {
      return;
    }
    const handleKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', handleKey);
    // fokuskan dialog agar Escape dan Tab bekerja di dalamnya
    dialogRef.current?.focus();
    return () => window.removeEventListener('keydown', handleKey);
  }, [open, onClose]);

  if (!open) {
    return null;
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-slate-950/80 p-4 backdrop-blur-sm"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) {
          onClose();
        }
      }}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        tabIndex={-1}
        className={`my-8 w-full ${width} overflow-hidden rounded-xl border border-slate-700 bg-slate-900 shadow-2xl outline-none`}
      >
        <header className="flex items-start justify-between gap-3 border-b border-slate-800 px-4 py-3">
          <div className="min-w-0">
            <h2 className="text-sm font-semibold text-slate-100">{title}</h2>
            {description && <p className="mt-0.5 text-[11px] text-slate-400">{description}</p>}
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Tutup dialog"
            className="shrink-0 text-slate-500 hover:text-slate-200"
          >
            ×
          </button>
        </header>
        <div className="max-h-[70vh] overflow-y-auto px-4 py-3 custom-scrollbar">{children}</div>
        {footer && (
          <footer className="flex items-center justify-end gap-2 border-t border-slate-800 bg-slate-900/60 px-4 py-2.5">
            {footer}
          </footer>
        )}
      </div>
    </div>
  );
}

/** Dialog konfirmasi untuk aksi berisiko. */
export function ConfirmDialog({
  open,
  title,
  children,
  confirmLabel = 'Lanjutkan',
  cancelLabel = 'Batal',
  tone = 'primary',
  busy = false,
  onConfirm,
  onCancel,
}: {
  open: boolean;
  title: ReactNode;
  children: ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  tone?: Tone;
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <Modal
      open={open}
      title={title}
      width="max-w-md"
      onClose={onCancel}
      footer={
        <>
          <Button variant="ghost" size="sm" onClick={onCancel} disabled={busy}>
            {cancelLabel}
          </Button>
          <Button
            variant={tone === 'error' ? 'danger' : 'solid'}
            size="sm"
            onClick={onConfirm}
            loading={busy}
          >
            {confirmLabel}
          </Button>
        </>
      }
    >
      <div className="text-xs leading-relaxed text-slate-300">{children}</div>
    </Modal>
  );
}

export interface ToastMessage {
  id: number;
  tone: Tone;
  message: string;
}

/** Tumpukan notifikasi di kanan bawah. */
export function ToastStack({
  toasts,
  onDismiss,
}: {
  toasts: ToastMessage[];
  onDismiss: (id: number) => void;
}) {
  if (toasts.length === 0) {
    return null;
  }
  return (
    <div className="pointer-events-none fixed bottom-4 right-4 z-50 flex w-80 flex-col gap-2">
      {toasts.map((toast) => (
        <div
          key={toast.id}
          role="status"
          className={`pointer-events-auto flex items-start gap-2 rounded-lg border px-3 py-2 shadow-xl ${
            toast.tone === 'success'
              ? 'border-emerald-500/40 bg-emerald-950/90'
              : toast.tone === 'error'
                ? 'border-rose-500/40 bg-rose-950/90'
                : toast.tone === 'warning'
                  ? 'border-amber-500/40 bg-amber-950/90'
                  : 'border-slate-600 bg-slate-900/95'
          }`}
        >
          <p className="min-w-0 flex-1 text-[11px] leading-relaxed text-slate-100">{toast.message}</p>
          <button
            type="button"
            onClick={() => onDismiss(toast.id)}
            aria-label="Tutup notifikasi"
            className="shrink-0 text-slate-400 hover:text-slate-100"
          >
            ×
          </button>
        </div>
      ))}
    </div>
  );
}
