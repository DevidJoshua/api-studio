import { useCallback, useEffect, useRef, useState } from 'react';

/** Status pemuatan data asinkron. */
export interface AsyncState<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
  reload: () => void;
  setData: (value: T | null) => void;
}

/**
 * Hook pemuatan data sederhana.
 *
 * @param loader fungsi async; bungkus dengan useCallback bila bergantung pada
 *               parameter, atau sertakan pada `deps`.
 */
export function useAsync<T>(loader: () => Promise<T>, deps: unknown[] = []): AsyncState<T> {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const mounted = useRef(true);
  const loaderRef = useRef(loader);
  loaderRef.current = loader;

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    setLoading(true);
    setError(null);
    loaderRef
      .current()
      .then((result) => {
        if (mounted.current) {
          setData(result);
        }
      })
      .catch((cause: unknown) => {
        if (mounted.current) {
          setError(cause instanceof Error ? cause.message : 'Gagal memuat data');
        }
      })
      .finally(() => {
        if (mounted.current) {
          setLoading(false);
        }
      });
    // deps sengaja dipakai langsung agar pemanggil mengontrol pemuatan ulang
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);

  const reload = useCallback(() => setTick((value) => value + 1), []);
  return { data, loading, error, reload, setData };
}

/** Nilai yang ditunda (untuk pencarian). */
export function useDebounced<T>(value: T, delayMs = 300): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), delayMs);
    return () => window.clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}

/** Notifikasi sementara. */
export interface Toast {
  id: number;
  tone: 'default' | 'primary' | 'success' | 'warning' | 'error' | 'info' | 'muted';
  message: string;
}

/** Pengelola notifikasi dengan penghapusan otomatis. */
export function useToasts(timeoutMs = 4000) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const counter = useRef(0);

  const dismiss = useCallback((id: number) => {
    setToasts((current) => current.filter((toast) => toast.id !== id));
  }, []);

  const push = useCallback(
    (message: string, tone: Toast['tone'] = 'info') => {
      counter.current += 1;
      const id = counter.current;
      setToasts((current) => [...current, { id, tone, message }]);
      window.setTimeout(() => dismiss(id), timeoutMs);
    },
    [dismiss, timeoutMs],
  );

  return { toasts, push, dismiss };
}

/** State yang disimpan di localStorage. */
export function useLocalState<T>(key: string, initial: T): [T, (value: T) => void] {
  const [value, setValue] = useState<T>(() => {
    try {
      const raw = localStorage.getItem(key);
      return raw === null ? initial : (JSON.parse(raw) as T);
    } catch {
      return initial;
    }
  });

  const update = useCallback(
    (next: T) => {
      setValue(next);
      try {
        localStorage.setItem(key, JSON.stringify(next));
      } catch {
        // penyimpanan penuh/diblokir: abaikan, state tetap hidup di memori
      }
    },
    [key],
  );

  return [value, update];
}
