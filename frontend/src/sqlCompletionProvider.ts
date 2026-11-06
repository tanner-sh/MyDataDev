import { analyzeSqlCompletion, isSqlCompletionListIncomplete, joinConditionPosition, quoteSqlIdentifier, resolveSqlTableReference, shouldTriggerSqlConditionColumnCompletion, sqlTableQualifier } from './sqlCompletion';
import type { SqlCompletionItem, SqlCompletionRequest, SqlCompletionResult } from './sqlEditorTypes';
import type { CompletionCatalog, ObjectColumns } from './types';
import { sqlKeywordCompletionItems } from './sqlKeywordSuggestions';
import { inferJoinConditions, joinConditionText, type JoinTable } from './joinInference';

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
  // 自动弹出只发生在「正在输入一个词」的时候。刚敲完 ; ( , 这类符号并不是在写词，此时把一整列
  // 候选摊开纯属打扰 —— 用户报的就是一条语句以分号收尾后照样弹出候选。Ctrl/Cmd+Space 是明确
  // 要求，照给；`别名.` 和条件关键字后的空格是另外两处有意为之的触发，也照给。
  if (!request.explicit && !context.replacement.prefix && request.triggerCharacter !== '.'
    && !shouldTriggerSqlConditionColumnCompletion(context)) return null;
  const current = () => !request.signal.aborted && deps.isCurrent();
  if (!current()) return null;
  const prefix = context.replacement.prefix.toLowerCase();
  // 关键字按光标所在位置给：语句开头只有 SELECT/INSERT/UPDATE/DELETE，WHERE 后面一个都不给。
  let items: SqlCompletionItem[] = sqlKeywordCompletionItems(context);
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
      items = [...joinConditionItems(context, references, structures, prefix), ...columns, ...items];
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

/**
 * 停在 `JOIN 表 ON` 后面时，按命名推断出整条条件放在最前面（见 joinInference.ts）。
 * 用的就是上面为字段候选加载的那几份列清单，不多打接口；读不到某张表的列就跳过那张表。
 */
function joinConditionItems(
  context: ReturnType<typeof analyzeSqlCompletion>,
  references: ReturnType<typeof analyzeSqlCompletion>['tables'],
  structures: PromiseSettledResult<ObjectColumns>[],
  prefix: string
): SqlCompletionItem[] {
  const position = joinConditionPosition(context);
  if (!position) return [];
  const toJoinTable = (reference: (typeof references)[number]): JoinTable | undefined => {
    const index = references.indexOf(reference);
    const structure = index >= 0 ? structures[index] : undefined;
    if (!structure || structure.status !== 'fulfilled') return undefined;
    return {
      qualifier: sqlTableQualifier(reference),
      name: structure.value.name,
      columns: structure.value.columns.map(column => ({ name: column.name, type: column.type })),
      primaryKeys: structure.value.primaryKeys ?? []
    };
  };
  const joined = toJoinTable(position.joined);
  if (!joined) return [];
  const existing = position.existing.map(toJoinTable).filter((table): table is JoinTable => Boolean(table));
  const quote = (identifier: string) => quoteSqlIdentifier(identifier, context.replacement.quoteStyle);
  return inferJoinConditions(joined, existing)
    .map((suggestion, index) => {
      const text = joinConditionText(suggestion, quote);
      return {
        label: text,
        kind: 'join' as const,
        insertText: text,
        detail: suggestion.explanation,
        sortText: `00-${String(index).padStart(3, '0')}`
      };
    })
    .filter(item => !prefix || item.label.toLowerCase().startsWith(prefix));
}
