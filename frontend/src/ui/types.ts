
/**
 * ============================================================================
 *  KOMPONEN UI DASAR — GAYA DARK ENTERPRISE
 * ============================================================================
 *
 * Semua halaman mengimpor komponen dari folder `src/ui`, tidak menulis kelas
 * Tailwind mentah untuk kontrol berulang. Tujuannya: bila tema berubah (mis.
 * pindah ke light mode atau design system lain), perubahan terpusat di sini.
 *
 * Palet mengikuti patokan `enterprise_api_builder.tsx`: latar slate gelap,
 * aksen indigo/cyan, tipografi kecil rapat, dan border tipis.
 */

export type Tone = 'default' | 'primary' | 'success' | 'warning' | 'error' | 'info' | 'muted';

export const TONE_TEXT: Record<Tone, string> = {
  default: 'text-slate-200',
  primary: 'text-indigo-400',
  success: 'text-emerald-400',
  warning: 'text-amber-400',
  error: 'text-rose-400',
  info: 'text-cyan-400',
  muted: 'text-slate-500',
};

export const TONE_BG: Record<Tone, string> = {
  default: 'bg-slate-800/60',
  primary: 'bg-indigo-500/10',
  success: 'bg-emerald-500/10',
  warning: 'bg-amber-500/10',
  error: 'bg-rose-500/10',
  info: 'bg-cyan-500/10',
  muted: 'bg-slate-800/40',
};

export const TONE_BORDER: Record<Tone, string> = {
  default: 'border-slate-700',
  primary: 'border-indigo-500/30',
  success: 'border-emerald-500/30',
  warning: 'border-amber-500/30',
  error: 'border-rose-500/30',
  info: 'border-cyan-500/30',
  muted: 'border-slate-800',
};

/** Warna badge untuk method HTTP (konsisten di seluruh aplikasi). */
export function methodTone(method: string): Tone {
  switch (method?.toUpperCase()) {
    case 'GET':
      return 'info';
    case 'POST':
      return 'success';
    case 'PUT':
    case 'PATCH':
      return 'warning';
    case 'DELETE':
      return 'error';
    default:
      return 'default';
  }
}

/** Warna status versi. */
export function versionTone(status: string): Tone {
  switch (status) {
    case 'PUBLISHED':
      return 'success';
    case 'DRAFT':
      return 'warning';
    case 'DEPRECATED':
      return 'muted';
    default:
      return 'default';
  }
}

/** Warna status deployment. */
export function deploymentTone(status: string): Tone {
  switch (status) {
    case 'ACTIVE':
      return 'success';
    case 'PENDING':
      return 'info';
    case 'DRAINING':
      return 'warning';
    case 'FAILED':
      return 'error';
    case 'INACTIVE':
      return 'muted';
    default:
      return 'default';
  }
}

/** Warna status test run. */
export function runTone(status: string): Tone {
  switch (status) {
    case 'PASSED':
      return 'success';
    case 'FAILED':
      return 'warning';
    case 'ERROR':
      return 'error';
    default:
      return 'default';
  }
}

/** Warna status trace node. */
export function traceTone(status: string): Tone {
  switch (status) {
    case 'OK':
      return 'success';
    case 'RETRIED':
      return 'info';
    case 'ERROR':
      return 'error';
    case 'DISABLED':
    case 'SKIPPED':
      return 'muted';
    default:
      return 'default';
  }
}

/** Warna status HTTP. */
export function httpTone(status: number): Tone {
  if (status >= 500) {
    return 'error';
  }
  if (status >= 400) {
    return 'warning';
  }
  if (status >= 200) {
    return 'success';
  }
  return 'default';
}
