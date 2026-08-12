import { useEffect, useRef } from 'react';
import { editor as monacoEditor, sqlMonaco } from '../monacoSetup';
import type { SqlEditorProps } from '../sqlEditorTypes';

function cssSize(value: string | number | undefined, fallback: string) {
  if (typeof value === 'number') return `${value}px`;
  return value || fallback;
}

export default function SqlEditor({
  value,
  defaultValue,
  language,
  defaultLanguage,
  theme,
  height,
  width,
  options,
  beforeMount,
  onMount,
  onChange
}: SqlEditorProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const editorRef = useRef<ReturnType<typeof monacoEditor.create> | null>(null);
  const applyingValueRef = useRef(false);
  const beforeMountRef = useRef(beforeMount);
  const onMountRef = useRef(onMount);
  const onChangeRef = useRef(onChange);

  beforeMountRef.current = beforeMount;
  onMountRef.current = onMount;
  onChangeRef.current = onChange;

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    beforeMountRef.current?.(sqlMonaco);
    const instance = monacoEditor.create(container, {
      value: value ?? defaultValue ?? '',
      language: language ?? defaultLanguage,
      theme,
      automaticLayout: true,
      ...options
    });
    editorRef.current = instance;
    const changeListener = instance.onDidChangeModelContent((event) => {
      if (!applyingValueRef.current) onChangeRef.current?.(instance.getValue(), event);
    });
    onMountRef.current?.(instance, sqlMonaco);

    return () => {
      changeListener.dispose();
      const model = instance.getModel();
      instance.dispose();
      model?.dispose();
      if (editorRef.current === instance) editorRef.current = null;
    };
  }, []);

  useEffect(() => {
    const instance = editorRef.current;
    if (!instance || value === undefined || instance.getValue() === value) return;
    applyingValueRef.current = true;
    try {
      instance.setValue(value);
    } finally {
      applyingValueRef.current = false;
    }
  }, [value]);

  useEffect(() => {
    const instance = editorRef.current;
    if (instance && options) instance.updateOptions(options);
  }, [options]);

  useEffect(() => {
    if (theme) monacoEditor.setTheme(theme);
  }, [theme]);

  useEffect(() => {
    const model = editorRef.current?.getModel();
    const nextLanguage = language ?? defaultLanguage;
    if (model && nextLanguage && model.getLanguageId() !== nextLanguage) {
      monacoEditor.setModelLanguage(model, nextLanguage);
    }
  }, [defaultLanguage, language]);

  return <div ref={containerRef} style={{ height: cssSize(height, '100%'), width: cssSize(width, '100%'), overflow: 'hidden' }} />;
}
