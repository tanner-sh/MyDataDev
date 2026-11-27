import { formatTabular } from '../output/format.js';
import type { MetadataResponse, ObjectDdlResponse, ObjectDetail, ObjectSearchResponse } from '../types.js';
import { resolveConnection, type CommandContext } from './context.js';

export async function handleMetaNamespaces(ctx: CommandContext, connRef: string): Promise<void> {
  const connection = await resolveConnection(ctx.client, connRef);
  const meta = await ctx.client.requestJson<MetadataResponse>(`/api/metadata/${connection.id}`);
  const namespaces = meta.namespaces ?? meta.schemas ?? meta.catalogs ?? [];
  const rows = namespaces.map((name) => [name, name === meta.currentSchema ? '*' : '']);
  ctx.stdout.write(
    formatTabular(ctx.format, ['命名空间', '当前'], rows, { currentSchema: meta.currentSchema ?? null, namespaces }) + '\n'
  );
}

export async function handleMetaObjects(
  ctx: CommandContext,
  connRef: string,
  options: { schema?: string; type?: string; keyword?: string; pageSize?: number }
): Promise<void> {
  const connection = await resolveConnection(ctx.client, connRef);
  const query = new URLSearchParams();
  if (options.schema) query.set('schema', options.schema);
  if (options.keyword) query.set('keyword', options.keyword);
  if (options.pageSize) query.set('pageSize', String(options.pageSize));
  const qs = query.toString();
  const meta = await ctx.client.requestJson<MetadataResponse>(`/api/metadata/${connection.id}${qs ? `?${qs}` : ''}`);
  let objects = meta.objects ?? [];
  if (options.type) {
    const wanted = options.type.trim().toUpperCase();
    objects = objects.filter((item) => (item.type || '').toUpperCase().includes(wanted));
  }
  const rows = objects.map((obj) => [obj.schemaName || meta.currentSchema || '', obj.name, obj.type, obj.remarks || '']);
  ctx.stdout.write(formatTabular(ctx.format, ['Schema', '名称', '类型', '备注'], rows, objects) + '\n');
}

export async function handleMetaDescribe(
  ctx: CommandContext,
  connRef: string,
  objectName: string,
  options: { schema?: string }
): Promise<void> {
  const connection = await resolveConnection(ctx.client, connRef);
  const { schema, name } = splitObjectName(objectName, options.schema);
  const query = new URLSearchParams({ objectName: name });
  if (schema) query.set('schemaName', schema);
  const detail = await ctx.client.requestJson<ObjectDetail>(`/api/metadata/${connection.id}/objects/detail?${query}`);

  if (ctx.format === 'json') {
    ctx.stdout.write(JSON.stringify(detail, null, 2) + '\n');
    return;
  }
  const rows = (detail.columns ?? []).map((col) => [
    col.name,
    col.typeName || col.type || '',
    col.nullable === false ? 'NO' : 'YES',
    col.primaryKey || detail.primaryKeys?.includes(col.name) ? 'PK' : '',
    col.defaultValue ?? '',
    col.remarks ?? ''
  ]);
  ctx.stdout.write(formatTabular(ctx.format, ['列名', '类型', '可空', '键', '默认值', '备注'], rows) + '\n');
  if (ctx.format === 'table' && detail.indexes && detail.indexes.length > 0 && !ctx.quiet) {
    ctx.stdout.write('\n索引:\n');
    for (const idx of detail.indexes) {
      ctx.stdout.write(`  ${idx.unique ? '[UNIQUE] ' : ''}${idx.name} (${(idx.columns ?? []).join(', ')})\n`);
    }
  }
}

export async function handleMetaDdl(
  ctx: CommandContext,
  connRef: string,
  objectName: string,
  options: { schema?: string }
): Promise<void> {
  const connection = await resolveConnection(ctx.client, connRef);
  const { schema, name } = splitObjectName(objectName, options.schema);
  const query = new URLSearchParams({ objectName: name });
  if (schema) query.set('schemaName', schema);
  const ddl = await ctx.client.requestJson<ObjectDdlResponse>(`/api/metadata/${connection.id}/objects/ddl?${query}`);
  if (ctx.format === 'json') {
    ctx.stdout.write(JSON.stringify(ddl, null, 2) + '\n');
  } else {
    ctx.stdout.write((ddl.ddl || '').trimEnd() + '\n');
  }
}

export async function handleMetaSearch(
  ctx: CommandContext,
  connRef: string,
  keyword: string,
  options: { schema?: string; limit?: number }
): Promise<void> {
  const connection = await resolveConnection(ctx.client, connRef);
  const query = new URLSearchParams({ keyword });
  if (options.schema) query.set('schema', options.schema);
  if (options.limit) query.set('limit', String(options.limit));
  const res = await ctx.client.requestJson<ObjectSearchResponse>(`/api/metadata/${connection.id}/search?${query}`);
  const items = res.items ?? res.results ?? res.objects ?? [];
  const rows = items.map((item) => [
    item.schemaName || '',
    item.name,
    item.type || item.kind || '',
    item.columnName || '',
    item.remarks || ''
  ]);
  ctx.stdout.write(formatTabular(ctx.format, ['Schema', '对象', '类型', '匹配列', '备注'], rows, items) + '\n');
}

function splitObjectName(raw: string, explicitSchema?: string): { schema?: string; name: string } {
  if (explicitSchema) return { schema: explicitSchema, name: raw };
  const dot = raw.indexOf('.');
  if (dot > 0 && dot < raw.length - 1) {
    return { schema: raw.slice(0, dot), name: raw.slice(dot + 1) };
  }
  return { name: raw };
}
