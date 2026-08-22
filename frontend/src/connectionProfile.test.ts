import { describe, expect, it } from 'vitest';
import {
  availableTags,
  connectionGroupName,
  connectionProfileSummary,
  groupConnections,
  matchesKeyword,
  matchesTags,
  UNGROUPED_LABEL
} from './connectionProfile';
import type { Connection } from './types';

function connection(id: number, overrides: Partial<Connection> = {}): Connection {
  return {
    id,
    name: `连接${id}`,
    dbType: 'mysql',
    jdbcUrl: `jdbc:mysql://host/db${id}`,
    environment: 'dev',
    readonly: false,
    capabilities: {
      tableBrowse: true,
      tableEdit: true,
      tableDesign: true,
      explain: true,
      nativeBackupMethods: [],
      nativeRestoreMethods: [],
      schemaObjects: []
    },
    ...overrides
  };
}

describe('groupConnections', () => {
  it('按分组聚合并保留传入顺序', () => {
    const groups = groupConnections([
      connection(1, { groupName: '订单' }),
      connection(2, { groupName: '风控' }),
      connection(3, { groupName: '订单' })
    ]);
    expect(groups.map((group) => group.name)).toEqual(['订单', '风控']);
    expect(groups[0].connections.map((item) => item.id)).toEqual([1, 3]);
  });

  it('未分组永远垫底，即便它先出现', () => {
    const groups = groupConnections([connection(1), connection(2, { groupName: '订单' })]);
    expect(groups.map((group) => group.name)).toEqual(['订单', UNGROUPED_LABEL]);
  });

  it('空白分组名当作未分组', () => {
    expect(connectionGroupName(connection(1, { groupName: '   ' }))).toBe(UNGROUPED_LABEL);
  });

  it('没有连接时返回空数组', () => {
    expect(groupConnections([])).toEqual([]);
  });
});

describe('availableTags', () => {
  it('按出现次数降序，同频按中文拼音序', () => {
    const tags = availableTags([
      connection(1, { tags: ['核心', '只读'] }),
      connection(2, { tags: ['核心'] }),
      connection(3, { tags: ['备份'] })
    ]);
    // 核心出现两次排最前；只读与备份同为一次，按拼音 bei < zhi。
    expect(tags).toEqual(['核心', '备份', '只读']);
  });
});

describe('matchesTags', () => {
  it('多选标签取交集', () => {
    const target = connection(1, { tags: ['核心', '只读'] });
    expect(matchesTags(target, ['核心'])).toBe(true);
    expect(matchesTags(target, ['核心', '只读'])).toBe(true);
    expect(matchesTags(target, ['核心', '备份'])).toBe(false);
  });

  it('没选标签时不筛', () => {
    expect(matchesTags(connection(1), [])).toBe(true);
  });
});

describe('matchesKeyword', () => {
  it('覆盖名称、地址、分组、标签与备注', () => {
    const target = connection(1, { groupName: '订单', tags: ['核心'], description: '张三负责，周三可停' });
    expect(matchesKeyword(target, '订单')).toBe(true);
    expect(matchesKeyword(target, '核心')).toBe(true);
    expect(matchesKeyword(target, '张三')).toBe(true);
    expect(matchesKeyword(target, 'jdbc:mysql')).toBe(true);
    expect(matchesKeyword(target, '不存在')).toBe(false);
  });

  it('大小写与首尾空白不影响命中', () => {
    expect(matchesKeyword(connection(1, { name: 'Prod-Core' }), '  prod-CORE ')).toBe(true);
  });

  it('空关键字不筛', () => {
    expect(matchesKeyword(connection(1), '   ')).toBe(true);
  });
});

describe('connectionProfileSummary', () => {
  it('只在配置了才显示，没有内容时返回空串', () => {
    expect(connectionProfileSummary(connection(1))).toBe('');
    expect(connectionProfileSummary(connection(1, { defaultSchema: 'app' }))).toBe('默认 app');
    expect(connectionProfileSummary(connection(1, { defaultSchema: 'app', initSql: "SET x=1" })))
      .toBe('默认 app · 会话初始化');
  });
});
