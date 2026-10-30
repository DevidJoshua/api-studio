import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * Konfigurasi Vite Studio.
 *
 * Proxy /api dan /gw ke Control Plane agar pengembangan lokal tidak
 * memerlukan konfigurasi CORS tambahan dan URL relatif tetap sama seperti
 * saat deploy produksi (Studio disajikan dari origin yang sama).
 *
 * Target backend dapat diubah lewat env agar tidak bentrok dengan port lain
 * di mesin pengembang (mis. Docker Desktop memakai 8080):
 *
 *   $env:APISTUDIO_BACKEND_URL = "http://localhost:8090"; npm run dev
 */
const backendUrl = process.env.APISTUDIO_BACKEND_URL ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: Number(process.env.APISTUDIO_STUDIO_PORT ?? 5173),
    proxy: {
      '/api': { target: backendUrl, changeOrigin: true },
      '/gw': { target: backendUrl, changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
});