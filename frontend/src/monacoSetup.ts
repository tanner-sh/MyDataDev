import type * as Monaco from 'monaco-editor';
import { KeyMod } from 'monaco-editor/esm/vs/editor/common/services/editorBaseApi.js';
import { CompletionItemKind, KeyCode } from 'monaco-editor/esm/vs/editor/common/standalone/standaloneEnums.js';
import { create, setModelLanguage, setTheme } from 'monaco-editor/esm/vs/editor/standalone/browser/standaloneEditor.js';
import {
  register,
  registerCompletionItemProvider,
  setLanguageConfiguration,
  setMonarchTokensProvider
} from 'monaco-editor/esm/vs/editor/standalone/browser/standaloneLanguages.js';
import 'monaco-editor/esm/vs/editor/contrib/comment/browser/comment.js';
import 'monaco-editor/esm/vs/editor/contrib/suggest/browser/suggestController.js';
// @ts-ignore - sql.js is a pure data module without types
import { conf, language } from 'monaco-editor/esm/vs/basic-languages/sql/sql.js';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';

self.MonacoEnvironment = {
  getWorker: () => new EditorWorker()
};

register({ id: 'sql', extensions: ['.sql'], aliases: ['SQL'] });
setLanguageConfiguration('sql', conf);
setMonarchTokensProvider('sql', language);

export const editor = { create, setModelLanguage, setTheme };
const languages = {
  register,
  registerCompletionItemProvider,
  setLanguageConfiguration,
  setMonarchTokensProvider,
  CompletionItemKind
};

// Consumers only need these four Monaco surfaces. Keeping this object narrow
// lets Vite discard unrelated public API exports that the namespace import and
// React loader previously kept reachable.
export const sqlMonaco = { editor, languages, KeyCode, KeyMod } as unknown as typeof Monaco;
