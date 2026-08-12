import type * as Monaco from 'monaco-editor';

export type SqlEditorOnMount = (
  editor: Monaco.editor.IStandaloneCodeEditor,
  monaco: typeof Monaco
) => void;

export type SqlEditorProps = {
  value?: string;
  defaultValue?: string;
  language?: string;
  defaultLanguage?: string;
  theme?: string;
  height?: string | number;
  width?: string | number;
  options?: Monaco.editor.IStandaloneEditorConstructionOptions;
  beforeMount?: (monaco: typeof Monaco) => void;
  onMount?: SqlEditorOnMount;
  onChange?: (value: string | undefined, event: Monaco.editor.IModelContentChangedEvent) => void;
};
