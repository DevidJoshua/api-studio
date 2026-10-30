/**
 * Editor kode khusus aplikasi.
 *
 * <p>Empat editor disediakan sesuai jenis isi, masing-masing dengan pewarnaan
 * sintaks dan autocomplete yang relevan:
 * <ul>
 *   <li>{@link JavaEditor} — node Java Code, gaya IntelliJ (saran ctx, JDK, snippet).</li>
 *   <li>{@link SqlEditor} — node Database Query (saran tabel/kolom dari seed).</li>
 *   <li>{@link JsonEditor} — headers, body, mock, seed, assertion (validasi langsung).</li>
 *   <li>{@link ExpressionEditor} — kondisi, argumen template (saran variabel & fungsi).</li>
 * </ul>
 *
 * <p>Semuanya dibangun di atas {@link CodeEditor} yang ringan (textarea +
 * lapisan highlight) sehingga tidak menambah dependensi editor eksternal.
 */

export { CodeEditor } from './CodeEditor';
export type { CodeEditorProps, Suggestion, SuggestContext } from './CodeEditor';
export { JavaEditor } from './JavaEditor';
export type { JavaEditorProps } from './JavaEditor';
export { SqlEditor, schemaFromSeeds } from './SqlEditor';
export type { SqlEditorProps } from './SqlEditor';
export { JsonEditor } from './JsonEditor';
export type { JsonEditorProps } from './JsonEditor';
export {
  ExpressionEditor,
  extractFlowVariables,
  extractBodyFields,
} from './ExpressionEditor';
export type { ExpressionEditorProps } from './ExpressionEditor';
export { YamlEditor } from './YamlEditor';
export type { YamlEditorProps, YamlSchemaNode } from './YamlEditor';
export { API_DEFINITION_SCHEMA } from './apiDefinitionSchema';
export { jsonLanguage, yamlLanguage, javaLanguage, sqlLanguage, expressionLanguage, TOKEN_CLASS } from './tokenizer';
