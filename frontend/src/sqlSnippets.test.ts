import { describe, expect, it } from 'vitest';
import {
  appendSnippetToSql,
  EMPTY_SNIPPET_DRAFT,
  MAX_SNIPPET_NAME_LENGTH,
  MAX_SNIPPET_TAGS,
  parseSnippetTags,
  snippetDraftFrom,
  snippetDraftFromSql,
  snippetListParams,
  snippetRequestBody,
  snippetSubtitle,
  suggestSnippetName,
  validateSnippetDraft,
  type SqlSnippet
} from './sqlSnippets';

const snippet = (overrides: Partial<SqlSnippet> = {}): SqlSnippet => ({
  id: 1, name: '每日对账', sql: 'select 1', useCount: 0, ...overrides
});

describe('suggestSnippetName', () => {
  it('uses the first meaningful line and strips a leading comment marker', () => {
    expect(suggestSnippetName('-- 每日对账\nselect * from orders')).toBe('每日对账');
    expect(suggestSnippetName('\n\n  select * from orders  ')).toBe('select * from orders');
  });

  it('caps the suggestion at the column limit and copes with empty SQL', () => {
    expect(suggestSnippetName('x'.repeat(300))).toHaveLength(MAX_SNIPPET_NAME_LENGTH);
    expect(suggestSnippetName('   \n  ')).toBe('');
    expect(suggestSnippetName('')).toBe('');
  });
});

describe('parseSnippetTags', () => {
  it('splits on both ASCII and full-width separators and drops duplicates', () => {
    expect(parseSnippetTags('对账, 日常，对账  维护')).toEqual(['对账', '日常', '维护']);
  });

  it('returns nothing for blank input and caps the count', () => {
    expect(parseSnippetTags(undefined)).toEqual([]);
    expect(parseSnippetTags('  ,  ,')).toEqual([]);
    expect(parseSnippetTags(Array.from({ length: 20 }, (_, i) => `t${i}`).join(','))).toHaveLength(MAX_SNIPPET_TAGS);
  });
});

describe('validateSnippetDraft', () => {
  it('requires a name and a body', () => {
    expect(validateSnippetDraft(EMPTY_SNIPPET_DRAFT).valid).toBe(false);
    expect(validateSnippetDraft({ ...EMPTY_SNIPPET_DRAFT, name: 'a' }).sqlError).toBe('片段内容不能为空');
    expect(validateSnippetDraft({ ...EMPTY_SNIPPET_DRAFT, sql: 'select 1' }).nameError).toBe('请输入片段名称');
    expect(validateSnippetDraft({ ...EMPTY_SNIPPET_DRAFT, name: ' a ', sql: 'select 1' }).valid).toBe(true);
  });

  it('rejects an over-long name', () => {
    const draft = { ...EMPTY_SNIPPET_DRAFT, name: 'x'.repeat(MAX_SNIPPET_NAME_LENGTH + 1), sql: 'select 1' };
    expect(validateSnippetDraft(draft).nameError).toContain(String(MAX_SNIPPET_NAME_LENGTH));
  });
});

describe('snippetRequestBody', () => {
  it('sends undefined rather than empty strings for the optional fields', () => {
    const body = snippetRequestBody({ ...EMPTY_SNIPPET_DRAFT, name: ' 对账 ', sql: 'select 1' });
    expect(body).toEqual({ name: '对账', description: undefined, sql: 'select 1', dbType: undefined, tags: undefined });
  });

  it('normalises the tags into a comma list', () => {
    const body = snippetRequestBody({ ...EMPTY_SNIPPET_DRAFT, name: 'a', sql: 's', tags: '对账 日常，对账' });
    expect(body.tags).toBe('对账,日常');
  });
});

describe('snippetDraftFrom', () => {
  it('round-trips a saved snippet into an editable draft', () => {
    const draft = snippetDraftFrom(snippet({ description: '说明', dbType: 'mysql', tags: 'a,b' }));
    expect(draft).toEqual({ id: 1, name: '每日对账', description: '说明', sql: 'select 1', dbType: 'mysql', tags: 'a,b' });
  });

  it('turns null-ish fields into empty strings so the form stays controlled', () => {
    expect(snippetDraftFrom(snippet())).toMatchObject({ description: '', dbType: '', tags: '' });
  });

  it('drafts from the current editor content', () => {
    expect(snippetDraftFromSql('-- 清理\ndelete from logs', 'mysql'))
      .toMatchObject({ name: '清理', sql: '-- 清理\ndelete from logs', dbType: 'mysql' });
  });
});

describe('snippetListParams', () => {
  it('omits blanks', () => {
    expect(snippetListParams('', undefined)).toBe('');
    expect(snippetListParams('  ')).toBe('');
    expect(new URLSearchParams(snippetListParams(' orders ', 'mysql')).get('keyword')).toBe('orders');
  });
});

describe('snippetSubtitle', () => {
  it('says 通用 when no database type is pinned', () => {
    expect(snippetSubtitle(snippet())).toBe('通用');
    expect(snippetSubtitle(snippet({ dbType: 'mysql', useCount: 3 }))).toBe('MYSQL · 用过 3 次');
  });
});

describe('appendSnippetToSql', () => {
  it('leaves exactly one blank line between the existing SQL and the snippet', () => {
    expect(appendSnippetToSql('select 1', 'select 2')).toBe('select 1\n\nselect 2');
    expect(appendSnippetToSql('select 1\n\n\n  ', 'select 2')).toBe('select 1\n\nselect 2');
  });

  it('replaces an empty editor outright', () => {
    expect(appendSnippetToSql('', 'select 2')).toBe('select 2');
    expect(appendSnippetToSql('   \n ', 'select 2')).toBe('select 2');
  });
});
