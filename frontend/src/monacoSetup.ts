import { loader } from '@monaco-editor/react';
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api.js';
// @ts-ignore - sql.js is a pure data module without types
import { conf, language } from 'monaco-editor/esm/vs/basic-languages/sql/sql.js';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';

self.MonacoEnvironment = {
  getWorker: () => new EditorWorker()
};

monaco.languages.register({ id: 'sql', extensions: ['.sql'], aliases: ['SQL'] });
monaco.languages.setLanguageConfiguration('sql', conf);
monaco.languages.setMonarchTokensProvider('sql', language);

loader.config({ monaco });
