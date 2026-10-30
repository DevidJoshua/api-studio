/**
 * ============================================================================
 *  LAPISAN UI — SATU-SATUNYA TEMPAT GAYA VISUAL DIDEKLARASIKAN
 * ============================================================================
 *
 * Semua komponen visual diekspor dari folder `src/ui`. Halaman dan fitur tidak
 * menulis kelas Tailwind mentah untuk kontrol berulang (`button`, `input`,
 * `card`, tabel, modal). Aturan ini membuat tema dapat diganti (mis. ke design
 * system lain) hanya dengan menulis ulang file di folder ini.
 *
 * Kontrak yang harus dipertahankan saat mengganti implementasi:
 *   - Nama komponen dan prop yang diekspor dari file ini.
 *   - Makna setiap `variant`, `size`, dan `tone` (lihat ui/types.ts).
 *   - Perilaku aksesibilitas: fokus terlihat, label terhubung ke input,
 *     dialog menangkap fokus, tombol memiliki tipe yang benar.
 */

export * from './types';
export * from './Button';
export * from './Card';
export * from './Field';
export * from './Feedback';
export * from './Overlay';
export * from './DataDisplay';
export * from './Layout';
export * from './editor';
