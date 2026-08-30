import { useEffect, useRef } from 'react';
import { Compartment, StateEffect, StateField } from '@codemirror/state';
import { Decoration, EditorView, type DecorationSet } from '@codemirror/view';
import { autocompletion, EditorState, sqlEditorExtensions } from '../codemirrorSetup';
import type { CompletionContext, CompletionResult } from '@codemirror/autocomplete';
import type { SqlEditorHandle, SqlEditorProps, SqlRange } from '../sqlEditorTypes';
import { completionTriggerCharacter, toEditorCompletion } from '../sqlEditorCompletion';

/** 定义跳转高亮：上层只回答「这段是不是对象引用」，画线和清线在这里。 */
const setDefinitionMark = StateEffect.define<SqlRange | null>();
const definitionMark = Decoration.mark({ class: 'sql-object-definition-link' });
const definitionMarkField = StateField.define<DecorationSet>({
  create: () => Decoration.none,
  update(marks, transaction) {
    let next = marks.map(transaction.changes);
    for (const effect of transaction.effects) {
      if (!effect.is(setDefinitionMark)) continue;
      next = effect.value && effect.value.end > effect.value.start
        ? Decoration.set([definitionMark.range(effect.value.start, effect.value.end)])
        : Decoration.none;
    }
    // 内容一变，记下来的偏移量就失效了。
    return transaction.docChanged && !transaction.effects.some((effect) => effect.is(setDefinitionMark))
      ? Decoration.none
      : next;
  },
  provide: (field) => EditorView.decorations.from(field)
});

/**
 * SQL 编辑器（CodeMirror 6）。
 *
 * <p>对外只暴露 sqlEditorTypes.ts 里那套按字符偏移量表达的接口，CodeMirror 的类型不外泄。
 * 之前这里是 Monaco，业务代码要按 Monaco 的形状构造补全项、在行列与偏移量之间来回换算；
 * 现在换一个编辑器只需要改这个文件。</p>
 */
