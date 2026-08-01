export type SqlEditorShortcut = 'execute' | 'format';

export function shouldLoadSqlEditor(connectionSelected: boolean, userRequested: boolean) {
  return connectionSelected || userRequested;
}

export function resolveSqlEditorShortcut(event: {
  key: string;
  ctrlKey: boolean;
  metaKey: boolean;
  shiftKey: boolean;
}): SqlEditorShortcut | undefined {
  if (!event.ctrlKey && !event.metaKey) return undefined;
  if (event.key === 'Enter' && !event.shiftKey) return 'execute';
  if (event.key.toLowerCase() === 'f' && event.shiftKey) return 'format';
  return undefined;
}
