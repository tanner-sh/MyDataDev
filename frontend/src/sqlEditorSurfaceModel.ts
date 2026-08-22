export type SqlEditorShortcut = 'execute' | 'format' | 'comment';

export type SqlLineCommentResult = {
  value: string;
  selectionStart: number;
  selectionEnd: number;
};

/**
 * 是否该把 Monaco 挂上。
 *
 * 除了用户主动聚焦，首屏空闲时也会自动挂载 —— chunk 已经在 App 里预取过，此时是命中
 * 缓存。之前只在聚焦后才加载，导致进入工作台看到的是一个没有高亮、没有行号的纯
 * textarea，还配了一句「聚焦后加载高级编辑器」把实现细节暴露给用户。
 */
export function shouldLoadSqlEditor(userRequested: boolean, autoLoad = false) {
  return userRequested || autoLoad;
}

export function resolveSqlEditorShortcut(event: {
  key: string;
  ctrlKey: boolean;
  metaKey: boolean;
  shiftKey: boolean;
}): SqlEditorShortcut | undefined {
  if (!event.ctrlKey && !event.metaKey) return undefined;
  if (event.key === '/' && !event.shiftKey) return 'comment';
  if (event.key === 'Enter' && !event.shiftKey) return 'execute';
  if (event.key.toLowerCase() === 'f' && event.shiftKey) return 'format';
  return undefined;
}

export function toggleSqlLineComment(value: string, selectionStart: number, selectionEnd: number): SqlLineCommentResult {
  const start = clamp(selectionStart, 0, value.length);
  const end = clamp(selectionEnd, start, value.length);
  const effectiveEnd = end > start && value[end - 1] === '\n' ? end - 1 : end;
  const firstLineStart = start === 0 ? 0 : value.lastIndexOf('\n', start - 1) + 1;
  const lines: Array<{ start: number; text: string }> = [];
  let lineStart = firstLineStart;

  while (lineStart <= effectiveEnd) {
    const newline = value.indexOf('\n', lineStart);
    const rawEnd = newline < 0 ? value.length : newline;
    const contentEnd = rawEnd > lineStart && value[rawEnd - 1] === '\r' ? rawEnd - 1 : rawEnd;
    lines.push({ start: lineStart, text: value.slice(lineStart, contentEnd) });
    if (newline < 0 || newline >= effectiveEnd) break;
    lineStart = newline + 1;
  }

  const nonEmptyLines = lines.filter((line) => /\S/.test(line.text));
  if (nonEmptyLines.length === 0) return { value, selectionStart: start, selectionEnd: end };
  const removeComment = nonEmptyLines.every((line) => /^\s*--/.test(line.text));
  const edits = nonEmptyLines.map((line) => {
    const indentation = line.text.match(/^\s*/)?.[0] || '';
    const editStart = line.start + indentation.length;
    if (!removeComment) return { start: editStart, end: editStart, text: '-- ' };
    const comment = line.text.slice(indentation.length).match(/^-- ?/)?.[0] || '--';
    return { start: editStart, end: editStart + comment.length, text: '' };
  });

  let nextValue = value;
  for (let index = edits.length - 1; index >= 0; index -= 1) {
    const edit = edits[index];
    nextValue = `${nextValue.slice(0, edit.start)}${edit.text}${nextValue.slice(edit.end)}`;
  }

  return {
    value: nextValue,
    selectionStart: mapOffsetThroughEdits(start, edits),
    selectionEnd: mapOffsetThroughEdits(end, edits)
  };
}

function mapOffsetThroughEdits(offset: number, edits: Array<{ start: number; end: number; text: string }>): number {
  let delta = 0;
  for (const edit of edits) {
    if (offset < edit.start) break;
    if (edit.end > edit.start && offset < edit.end) return edit.start + delta + edit.text.length;
    delta += edit.text.length - (edit.end - edit.start);
  }
  return offset + delta;
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}
