import { describe, expect, it } from 'vitest';
import {
  agentGrantWarnings,
  allowedLevelsFor,
  buildAgentGrants,
  grantsToFormValues,
  mcpAccessLevelColor
} from './mcpAccessLevels';
import type { McpConnectionOption } from './types';

const dev: McpConnectionOption = { id: 1, name: 'dev', dbType: 'H2', environment: 'dev', readonly: false };
const locked: McpConnectionOption = { id: 2, name: 'reporting', dbType: 'H2', environment: 'dev', readonly: true };
const prod: McpConnectionOption = { id: 3, name: 'orders-prod', dbType: 'H2', environment: 'prod', readonly: false };
const connections = new Map([dev, locked, prod].map((connection) => [connection.id, connection]));

describe('MCP 访问档位', () => {
  it('只读连接只能给只读档位', () => {
    expect(allowedLevelsFor(locked)).toEqual(['READ_ONLY']);
    expect(allowedLevelsFor(dev)).toEqual(['READ_ONLY', 'DATA_WRITE', 'FULL']);
    expect(allowedLevelsFor(undefined)).toEqual(['READ_ONLY', 'DATA_WRITE', 'FULL']);
  });

  it('合成授权时未选档位落到只读，只读连接上的写档位被降级', () => {
    expect(buildAgentGrants([1, 2, 3], { 1: 'FULL', 2: 'DATA_WRITE' }, connections)).toEqual([
      { connectionId: 1, accessLevel: 'FULL' },
      // 服务端一定会拒绝这个组合，界面不该先把它提交出去。
      { connectionId: 2, accessLevel: 'READ_ONLY' },
      { connectionId: 3, accessLevel: 'READ_ONLY' }
    ]);
  });

  it('能把服务端的授权列表还原成表单状态', () => {
    expect(grantsToFormValues([
      { connectionId: 3, accessLevel: 'READ_ONLY' },
      { connectionId: 1, accessLevel: 'DATA_WRITE' }
    ])).toEqual({ connectionIds: [3, 1], levels: { 3: 'READ_ONLY', 1: 'DATA_WRITE' } });
  });

  it('写档位和生产连接都要在确认框里被点名', () => {
    const warnings = agentGrantWarnings([
      { connectionId: 1, accessLevel: 'FULL' },
      { connectionId: 2, accessLevel: 'READ_ONLY' },
      { connectionId: 3, accessLevel: 'DATA_WRITE' }
    ], connections);

    expect(warnings.writable).toEqual(['dev（完全）', 'orders-prod（数据读写）']);
    expect(warnings.production).toEqual(['orders-prod']);
  });

  it('纯只读授权不触发确认', () => {
    const warnings = agentGrantWarnings([{ connectionId: 1, accessLevel: 'READ_ONLY' }], connections);
    expect(warnings.writable).toEqual([]);
    expect(warnings.production).toEqual([]);
  });

  it('写档位用颜色区分出来', () => {
    expect(mcpAccessLevelColor('FULL')).toBe('red');
    expect(mcpAccessLevelColor('DATA_WRITE')).toBe('orange');
    expect(mcpAccessLevelColor('READ_ONLY')).toBeUndefined();
  });
});