export default function SqlEditor({
  value,
  themeMode,
  readOnly,
  height,
  onChange,
  onMount,
  onExecute,
  onFormat,
  completionSource,
  onDefinitionProbe,
  onDefinitionActivate
}: SqlEditorProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const viewRef = useRef<EditorView | null>(null);
  const stateFactoryRef = useRef<((document: string) => EditorState) | null>(null);
  const themeCompartment = useRef(new Compartment()).current;
  const readOnlyCompartment = useRef(new Compartment()).current;
  // 回调和动态配置放在 ref 里：普通 React 渲染不重建编辑器，也不会丢掉撤销历史和光标位置。
  const callbacks = useRef({ onChange, onExecute, onFormat, completionSource, onDefinitionProbe, onDefinitionActivate, themeMode, readOnly });
  callbacks.current = { onChange, onExecute, onFormat, completionSource, onDefinitionProbe, onDefinitionActivate, themeMode, readOnly };
  const applyingValueRef = useRef(false);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    let modifierPressed = false;
    let markedRange: SqlRange | null = null;
    let hoveredOffset: number | null = null;
    let definitionProbeActive = false;

    const applyDefinitionMark = (range: SqlRange | null, view: EditorView) => {
      const same = markedRange && range && markedRange.start === range.start && markedRange.end === range.end;
      if (same || (!markedRange && !range)) return;
      markedRange = range;
      view.dispatch({ effects: setDefinitionMark.of(range) });
    };
    const clearDefinitionProbe = (view: EditorView, forgetPointer = false) => {
      if (definitionProbeActive) callbacks.current.onDefinitionProbe?.(null);
      definitionProbeActive = false;
      applyDefinitionMark(null, view);
      if (forgetPointer) hoveredOffset = null;
    };
    const probeAtOffset = (view: EditorView, offset: number | null) => {
      if (!modifierPressed || offset == null || !callbacks.current.onDefinitionProbe) {
        clearDefinitionProbe(view);
        return;
      }
      // 指针仍在当前对象名范围内时，既不必重新扫描整条语句，也不必反复重置预取计时器。
      if (markedRange && offset >= markedRange.start && offset < markedRange.end) return;
      const range = callbacks.current.onDefinitionProbe(offset);
      definitionProbeActive = range != null;
      applyDefinitionMark(range, view);
    };
    const probeAt = (view: EditorView, event: MouseEvent) => {
      hoveredOffset = view.posAtCoords({ x: event.clientX, y: event.clientY });
      if (!modifierPressed) {
        clearDefinitionProbe(view);
        return;
      }
      probeAtOffset(view, hoveredOffset);
    };
    const clearDefinitionAfterDocumentChange = () => {
      // StateField 会在同一笔 transaction 里清掉装饰；这里只同步本地状态并取消上层预取，
      // 避免在 EditorView 正在更新时嵌套 dispatch。
      if (definitionProbeActive) callbacks.current.onDefinitionProbe?.(null);
      definitionProbeActive = false;
      markedRange = null;
    };

    const createEditorState = (document: string) => {
      clearDefinitionAfterDocumentChange();
      return EditorState.create({
        doc: document,
        extensions: [
          ...sqlEditorExtensions({
            execute: () => callbacks.current.onExecute?.(),
            format: () => callbacks.current.onFormat?.()
          }),
          definitionMarkField,
          themeCompartment.of(EditorView.theme({}, { dark: callbacks.current.themeMode === 'dark' })),
          readOnlyCompartment.of(EditorState.readOnly.of(Boolean(callbacks.current.readOnly))),
          autocompletion({
            override: [(context) => completionFor(context, callbacks.current.completionSource)],
            icons: false
          }),
          EditorView.updateListener.of((update) => {
            if (update.docChanged) clearDefinitionAfterDocumentChange();
            if (!update.docChanged || applyingValueRef.current) return;
            callbacks.current.onChange(update.state.doc.toString());
          }),
          EditorView.domEventHandlers({
            mousemove(event, view) {
              modifierPressed = event.ctrlKey || event.metaKey;
              probeAt(view, event);
            },
            mouseleave(_event, view) {
              clearDefinitionProbe(view, true);
            },
            mousedown(event, view) {
              if (event.button !== 0 || !(event.ctrlKey || event.metaKey)) return false;
              const offset = view.posAtCoords({ x: event.clientX, y: event.clientY });
              const range = offset == null ? null : callbacks.current.onDefinitionProbe?.(offset);
              if (offset == null || !range) return false;
              definitionProbeActive = true;
              event.preventDefault();
              event.stopPropagation();
              clearDefinitionProbe(view);
              callbacks.current.onDefinitionActivate?.(offset);
              return true;
            }
          })
        ]
      });
    };
    stateFactoryRef.current = createEditorState;

    const view = new EditorView({
      parent: container,
      state: createEditorState(value)
    });
    viewRef.current = view;

    // 修饰键是在 window 上跟的：光标停着不动、只按下 Ctrl 时也要出现高亮。
    const handleModifierChange = (event: KeyboardEvent) => {
      const pressed = event.ctrlKey || event.metaKey;
      if (pressed === modifierPressed) return;
      modifierPressed = pressed;
      if (pressed) probeAtOffset(view, hoveredOffset);
      else clearDefinitionProbe(view);
    };
    const clearOnBlur = () => {
      modifierPressed = false;
      clearDefinitionProbe(view);
    };
    window.addEventListener('keydown', handleModifierChange);
    window.addEventListener('keyup', handleModifierChange);
    window.addEventListener('blur', clearOnBlur);

    const handle: SqlEditorHandle = {
      getValue: () => view.state.doc.toString(),
      setValue: (next) => {
        applyingValueRef.current = true;
        try {
          replaceDocument(view, next, createEditorState);
        } finally {
          applyingValueRef.current = false;
        }
      },
      focus: () => view.focus(),
      getSelection: () => ({ start: view.state.selection.main.from, end: view.state.selection.main.to }),
      replaceRange: (range, text) => {
        const length = view.state.doc.length;
        view.dispatch({
          changes: { from: Math.min(range.start, length), to: Math.min(range.end, length), insert: text }
        });
      },
      setSelection: (range) => {
        const length = view.state.doc.length;
        view.dispatch({
          selection: { anchor: Math.min(range.start, length), head: Math.min(range.end, length) },
          scrollIntoView: true
        });
      }
    };
    const disposeMount = onMount?.(handle);

    return () => {
      window.removeEventListener('keydown', handleModifierChange);
      window.removeEventListener('keyup', handleModifierChange);
      window.removeEventListener('blur', clearOnBlur);
      clearDefinitionProbe(view, true);
      if (typeof disposeMount === 'function') disposeMount();
      view.destroy();
      if (viewRef.current === view) viewRef.current = null;
      if (stateFactoryRef.current === createEditorState) stateFactoryRef.current = null;
    };
    // EditorView 只建一次；主题、只读状态和外部文档由下面的 effect 应用。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const view = viewRef.current;
    const createEditorState = stateFactoryRef.current;
    if (!view || !createEditorState || view.state.doc.toString() === value) return;
    applyingValueRef.current = true;
    try {
      replaceDocument(view, value, createEditorState);
    } finally {
      applyingValueRef.current = false;
    }
  }, [value]);

  useEffect(() => {
    viewRef.current?.dispatch({
      effects: themeCompartment.reconfigure(EditorView.theme({}, { dark: themeMode === 'dark' }))
    });
  }, [themeCompartment, themeMode]);

  useEffect(() => {
    viewRef.current?.dispatch({
      effects: readOnlyCompartment.reconfigure(EditorState.readOnly.of(Boolean(readOnly)))
    });
  }, [readOnly, readOnlyCompartment]);

  return <div ref={containerRef} className="sql-editor-host" style={{ height: cssSize(height) }} />;
}

function cssSize(value: string | number | undefined): string {
  if (typeof value === 'number') return `${value}px`;
  return value || '100%';
}

/** 外部文档切换不属于当前文档的用户编辑，不能进入或继承撤销栈。 */
function replaceDocument(view: EditorView, value: string, createState: (document: string) => EditorState) {
  // addToHistory(false) 只是不记录这次替换，旧历史仍会被映射到新文档；真正切换文档必须
  // 换一份 EditorState，才能保证在标签 B 按撤销不会把标签 A 的内容写进来。
  view.setState(createState(value));
}

/** 把 CodeMirror 的补全上下文翻译成上层那套按偏移量表达的请求，再把结果翻回来。 */
async function completionFor(
  context: CompletionContext,
  source: SqlEditorProps['completionSource']
): Promise<CompletionResult | null> {
  if (!source) return null;
  const controller = new AbortController();
  // 业务补全会发网络请求，继续输入后旧快照已经无效，必须尽早取消而不是等结果回来再重放。
  context.addEventListener('abort', () => controller.abort(), { onDocChange: true });
  const text = context.state.doc.toString();
  const result = await source({
    text,
    offset: context.pos,
    explicit: context.explicit,
    triggerCharacter: completionTriggerCharacter(text, context.pos, context.explicit),
    signal: controller.signal
  });
  if (!result || controller.signal.aborted || result.items.length === 0) return null;
  return toEditorCompletion(result);
}
