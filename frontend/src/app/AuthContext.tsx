import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { authApi } from '../api/endpoints';
import { setUnauthorizedHandler, tokenStore } from '../api/client';
import type { CurrentUser } from '../api/types';

interface AuthState {
  user: CurrentUser | null;
  loading: boolean;
  login: (username: string, password: string) => Promise<CurrentUser>;
  logout: () => Promise<void>;
  /** True bila pengguna memiliki izin tertentu. */
  can: (permission: string) => boolean;
  /** True bila pengguna memiliki salah satu izin. */
  canAny: (permissions: string[]) => boolean;
}

const AuthContext = createContext<AuthState | null>(null);

/**
 * Penyedia sesi dan otorisasi.
 *
 * Sesi dipulihkan dari token tersimpan saat aplikasi dimuat; bila token sudah
 * tidak valid backend mengembalikan 401 dan handler global membersihkan sesi
 * sehingga pengguna diarahkan kembali ke halaman login.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let active = true;

    setUnauthorizedHandler(() => {
      if (active) {
        setUser(null);
      }
    });

    const restore = async () => {
      if (!tokenStore.get()) {
        setLoading(false);
        return;
      }
      try {
        const me = await authApi.me();
        if (active) {
          setUser(me);
        }
      } catch {
        // token kedaluwarsa/tidak valid: mulai dari keadaan belum login
        tokenStore.clear();
        if (active) {
          setUser(null);
        }
      } finally {
        if (active) {
          setLoading(false);
        }
      }
    };

    void restore();
    return () => {
      active = false;
    };
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const response = await authApi.login(username, password);
    tokenStore.set(response.token);
    setUser(response.user);
    return response.user;
  }, []);

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } catch {
      // logout lokal tetap dilakukan walau server tidak dapat dihubungi
    }
    tokenStore.clear();
    setUser(null);
  }, []);

  const can = useCallback(
    (permission: string) => !!user?.permissions?.includes(permission),
    [user],
  );

  const canAny = useCallback(
    (permissions: string[]) => permissions.some((permission) => can(permission)),
    [can],
  );

  const value = useMemo<AuthState>(
    () => ({ user, loading, login, logout, can, canAny }),
    [user, loading, login, logout, can, canAny],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

/** Akses konteks sesi; gagal keras bila dipakai di luar provider. */
export function useAuth(): AuthState {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth harus dipakai di dalam AuthProvider');
  }
  return context;
}
