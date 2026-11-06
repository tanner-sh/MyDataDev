import { describe, expect, it } from 'vitest';
import { filterRelations, manualRelationProblem, pairKey, relationKey, relationKnowledge, relationText } from './tableRelations';

describe('表关系', () => {
  it('关系键与服务端同一规范：不分方向、不分大小写', () => {
    expect(relationKey('ORG_ORGS', 'PK_ORG', 'gl_voucher', 'pk_org')).toBe('gl_voucher.pk_org=org_orgs.pk_org');
    expect(relationKey('gl_voucher', 'pk_org', 'ORG_ORGS', 'PK_ORG')).toBe('gl_voucher.pk_org=org_orgs.pk_org');
    expect(pairKey({ leftTable: 'b', leftColumn: 'y', rightTable: 'a', rightColumn: 'x' })).toBe('a.x=b.y');
    expect(relationText({ leftTable: 'a', leftColumn: 'x', rightTable: 'b', rightColumn: 'y' })).toBe('a.x = b.y');
  });

  it('把服务端响应拆成确认、历史与否决键', () => {
    const knowledge = relationKnowledge({
      scannedStatements: 10,
      decided: [
        { leftTable: 'a', leftColumn: 'x', rightTable: 'b', rightColumn: 'y', status: 'CONFIRMED', source: 'HISTORY' },
        { leftTable: 'C', leftColumn: 'K', rightTable: 'D', rightColumn: 'K', status: 'REJECTED', source: 'NAMING' }
      ],
      mined: [{ leftTable: 'e', leftColumn: 'f', rightTable: 'g', rightColumn: 'h', hits: 3 }]
    });
    expect(knowledge.confirmed.map((item) => item.leftTable)).toEqual(['a']);
    expect([...knowledge.rejectedKeys]).toEqual(['c.k=d.k']);
    expect(knowledge.mined).toHaveLength(1);
  });

  it('按表名或列名筛选', () => {
    const items = [
      { leftTable: 'gl_voucher', leftColumn: 'pk_org', rightTable: 'org_orgs', rightColumn: 'pk_org' },
      { leftTable: 'gl_detail', leftColumn: 'pk_voucher', rightTable: 'gl_voucher', rightColumn: 'pk_voucher' }
    ];
    expect(filterRelations(items, 'ORG')).toHaveLength(1);
    expect(filterRelations(items, 'voucher')).toHaveLength(2);
    expect(filterRelations(items, '  ')).toBe(items);
  });

  it('手动添加的校验与服务端一致', () => {
    const ok = { leftTable: 'a', leftColumn: 'x', rightTable: 'b', rightColumn: 'y' };
    expect(manualRelationProblem(ok)).toBeNull();
    expect(manualRelationProblem({ ...ok, leftTable: ' ' })).toBe('请填写左侧表名');
    expect(manualRelationProblem({ ...ok, rightTable: 'fba.b' })).toContain('不带 Schema');
    expect(manualRelationProblem({ leftTable: 'a', leftColumn: 'x', rightTable: 'A', rightColumn: 'X' })).toContain('同一张表的同一列');
  });
});
