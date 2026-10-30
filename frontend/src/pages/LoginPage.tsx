import { useState } from 'react';
import { useAuth } from '../app/AuthContext';
import { Alert, Button, Field, TextInput } from '../ui';
import { IconLock, IconZap } from '../app/icons';
import { errorMessage } from '../lib/format';

/** Halaman login Studio. */
export function LoginPage() {
  const { login } = useAuth();
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('admin123');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(username.trim(), password);
    } catch (cause) {
      setError(errorMessage(cause, 'Login gagal'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="grid min-h-screen place-items-center bg-slate-950 px-4">
      <div className="w-full max-w-sm">
        <div className="mb-6 flex items-center gap-2.5">
          <div className="grid h-10 w-10 place-items-center rounded-lg bg-gradient-to-tr from-indigo-600 via-indigo-500 to-cyan-400 text-white shadow-lg shadow-indigo-500/25">
            <IconZap size={20} />
          </div>
          <div>
            <p className="text-base font-bold tracking-tight text-slate-100">JavaFlow</p>
            <p className="text-[10px] font-semibold uppercase tracking-wider text-indigo-400">
              Studio
            </p>
          </div>
        </div>

        <form
          onSubmit={submit}
          className="space-y-3 rounded-xl border border-slate-800 bg-slate-900/70 p-5 shadow-xl"
        >
          <div>
            <h1 className="text-sm font-semibold text-slate-100">Masuk ke Studio</h1>
            <p className="mt-0.5 text-[11px] text-slate-400">
              Kelola API, flow, dan deployment low-code.
            </p>
          </div>

          {error && <Alert tone="error">{error}</Alert>}

          <Field label="Username" required>
            <TextInput
              autoFocus
              value={username}
              autoComplete="username"
              onChange={(event) => setUsername(event.target.value)}
              placeholder="admin"
            />
          </Field>

          <Field label="Password" required>
            <TextInput
              type="password"
              value={password}
              autoComplete="current-password"
              onChange={(event) => setPassword(event.target.value)}
              placeholder="••••••••"
            />
          </Field>

          <Button type="submit" variant="solid" block loading={busy}>
            <IconLock size={13} /> Masuk
          </Button>

          <div className="rounded-lg border border-slate-800 bg-slate-950/60 px-2.5 py-2">
            <p className="text-[10px] font-semibold uppercase tracking-wider text-slate-500">
              Akun demo
            </p>
            <ul className="mt-1 space-y-0.5 font-mono text-[10px] text-slate-400">
              <li>admin / admin123 — Administrator</li>
              <li>designer / password123 — API Designer</li>
              <li>reviewer / password123 — Reviewer</li>
              <li>qa / password123 — QA</li>
              <li>operator / password123 — Operator</li>
              <li>auditor / password123 — Auditor</li>
            </ul>
          </div>
        </form>
      </div>
    </div>
  );
}
