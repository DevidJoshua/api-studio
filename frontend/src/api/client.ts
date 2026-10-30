/**
 * Klien HTTP tunggal untuk Control Plane.
 *
 * Semua pemanggilan backend melewati modul ini sehingga penanganan token,
 * error, dan pemetaan 401 terpusat. Halaman tidak pernah memanggil fetch
 * langsung, sehingga mengganti transport (BFF, GraphQL, mock) hanya perlu
 * mengubah file ini.
 */

const TOKEN_KEY = 'apistudio.token';

/** Error API dengan status dan pesan dari backend. */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

/** Penyimpanan token sesi. */
export const tokenStore = {
  get(): string | null {
    try {
      return localStorage.getItem(TOKEN_KEY);
    } catch {
      return null;
    }
  },
  set(token: string): void {
    localStorage.setItem(TOKEN_KEY, token);
  },
  clear(): void {
    localStorage.removeItem(TOKEN_KEY);
  },
};

let onUnauthorized: (() => void) | null = null;

/** Daftarkan handler ketika sesi tidak valid (401). */
export function setUnauthorizedHandler(handler: () => void): void {
  onUnauthorized = handler;
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  /** Query string; nilai undefined/null/'' dilewati. */
  query?: Record<string, string | number | boolean | undefined | null>;
  /** Kirim tanpa header Authorization (mis. login). */
  anonymous?: boolean;
}

function buildUrl(path: string, query?: RequestOptions['query']): string {
  const url = new URL(path, window.location.origin);
  if (query) {
    Object.entries(query).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') {
        url.searchParams.set(key, String(value));
      }
    });
  }
  return url.pathname + url.search;
}

/** Jalankan request ke backend; melempar ApiError bila status bukan 2xx. */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, query, anonymous = false } = options;
  const headers: Record<string, string> = { Accept: 'application/json' };

  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (!anonymous) {
    const token = tokenStore.get();
    if (token) {
      headers.Authorization = `Bearer ${token}`;
    }
  }

  let response: Response;
  try {
    response = await fetch(buildUrl(path, query), {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (cause) {
    throw new ApiError(0, 'Tidak dapat menghubungi server. Periksa koneksi backend.');
  }

  if (response.status === 401 && !anonymous) {
    tokenStore.clear();
    onUnauthorized?.();
    throw new ApiError(401, 'Sesi berakhir. Silakan login kembali.');
  }

  const text = await response.text();
  const data = text ? safeParse(text) : null;

  if (!response.ok) {
    throw new ApiError(response.status, extractMessage(data, response.status));
  }
  return data as T;
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

/** Ambil pesan error yang berguna dari berbagai bentuk respons backend. */
function extractMessage(data: unknown, status: number): string {
  if (data && typeof data === 'object') {
    const record = data as Record<string, unknown>;
    // GlobalExceptionHandler memakai "error", sebagian endpoint "message"
    const candidate = record.error ?? record.message;
    if (candidate) {
      return String(candidate);
    }
  }
  if (typeof data === 'string' && data.trim()) {
    return data.slice(0, 300);
  }
  return `Permintaan gagal (HTTP ${status})`;
}

/** Helper HTTP ringkas. */
export const http = {
  get: <T>(path: string, query?: RequestOptions['query']) => request<T>(path, { query }),
  post: <T>(path: string, body?: unknown, query?: RequestOptions['query']) =>
    request<T>(path, { method: 'POST', body, query }),
  put: <T>(path: string, body?: unknown, query?: RequestOptions['query']) =>
    request<T>(path, { method: 'PUT', body, query }),
  del: <T>(path: string, query?: RequestOptions['query']) =>
    request<T>(path, { method: 'DELETE', query }),
};
