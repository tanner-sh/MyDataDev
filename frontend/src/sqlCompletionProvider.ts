import { analyzeSqlCompletion, isSqlCompletionListIncomplete, quoteSqlIdentifier, resolveSqlTableReference, shouldTriggerSqlConditionColumnCompletion, sqlTableQualifier } from './sqlCompletion';
import type { SqlCompletionItem, SqlCompletionRequest, SqlCompletionResult } from './sqlEditorTypes';
import type { CompletionCatalog, ObjectColumns } from './types';
import { sqlKeywordCompletionItems } from './utils';

export type SqlCompletionDependencies = {
  connectionId: number | null;
  schemaName: string;
  isCurrent: () => boolean;
  loadCatalog: (connectionId: number, schemaName: string, prefix: string) => Promise<CompletionCatalog>;
  loadColumns: (connectionId: number, object: { schemaName?: string; name: string }) => Promise<ObjectColumns>;
};

export async function provideSqlCompletions(request: SqlCompletionRequest, deps: SqlCompletionDependencies): Promise<SqlCompletionResult | null> {
  const context = analyzeSqlCompletion(request.text, request.offset);
  if (context.insideCommentOrString || context.mode === 'none') return null;
  if (request.triggerCharacter === ' ' && !shouldTriggerSqlConditionColumnCompletion(context)) return null;
  const current = () => !request.signal.aborted && deps.isCurrent();
  if (!current()) return null;
  const prefix = context.replacement.prefix.toLowerCase();
  let items: SqlCompletionItem[] = sqlKeywordCompletionItems();
  let warning: string | undefined;
  if (deps.connectionId != null) {
    const connectionId = deps.connectionId;
    if (context.mode === 'table') {
      const catalog = await deps.loadCatalog(connectionId, deps.schemaName, prefix).catch(error => {
        if (!current()) return null;
        throw error;
      });
      if (!catalog || !current()) return null;
      items = catalog.objects.filter(object => !prefix || object.name.toLowerCase().startsWith(prefix)).map(object => ({
        label: object.name,
        kind: 'table',
        insertText: quoteSqlIdentifier(object.name, context.replacement.quoteStyle),
        remarks: object.remarks?.trim() || undefined,
        detail: `${object.schemaName || catalog.selectedSchema || deps.schemaName} · ${object.type.toUpperCase().includes('VIEW') ? '视图' : '表'}`,
        sortText: `1-${object.name.toLowerCase()}`
      }));
    } else {
      const table = context.mode === 'qualified-column' ? resolveSqlTableReference(context, context.qualifierParts) : undefined;
      const references = context.mode === 'qualified-column' ? table ? [table] : [] : context.tables;
      const structures = await Promise.allSettled(references.map(reference => deps.loadColumns(connectionId, {
        schemaName: reference.schemaName || deps.schemaName,
        name: reference.name
      })));
      const qualify = context.mode === 'column' && context.qualifyColumns;
      const columns: SqlCompletionItem[] = structures.flatMap((structure, index) => {
        if (structure.status === 'rejected') return [];
        const object = structure.value;
        const table = references[index];
        return object.columns.filter(column => !prefix || column.name.toLowerCase().startsWith(prefix)).map(column => ({
          label: qualify ? `${sqlTableQualifier(table)}.${column.name}` : column.name,
          kind: 'column',
          insertText: `${qualify ? sqlTableQualifier(table) + '.' : ''}${quoteSqlIdentifier(column.name, context.replacement.quoteStyle)}`,
          remarks: column.remarks?.trim() || undefined,
          detail: `${object.schemaName ? object.schemaName + '.' : ''}${object.name} · ${column.type}`,
          sortText: `0-${column.name.toLowerCase()}`
        }));
      });
      if (structures.some(result => result.status === 'rejected')) warning = '部分字段加载失败';
      items = [...columns, ...items];
    }
  }
  if (!current()) return null;
  return {
    range: { start: context.replacement.start, end: context.replacement.end },
    items,
    incomplete: Boolean(warning) || isSqlCompletionListIncomplete(context, items.some(item => item.kind === 'column')),
    warning
  };
}
