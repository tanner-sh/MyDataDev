declare module 'monaco-editor/esm/vs/editor/common/services/editorBaseApi.js' {
  export const KeyMod: typeof import('monaco-editor').KeyMod;
}

declare module 'monaco-editor/esm/vs/editor/common/standalone/standaloneEnums.js' {
  export const CompletionItemKind: typeof import('monaco-editor').languages.CompletionItemKind;
  export const KeyCode: typeof import('monaco-editor').KeyCode;
}

declare module 'monaco-editor/esm/vs/editor/standalone/browser/standaloneEditor.js' {
  export const create: typeof import('monaco-editor').editor.create;
  export const setModelLanguage: typeof import('monaco-editor').editor.setModelLanguage;
  export const setTheme: typeof import('monaco-editor').editor.setTheme;
}

declare module 'monaco-editor/esm/vs/editor/standalone/browser/standaloneLanguages.js' {
  export const register: typeof import('monaco-editor').languages.register;
  export const registerCompletionItemProvider: typeof import('monaco-editor').languages.registerCompletionItemProvider;
  export const setLanguageConfiguration: typeof import('monaco-editor').languages.setLanguageConfiguration;
  export const setMonarchTokensProvider: typeof import('monaco-editor').languages.setMonarchTokensProvider;
}
