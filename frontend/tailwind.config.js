/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['Inter', 'system-ui', '-apple-system', 'Segoe UI', 'sans-serif'],
        mono: ['JetBrains Mono', 'ui-monospace', 'SFMono-Regular', 'Consolas', 'monospace'],
      },
      colors: {
        // Palet gelap enterprise: latar slate dengan aksen indigo/cyan.
        panel: '#0f172a',
        'panel-soft': '#131c31',
        edge: '#1e293b',
      },
    },
  },
  plugins: [],
};