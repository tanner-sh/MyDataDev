/**
 * CodeMirror 6 的扩展装配。
 *
 * <p>只装 SQL 工作台真的用得上的东西。CodeMirror 的模块粒度很细，装什么就打包什么 ——
 * 这正是从 Monaco 换过来的理由（同等能力下 gzip 685 KiB → 128 KiB），所以这里不图省事
 * 引 codemirror 那个 all-in-one 元包。</p>
 *
 * <p>主题不写死颜色：全部走 styles.css 顶部那套令牌，深浅两套因此自动跟随，
 * 和 ResultChart、ErDiagram 的做法一致。</p>
 */
import { autocompletion, closeBrackets, closeBracketsKeymap, completionKeymap } from '@codemirror/autocomplete';
import { defaultKeymap, history, historyKeymap, indentWithTab, toggleComment } from '@codemirror/commands';
import { HighlightStyle, bracketMatching, indentOnInput, syntaxHighlighting } from '@codemirror/language';
import { searchKeymap } from '@codemirror/search';
import { EditorState, type Extension } from '@codemirror/state';
import {
  EditorView,
  drawSelection,
  highlightActiveLine,
  highlightActiveLineGutter,
  keymap,
  lineNumbers
} from '@codemirror/view';
import { sql, StandardSQL } from '@codemirror/lang-sql';
import { tags } from '@lezer/highlight';

/** 语法高亮。颜色取自令牌，因此深浅主题共用这一份定义。 */
const sqlHighlightStyle = HighlightStyle.define([
  { tag: tags.keyword, color: 'var(--code-keyword)', fontWeight: '600' },
  { tag: [tags.string, tags.special(tags.string)], color: 'var(--code-string)' },
  { tag: [tags.number, tags.bool, tags.null], color: 'var(--code-number)' },
  { tag: tags.comment, color: 'var(--code-comment)', fontStyle: 'italic' },
  { tag: [tags.operator, tags.punctuation], color: 'var(--code-operator)' },
  { tag: [tags.function(tags.variableName), tags.standard(tags.variableName)], color: 'var(--code-function)' },
  { tag: tags.typeName, color: 'var(--code-type)' }
]);

/**
 * 编辑器外观。
 *
 * <p>行高、字号与内边距沿用换编辑器之前 EDITOR_OPTIONS 里的取值，让这次替换在视觉上
 * 尽量无感。自动换行是必须的：SQL 常常是一条很长的语句，横向滚动会把开头的 select
 * 滚出视野，用户只能看到语句中段。</p>
 */
const appearance = EditorView.theme({
  '&': {
    fontSize: '14px',
    height: '100%',
    backgroundColor: 'var(--surface)',
    color: 'var(--text)'
  },
  '.cm-content': {
    padding: '12px 0',
    lineHeight: '22px',
    fontFamily: 'var(--font-mono, ui-monospace, SFMono-Regular, Menlo, Consolas, monospace)',
    caretColor: 'var(--text)',
    userSelect: 'text'
  },
  '.cm-scroller': { overflow: 'auto', lineHeight: '22px' },
  '.cm-gutters': {
    backgroundColor: 'var(--surface)',
    color: 'var(--text-secondary)',
    border: 'none'
  },
  '.cm-activeLineGutter': { backgroundColor: 'var(--surface-hover)' },
  // CodeMirror 的自绘选区位于内容层下方。当前行如果使用不透明背景，单行选区会被完全
  // 盖住，看起来像鼠标没有选中任何文字；保留 CodeMirror 自带的半透明当前行底色。
  '&.cm-focused .cm-cursor': { borderLeftColor: 'var(--text)' },
  '.cm-selectionBackground, ::selection': {
    backgroundColor: 'var(--primary-soft)'
  },
  // 与 CodeMirror 基础主题的完整选择器保持同等特异性，确保聚焦时使用足够清晰的颜色。
  '&.cm-focused > .cm-scroller > .cm-selectionLayer .cm-selectionBackground': {
    backgroundColor: 'color-mix(in srgb, var(--primary) 38%, transparent)'
  },
  '.cm-tooltip': {
    backgroundColor: 'var(--surface-raised)',
    border: '1px solid var(--border)',
    borderRadius: 'var(--radius-md)'
  },
  '.cm-tooltip-autocomplete > ul > li[aria-selected]': {
    backgroundColor: 'var(--primary-soft)',
    color: 'var(--text)'
  },
  '.cm-tooltip-autocomplete > ul > li': { color: 'var(--text)' }
});

export type SqlEditorCommands = {
  execute?: () => void;
  format?: () => void;
};

/**
 * 工作台的基础扩展集合。
 *
 * <p>快捷键顺序有讲究：自定义的执行/格式化放在 defaultKeymap 之前，否则 Mod-Enter
 * 会先被默认的换行命令吃掉。</p>
 */
export function sqlEditorExtensions(commands: SqlEditorCommands): Extension[] {
  return [
    lineNumbers(),
    highlightActiveLine(),
    highlightActiveLineGutter(),
    drawSelection(),
    history(),
    indentOnInput(),
    bracketMatching(),
    closeBrackets(),
    syntaxHighlighting(sqlHighlightStyle),
    sql({ dialect: StandardSQL, upperCaseKeywords: false }),
    EditorState.allowMultipleSelections.of(true),
    EditorView.lineWrapping,
    appearance,
    keymap.of([
      {
        key: 'Mod-Enter',
        run: () => {
          commands.execute?.();
          return true;
        }
      },
      {
        key: 'Mod-Shift-f',
        run: () => {
          commands.format?.();
          return true;
        }
      },
      // Monaco 的 Ctrl+/ 行注释在这里由 commands 包提供，行为一致。
      { key: 'Mod-/', run: toggleComment },
      indentWithTab
    ]),
    keymap.of([...closeBracketsKeymap, ...defaultKeymap, ...historyKeymap, ...completionKeymap, ...searchKeymap])
  ];
}

export { autocompletion, EditorState, EditorView };
