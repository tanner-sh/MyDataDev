import { describe, expect, it } from 'vitest';
import { idSuffixStem, inferJoinConditions, joinConditionText, tableNameMatchesStem, type JoinTable } from './joinInference';

const table = (qualifier: string, name: string, columns: [string, string?][], primaryKeys: string[]): JoinTable =>
  ({ qualifier, name, columns: columns.map(([column, type]) => ({ name: column, type })), primaryKeys });

// NC / BIP 风格：不建外键，靠同名的 pk_xxx 关联。
const org = table('o', 'ORG_ORGS', [['PK_ORG', 'CHAR'], ['NAME', 'VARCHAR2']], ['PK_ORG']);
const voucher = table('v', 'GL_VOUCHER', [['PK_VOUCHER', 'CHAR'], ['PK_ORG', 'CHAR'], ['CREATOR', 'CHAR']], ['PK_VOUCHER']);
const detail = table('d', 'GL_DETAIL', [['PK_DETAIL', 'CHAR'], ['PK_VOUCHER', 'CHAR'], ['PK_ORG', 'CHAR']], ['PK_DETAIL']);

// xxx_id 风格。
const customer = table('c', 't_customer', [['id', 'bigint'], ['name', 'varchar']], ['id']);
const orders = table('o', 'orders', [['id', 'bigint'], ['customer_id', 'bigint'], ['category_id', 'bigint']], ['id']);
const category = table('cat', 'categories', [['id', 'bigint']], ['id']);

describe('JOIN 条件命名推断', () => {
  it('同名主键：子表引用父表的 pk_xxx，两个方向都认', () => {
    const joinOrg = inferJoinConditions(org, [voucher]);
    expect(joinOrg.map((item) => joinConditionText(item))).toEqual(['v.PK_ORG = o.PK_ORG']);
    expect(joinOrg[0].reason).toBe('same-name-key');
    const joinVoucher = inferJoinConditions(voucher, [org]);
    expect(joinVoucher.map((item) => joinConditionText(item))).toEqual(['o.PK_ORG = v.PK_ORG']);
  });

  it('不把两张表都有、但都不是主键的同名列当成关联', () => {
    // GL_DETAIL 和 GL_VOUCHER 都有 PK_ORG，但谁的主键都不是它：连 PK_ORG 会把行数乘爆。
    const conditions = inferJoinConditions(detail, [voucher]).map((item) => joinConditionText(item));
    expect(conditions).toEqual(['v.PK_VOUCHER = d.PK_VOUCHER']);
  });

  it('离新 JOIN 的表最近的那张表排在前面', () => {
    const conditions = inferJoinConditions(org, [voucher, detail]).map((item) => joinConditionText(item));
    expect(conditions).toEqual(['d.PK_ORG = o.PK_ORG', 'v.PK_ORG = o.PK_ORG']);
  });

  it('xxx_id 指向 xxx 表（含复数、带前缀的表名）的主键', () => {
    expect(inferJoinConditions(customer, [orders]).map((item) => joinConditionText(item))).toEqual(['o.customer_id = c.id']);
    expect(inferJoinConditions(category, [orders]).map((item) => joinConditionText(item))).toEqual(['o.category_id = cat.id']);
    expect(inferJoinConditions(orders, [customer]).map((item) => joinConditionText(item))).toEqual(['c.id = o.customer_id']);
    expect(inferJoinConditions(orders, [customer])[0].reason).toBe('id-suffix');
  });

  it('两边主键都叫 id 不算关联', () => {
    expect(inferJoinConditions(customer, [category])).toEqual([]);
  });

  it('类型明显不兼容时不推断，认不出类型时放行', () => {
    const textOrders = table('o', 'orders', [['customer_id', 'varchar']], ['id']);
    expect(inferJoinConditions(customer, [textOrders])).toEqual([]);
    const untyped = table('o', 'orders', [['customer_id']], []);
    expect(inferJoinConditions(customer, [untyped]).map((item) => joinConditionText(item))).toEqual(['o.customer_id = c.id']);
  });

  it('读不到主键时：规则一不适用，规则二退回名为 id 的列且排得更低', () => {
    const noKey = table('c', 'customer', [['id', 'bigint']], []);
    const [suggestion] = inferJoinConditions(noKey, [orders]);
    expect(joinConditionText(suggestion)).toBe('o.customer_id = c.id');
    expect(suggestion.score).toBeLessThan(inferJoinConditions(customer, [orders])[0].score);
  });

  it('1:1 扩展表（两边主键同名）给出但排在子表引用之后', () => {
    const ext = table('e', 'ORG_ORGS_EXT', [['PK_ORG', 'CHAR']], ['PK_ORG']);
    const [toOrg] = inferJoinConditions(org, [ext]);
    expect(joinConditionText(toOrg)).toBe('e.PK_ORG = o.PK_ORG');
    expect(toOrg.score).toBeLessThan(inferJoinConditions(org, [voucher])[0].score);
  });

  it('词干解析与表名匹配', () => {
    expect(idSuffixStem('customer_id')).toBe('customer');
    expect(idSuffixStem('CUSTOMER_ID')).toBe('customer');
    expect(idSuffixStem('orderItemId')).toBe('order_item');
    expect(idSuffixStem('USERID')).toBeUndefined();
    expect(idSuffixStem('id')).toBeUndefined();
    expect(tableNameMatchesStem('sys_user', 'user')).toBe(true);
    expect(tableNameMatchesStem('companies', 'company')).toBe(true);
    expect(tableNameMatchesStem('boxes', 'box')).toBe(true);
    expect(tableNameMatchesStem('superuser', 'user')).toBe(false);
  });

  it('条件文本交给调用方按库引用标识符', () => {
    const [suggestion] = inferJoinConditions(org, [voucher]);
    expect(joinConditionText(suggestion, (value) => `"${value}"`)).toBe('v."PK_ORG" = o."PK_ORG"');
  });
});
