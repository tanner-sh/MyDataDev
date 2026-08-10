import type { Metadata } from './types';

export function resolveSqlExecutionSchema(
  selectedSchema: string,
  metadata?: Pick<Metadata, 'selectedSchema' | 'currentSchema'> | null
) {
  return selectedSchema || metadata?.selectedSchema || metadata?.currentSchema || undefined;
}
