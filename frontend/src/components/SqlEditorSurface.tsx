import { Button } from 'antd';
import { memo, useCallback, useEffect, useRef, useState } from 'react';
import type { ComponentType, KeyboardEvent } from 'react';
import { resolveSqlEditorShortcut, shouldLoadSqlEditor, toggleSqlLineComment } from '../sqlEditorSurfaceModel';
import { prefetchWhenIdle } from '../idlePrefetch';
import type { SqlEditorOnMount, SqlEditorProps } from '../sqlEditorTypes';

type SqlEditorComponent = ComponentType<SqlEditorProps>;

export const SqlEditorSurface = memo(function SqlEditorSurface({
  value,
  themeMode,
  options,
  executeDisabled,
  onMount,
  onChange,
  onFormat,
  onExecute
}: {
  value: string;
  themeMode: 'light' | 'dark';
  options: SqlEditorProps['options'];
  executeDisabled: boolean;
  onMount: SqlEditorOnMount;
  onChange: (value: string) => void;
  onFormat: () => void;
  onExecute: () => void;
}) {
  const [userRequested, setUserRequested] = useState(false);
  const [editorComponent, setEditorComponent] = useState<SqlEditorComponent>();
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const [autoLoad, setAutoLoad] = useState(false);
  const mountedRef = useRef(true);
  const loadingRef = useRef(false);
  const componentRef = useRef<SqlEditorComponent | undefined>(undefined);
  const fallbackRef = useRef<HTMLTextAreaElement | null>(null);
  const focusEditorRef = useRef(false);
  const pendingSelectionRef = useRef<{ start: number; end: number } | undefined>(undefined);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const loadEditor = useCallback(async () => {
    if (loadingRef.current || componentRef.current) return;
    loadingRef.current = true;
    setLoading(true);
    setLoadError(false);
    try {
      const module = await import('./SqlEditor');
      if (!mountedRef.current) return;
      focusEditorRef.current = document.activeElement === fallbackRef.current;
      componentRef.current = module.default;
      setEditorComponent(() => module.default);
    } catch {
      if (mountedRef.current) setLoadError(true);
    } finally {
      loadingRef.current = false;
      if (mountedRef.current) setLoading(false);
    }
  }, []);

  const loadRequested = shouldLoadSqlEditor(userRequested, autoLoad);
  useEffect(() => {
    if (loadRequested) void loadEditor();
  }, [loadEditor, loadRequested]);

  // 只有进入 SQL 工作台并挂载此组件后，才在空闲时拉起 Monaco，避免浏览表格等会话
  // 下载全站最大的资源块；文本框仍可立即输入，编辑器就绪后会无损接管内容。
  useEffect(() => prefetchWhenIdle(async () => setAutoLoad(true), window), []);

  function handleFallbackShortcut(event: KeyboardEvent<HTMLTextAreaElement>) {
    const shortcut = resolveSqlEditorShortcut(event);
    if (!shortcut) return;
    if (shortcut === 'execute' && executeDisabled) return;
    event.preventDefault();
    if (shortcut === 'comment') {
      const result = toggleSqlLineComment(value, event.currentTarget.selectionStart, event.currentTarget.selectionEnd);
      pendingSelectionRef.current = { start: result.selectionStart, end: result.selectionEnd };
      onChange(result.value);
      window.requestAnimationFrame(() => {
        const fallback = fallbackRef.current;
        if (fallback && document.activeElement === fallback) {
          fallback.setSelectionRange(result.selectionStart, result.selectionEnd);
        }
      });
    } else if (shortcut === 'format') onFormat();
    else onExecute();
  }

  if (editorComponent) {
    const EditorComponent = editorComponent;
    return (
      <EditorComponent
        height="100%"
        language="sql"
        value={value}
        onMount={(editor, monaco) => {
          onMount(editor, monaco);
          if (!focusEditorRef.current) return;
          editor.focus();
          const model = editor.getModel();
          const pendingSelection = pendingSelectionRef.current;
          if (model && pendingSelection) {
            const start = model.getPositionAt(Math.min(pendingSelection.start, model.getValueLength()));
            const end = model.getPositionAt(Math.min(pendingSelection.end, model.getValueLength()));
            editor.setSelection({
              startLineNumber: start.lineNumber,
              startColumn: start.column,
              endLineNumber: end.lineNumber,
              endColumn: end.column
            });
          } else if (model) {
            editor.setPosition(model.getPositionAt(value.length));
          }
          pendingSelectionRef.current = undefined;
          focusEditorRef.current = false;
        }}
        onChange={(next) => onChange(next || '')}
        theme={themeMode === 'dark' ? 'vs-dark' : 'vs'}
        options={options}
      />
    );
  }

  return (
    <div className="sql-editor-fallback" aria-busy={loading}>
      <textarea
        ref={fallbackRef}
        className="sql-editor-fallback-input"
        aria-label="SQL 编辑器"
        value={value}
        spellCheck={false}
        onFocus={() => {
          focusEditorRef.current = true;
          setUserRequested(true);
        }}
        onBlur={() => {
          focusEditorRef.current = false;
        }}
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={handleFallbackShortcut}
      />
      {(loading || loadError || !loadRequested) && (
        <div className={`sql-editor-fallback-status${loadError ? ' is-error' : ''}`} aria-live="polite">
          {loading && <><span className="editor-loading-dot" aria-hidden="true" /> 正在加载高级编辑器…</>}
          {loadError && <><span>高级编辑器加载失败，当前仍可继续输入。</span><Button type="link" size="small" onClick={() => void loadEditor()}>重试</Button></>}
          {!loading && !loadError && !loadRequested && <span>正在准备编辑器…</span>}
        </div>
      )}
    </div>
  );
});
