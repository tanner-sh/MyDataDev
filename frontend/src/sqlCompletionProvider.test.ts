import { describe, expect, it, vi } from 'vitest';
import { provideSqlCompletions, type SqlCompletionDependencies } from './sqlCompletionProvider';
import { completionTriggerCharacter } from './sqlEditorCompletion';
import type { ObjectColumns } from './types';

const columns: ObjectColumns = { schemaName: 'APP', name: 'BD_ACCOUNT', type: 'TABLE', remarks: '账户', columns: [
  { name: 'CODE', type: 'VARCHAR2', size: 30, nullable: false, remarks: '账户编码' },
  { name: 'COMBINEFORM', type: 'CHAR', size: 1, nullable: true, remarks: '组合形式' }
] };
function deps(): SqlCompletionDependencies {
  return { connectionId: 1, schemaName: 'APP', isCurrent: () => true,
    loadCatalog: vi.fn(async () => ({ objects: [{ ...columns, indexes: [] }] })),
    loadColumns: vi.fn(async () => columns) };
}
function request(text: string, signal = new AbortController().signal) {
  return { text, offset: text.length, signal, explicit: false, triggerCharacter: completionTriggerCharacter(text, text.length) };
}

describe('SQL 补全数据通路', () => {
  it.each([' ', '  ', '\n', '\n  ', '\t'])('WHERE 后 %j 直接加载字段，不等待表目录', async space => {
    const dependencies = deps();
    const result = await provideSqlCompletions(request('select * from BD_ACCOUNT where' + space), dependencies);
    expect(dependencies.loadCatalog).not.toHaveBeenCalled();
    expect(dependencies.loadColumns).toHaveBeenCalledWith(1, { schemaName: 'APP', name: 'BD_ACCOUNT' });
    expect(result?.items.find(item => item.label === 'CODE')).toMatchObject({ remarks: '账户编码', insertText: 'CODE' });
  });

  it('没有正在输入的词时不自动弹出候选', async () => {
    // 一条语句以分号收尾之后，用户还没开始写下一条。此时摊开一整列关键字是打扰。
    expect(await provideSqlCompletions(request('select 1 as val;'), deps())).toBeNull();
    expect(await provideSqlCompletions(request('select ID, ('), deps())).toBeNull();
    expect(await provideSqlCompletions(request('select ID,'), deps())).toBeNull();
  });

  it('显式请求（Ctrl/Cmd+Space）照常给出候选', async () => {
    const result = await provideSqlCompletions({ ...request('select 1 as val;'), explicit: true }, deps());
    expect(result?.items.map(item => item.label)).toEqual(['SELECT', 'INSERT', 'UPDATE', 'DELETE']);
  });

  it('别名点号与条件关键字后的空格仍然自动触发', async () => {
    const dependencies = deps();
    expect(await provideSqlCompletions(request('select * from BD_ACCOUNT a where a.'), dependencies)).not.toBeNull();
    expect(await provideSqlCompletions(request('select * from BD_ACCOUNT where '), dependencies)).not.toBeNull();
  });

  it('表名携带注释，搜索与插入仍使用标识符', async () => {
    const dependencies = deps();
    const result = await provideSqlCompletions(request('select * from bd_'), dependencies);
    expect(result?.items).toEqual([expect.objectContaining({ label: 'BD_ACCOUNT', remarks: '账户', insertText: 'BD_ACCOUNT' })]);
    expect(dependencies.loadColumns).not.toHaveBeenCalled();
  });

  it('同一文档只使用当前语句的表及其字段', async () => {
    const dependencies = deps();
    await provideSqlCompletions(request('select * from old_table; select * from BD_ACCOUNT where '), dependencies);
    expect(dependencies.loadColumns).toHaveBeenCalledTimes(1);
    expect(dependencies.loadColumns).toHaveBeenCalledWith(1, expect.objectContaining({ name: 'BD_ACCOUNT' }));
  });

  it('失败后提供重试提示，不永久缓存关键字列表', async () => {
    const dependencies = deps();
    vi.mocked(dependencies.loadColumns).mockRejectedValueOnce(new Error('temporary'));
    const first = await provideSqlCompletions(request('select * from BD_ACCOUNT where '), dependencies);
    expect(first?.warning).toBe('部分字段加载失败');
    expect(first?.incomplete).toBe(true);
    const next = await provideSqlCompletions(request('select * from BD_ACCOUNT where  '), dependencies);
    expect(next?.items.some(item => item.kind === 'column')).toBe(true);
  });

  it('旧连接或 Schema 的延迟结果不能应用到新上下文', async () => {
    const dependencies = deps();
    let resolve!: (value: ObjectColumns) => void;
    dependencies.loadColumns = () => new Promise(done => { resolve = done; });
    const pending = provideSqlCompletions(request('select * from BD_ACCOUNT where '), dependencies);
    dependencies.isCurrent = () => false;
    resolve(columns);
    expect(await pending).toBeNull();
  });

  it('别名点号只请求对应表，取消的输入不返回旧候选', async () => {
    const dependencies = deps();
    const controller = new AbortController();
    const pending = provideSqlCompletions(request('select * from BD_ACCOUNT a join other b on a.', controller.signal), dependencies);
    controller.abort();
    expect(await pending).toBeNull();
    expect(dependencies.loadColumns).toHaveBeenCalledTimes(1);
  });

  it('切换连接后旧表目录失败不显示到新上下文', async () => {
    const dependencies = deps();
    let reject!: (error: Error) => void;
    dependencies.loadCatalog = () => new Promise((_, fail) => { reject = fail; });
    const pending = provideSqlCompletions(request('select * from bd_'), dependencies);
    dependencies.isCurrent = () => false;
    reject(new Error('old connection failed'));
    expect(await pending).toBeNull();
  });
});
