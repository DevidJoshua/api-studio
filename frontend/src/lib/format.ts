/** Format tanggal & waktu ke bentuk yang ringkas dan dapat dibaca. */
export function formatDateTime(value?: string | null): string {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '—';
  }
  return date.toLocaleString('id-ID', {
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/** Waktu relatif singkat (mis. "3 menit lalu"). */
export function timeAgo(value?: string | null): string {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '—';
  }
  const seconds = Math.round((Date.now() - date.getTime()) / 1000);
  if (seconds < 5) {
    return 'baru saja';
  }
  if (seconds < 60) {
    return `${seconds} detik lalu`;
  }
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) {
    return `${minutes} menit lalu`;
  }
  const hours = Math.round(minutes / 60);
  if (hours < 24) {
    return `${hours} jam lalu`;
  }
  const days = Math.round(hours / 24);
  if (days < 30) {
    return `${days} hari lalu`;
  }
  return formatDateTime(value);
}

/** Durasi dalam milidetik ke teks pendek. */
export function formatDuration(ms?: number | null): string {
  if (ms === null || ms === undefined || Number.isNaN(ms)) {
    return '—';
  }
  if (ms < 1000) {
    return `${Math.round(ms)} ms`;
  }
  if (ms < 60_000) {
    return `${(ms / 1000).toFixed(2)} s`;
  }
  return `${(ms / 60_000).toFixed(1)} mnt`;
}

/** Angka dengan pemisah ribuan. */
export function formatNumber(value?: number | null): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return '—';
  }
  return value.toLocaleString('id-ID');
}

/** JSON pretty-print yang toleran terhadap nilai non-JSON. */
export function prettyJson(value: unknown, fallback = '—'): string {
  if (value === null || value === undefined) {
    return fallback;
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

/** Parse JSON dengan pesan error yang jelas (untuk editor). */
export function parseJsonOrThrow(text: string, fieldName: string): unknown {
  if (!text.trim()) {
    return null;
  }
  try {
    return JSON.parse(text);
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : 'JSON tidak valid';
    throw new Error(`${fieldName}: ${message}`);
  }
}

/** Ambil pesan error yang ramah dari nilai apa pun. */
export function errorMessage(cause: unknown, fallback = 'Terjadi kesalahan'): string {
  if (cause instanceof Error && cause.message) {
    return cause.message;
  }
  if (typeof cause === 'string' && cause.trim()) {
    return cause;
  }
  return fallback;
}

/** Ringkas teks panjang dengan elipsis. */
export function truncate(value: string | null | undefined, max = 80): string {
  if (!value) {
    return '';
  }
  return value.length <= max ? value : `${value.slice(0, max - 1)}…`;
}

/** Ambil inisial untuk avatar. */
export function initials(name: string | null | undefined): string {
  if (!name) {
    return '?';
  }
  const parts = name.trim().split(/\s+/).slice(0, 2);
  return parts.map((part) => part.charAt(0).toUpperCase()).join('') || '?';
}
