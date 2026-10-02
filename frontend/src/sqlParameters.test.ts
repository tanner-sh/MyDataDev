import { describe, expect, it } from 'vitest';
import { initialParameterValues, normalizeParameterDefinitions, parameterError, reconcileParameters, type SqlParameterDefinition } from './sqlParameters';
import { snippetDraftFrom, snippetRequestBody } from './sqlSnippets';
import { normalizeSqlSession, writeSqlSession } from './sqlSessionStorage';
import { createSqlTab } from './utils';

const definitions: SqlParameterDefinition[] = [
  { name: 'id', type: 'INTEGER', required: true, defaultValue: '9223372036854775807' },
  { name: 'optional', type: 'TEXT', required: false }
];
describe('SQL parameters', () => {
  it('preserves definitions for surviving parameters and drops removed ones', () => {
    expect(reconcileParameters(['id', 'new'], definitions)).toEqual([definitions[0], { name: 'new', type: 'TEXT', required: true }]);
    expect(reconcileParameters(['__proto__', 'constructor'])).toHaveLength(2);
  });
  it('keeps integers as text and distinguishes null from empty strings', () => {
    expect(initialParameterValues(definitions)).toEqual({ id: { type: 'INTEGER', value: '9223372036854775807' }, optional: { type: 'TEXT', value: null } });
    expect(parameterError(definitions[0], '9223372036854775808')).toContain('64');
    expect(parameterError(definitions[0], null)).toBeDefined();
    expect(parameterError(definitions[1], null)).toBeUndefined();
    expect(parameterError(definitions[1], '')).toBeUndefined();
    expect(parameterError({ ...definitions[0], type: 'BOOLEAN' }, 'yes')).toBeDefined();
    expect(parameterError({ ...definitions[0], type: 'DECIMAL' }, '12.34.56')).toBeDefined();
    expect(parameterError({ ...definitions[0], type: 'TIMESTAMP' }, '2026-10-01 12:34:56.123')).toBeUndefined();
  });
  it('round trips template metadata through snippet editing and session recovery', () => {
    const draft = snippetDraftFrom({ id: 1, name: 'template', sql: 'select :id, :optional', useCount: 0, parameters: definitions });
    expect(snippetRequestBody(draft).parameters).toEqual(definitions);
    const tab = { ...createSqlTab(1), parameters: definitions };
    let serialized = '';
    writeSqlSession(1, [tab], tab.id, { getItem: () => serialized, setItem: (_, value) => { serialized = value; } }, 'test');
    expect(normalizeSqlSession(JSON.parse(serialized))?.tabs[0].parameters).toEqual(definitions);
  });
  it('rejects corrupt stored metadata', () => {
    expect(normalizeParameterDefinitions([{ name: 'id', type: 'INVALID', required: true }])).toBeUndefined();
    expect(normalizeParameterDefinitions([definitions[0], definitions[0]])).toBeUndefined();
  });
});
