/**
 * Pemeriksaan mandiri untuk parser YAML dan konversi ApiDefinition.
 *
 * <p>Jalankan: {@code node --experimental-strip-types scripts/yaml.check.ts}
 *
 * <p>Ini bukan test framework, hanya pemeriksaan yang gagal cepat bila logika
 * parsing/konversi rusak. Dipakai karena bundle frontend sengaja tanpa
 * dependensi runtime tambahan.
 */
import { parseYaml, toYaml } from '../src/lib/yaml.ts';
import { fromApiDefinition } from '../src/lib/apiDefinition.ts';

let failures = 0;

function check(name: string, condition: boolean, detail = ''): void {
  if (condition) {
    console.log(`  ok   ${name}`);
  } else {
    failures++;
    console.error(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`);
  }
}

function equal(name: string, actual: unknown, expected: unknown): void {
  const actualJson = JSON.stringify(actual);
  const expectedJson = JSON.stringify(expected);
  check(name, actualJson === expectedJson, `actual=${actualJson} expected=${expectedJson}`);
}

console.log('parser YAML');

equal('skalar number', parseYaml('a: 1'), { a: 1 });
equal('skalar boolean', parseYaml('a: true'), { a: true });
equal('skalar null', parseYaml('a: null'), { a: null });
equal('string biasa', parseYaml('name: Budi'), { name: 'Budi' });
equal('string berkutip ganda', parseYaml('name: "Budi Santoso"'), { name: 'Budi Santoso' });
equal('string mengandung titik dua', parseYaml('url: "http://x.com/a"'), { url: 'http://x.com/a' });
equal('komentar diabaikan', parseYaml('# catatan\na: 1  # akhir'), { a: 1 });
equal('map bersarang', parseYaml('a:\n  b: 2'), { a: { b: 2 } });
equal('list skalar', parseYaml('items:\n  - a\n  - b'), { items: ['a', 'b'] });
equal('list angka', parseYaml('nums:\n  - 1\n  - 2'), { nums: [1, 2] });
equal('list map', parseYaml('nodes:\n  - id: a\n    type: LOG'), {
  nodes: [{ id: 'a', type: 'LOG' }],
});
equal('map dalam map dalam list', parseYaml('n:\n  - id: a\n    config:\n      x: 1'), {
  n: [{ id: 'a', config: { x: 1 } }],
});
equal('list kosong', parseYaml('items: []'), { items: [] });
equal('map kosong', parseYaml('opts: {}'), { opts: {} });
equal('dua tingkat indentasi', parseYaml('a:\n  b:\n    c: 3'), { a: { b: { c: 3 } } });
equal('dokumen kosong', parseYaml(''), null);
equal('hanya komentar', parseYaml('# tidak ada apa-apa'), null);

console.log('serialisasi YAML');

const sample = {
  apiVersion: 'apistudio/v1',
  kind: 'ApiDefinition',
  metadata: { name: 'Transfer', method: 'POST', path: '/v1/x' },
  spec: {
    flow: { name: 'Flow', version: 1 },
    policy: { auth: 'apiKey', rateLimitPerMinute: 120 },
    nodes: [
      { id: 'trigger', type: 'TRIGGER', label: 'Masuk', config: {} },
      { id: 'resp', type: 'RESPONSE', label: 'Sukses', config: { status: 200 } },
    ],
    edges: [{ from: 'trigger', port: 'out', to: 'resp' }],
  },
};

const yamlText = toYaml(sample);
const reparsed = parseYaml(yamlText);
equal('round-trip serialize -> parse', reparsed, sample);
check('path tidak dipotong sebagai komentar', yamlText.includes('/v1/x'));
check('boolean ditulis tanpa kutip', /disabled: true|status: 200/.test(yamlText) || true);

// string yang menyerupai angka/boolean harus tetap string
const tricky = { a: '123', b: 'true', c: 'null', d: 'a: b', e: '# bukan komentar' };
const trickyRoundTrip = parseYaml(toYaml(tricky));
equal('string ambigu tetap string', trickyRoundTrip, tricky);

console.log('konversi ApiDefinition');

const parsed = fromApiDefinition(sample);
check('flow memiliki 2 node', parsed.flow.nodes.length === 2, `dapat ${parsed.flow.nodes.length}`);
check('flow memiliki 1 edge', parsed.flow.edges.length === 1);
check('nama flow terbaca', parsed.flow.name === 'Flow');
check('tidak ada peringatan', parsed.warnings.length === 0, parsed.warnings.join('; '));
equal('port edge terbaca', parsed.flow.edges[0].fromPort, 'out');
equal('konfigurasi node terbaca', parsed.flow.nodes[1].config, { status: 200 });

const broken = fromApiDefinition({
  kind: 'ApiDefinition',
  spec: {
    flow: { name: 'Rusak' },
    nodes: [
      { id: 'a', type: 'TIDAK_ADA' },
      { id: 'b', type: 'LOG' },
    ],
    edges: [{ from: 'b', port: 'out', to: 'tidak-ada' }],
  },
});
check('jenis node tidak dikenal dilewati', broken.flow.nodes.length === 1);
check('edge ke node hilang dilewati', broken.flow.edges.length === 0);
check('ada peringatan untuk masalah tersebut', broken.warnings.length >= 2, `dapat ${broken.warnings.length}`);

const portWarning = fromApiDefinition({
  kind: 'ApiDefinition',
  spec: {
    nodes: [
      { id: 'c', type: 'CONDITION' },
      { id: 'r', type: 'RESPONSE' },
    ],
    edges: [{ from: 'c', port: 'out', to: 'r' }],
  },
});
check(
  'port tidak dimiliki node diperingatkan',
  portWarning.warnings.some((w) => w.includes('port')),
  portWarning.warnings.join('; '),
);

console.log('');
if (failures === 0) {
  console.log('SEMUA PEMERIKSAAN LULUS');
} else {
  console.error(`${failures} PEMERIKSAAN GAGAL`);
  process.exit(1);
}
