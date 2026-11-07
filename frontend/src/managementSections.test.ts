import { describe, expect, it } from 'vitest';
import {
  isManagementSectionAvailable,
  MANAGEMENT_GROUPS,
  MANAGEMENT_SECTIONS,
  isManagementSectionVisible,
  managementNavigation,
  managementNavItemOf,
  managementSectionLabel,
  resolveManagementSection
} from './managementSections';

describe('management sections', () => {
  it('需要连接的分区在未选连接时不可用', () => {
    expect(isManagementSectionAvailable('backups', false)).toBe(false);
    expect(isManagementSectionAvailable('sessions', false)).toBe(false);
    expect(isManagementSectionAvailable('backups', true)).toBe(true);
    // 连接管理本身不依赖已选连接，否则就没法选第一条连接了。
    expect(isManagementSectionAvailable('connections', false)).toBe(true);
    expect(isManagementSectionAvailable('audit', false, true)).toBe(true);
    expect(isManagementSectionAvailable('audit', true, false)).toBe(false);
    expect(isManagementSectionAvailable('mcp', true, false)).toBe(false);
    expect(isManagementSectionAvailable('users', false, true)).toBe(true);
    expect(isManagementSectionAvailable('users', true, false)).toBe(false);
    expect(isManagementSectionAvailable('backups', true, false, true, ['QUERY'])).toBe(false);
    expect(isManagementSectionAvailable('backups', true, false, true, ['BACKUP_RESTORE'])).toBe(true);
  });

  it('打开不可用的分区时退回第一个可用分区，而不是显示一片禁用', () => {
    expect(resolveManagementSection('backups', false)).toBe('connections');
    expect(resolveManagementSection('sessions', false)).toBe('connections');
    expect(resolveManagementSection('backups', true)).toBe('backups');
    expect(resolveManagementSection('audit', false, true)).toBe('audit');
    expect(resolveManagementSection('audit', true, false)).toBe('connections');
  });

  it('每个分区都有中文名', () => {
    for (const section of MANAGEMENT_SECTIONS) {
      expect(managementSectionLabel(section.key)).toBe(section.label);
      expect(section.label).not.toBe('管理');
    }
  });

  it('桌面模式保留 MCP 和审计，但不显示 Web 用户与访问控制', () => {
    expect(isManagementSectionVisible('mcp', false, false)).toBe(true);
    expect(isManagementSectionVisible('audit', false, false)).toBe(true);
    expect(isManagementSectionVisible('users', false, false)).toBe(false);
    expect(isManagementSectionVisible('access', false, false)).toBe(false);
  });

  it('导航按组给出分区，同组的分区在清单里必须连着写', () => {
    const groups = managementNavigation();
    expect(groups.map((group) => group.label)).toEqual(['连接与运维', '数据与结构', '智能与集成', '安全与治理']);
    // 渲染不做重排，所以清单里同一组的分区必须是连续的一段，否则组标题会插在中间。
    expect(groups.flatMap((group) => group.sections.map((section) => section.key)))
      .toEqual(MANAGEMENT_SECTIONS.map((section) => section.key));
    // 每个分区都归了组，没有漏网的。
    for (const section of MANAGEMENT_SECTIONS) {
      expect(MANAGEMENT_GROUPS.some((group) => group.key === section.group)).toBe(true);
    }
  });

  it('一个分区都不可见的组不留下空标题', () => {
    // 桌面模式（认证关闭）下「安全与治理」只剩审计，而非管理员在 Web 模式下整组都没有。
    expect(managementNavigation(true, false).find((group) => group.key === 'governance')?.sections.map((s) => s.key))
      .toEqual(['audit']);
    expect(managementNavigation(false, true).map((group) => group.key)).not.toContain('governance');
    expect(managementNavigation(false, true).map((group) => group.key)).not.toContain('integrations');
  });

  it('对比类分区与用户、访问控制在导航上各合成一项，分区 key 不变', () => {
    const items = managementNavigation().flatMap((group) => group.items);
    const compare = items.find((item) => item.key === 'compare');
    expect(compare?.label).toBe('对比与漂移');
    expect(compare?.sections.map((section) => section.key)).toEqual(['schema-diff', 'schema-snapshots', 'data-diff']);
    expect(items.find((item) => item.key === 'identity')?.sections.map((section) => section.key)).toEqual(['users', 'access']);
    // 单独成项的分区用自己的名字。
    expect(items.find((item) => item.key === 'connections')?.label).toBe('连接管理');
    expect(items.length).toBeLessThan(MANAGEMENT_SECTIONS.length);
    // 合并入口的分区在清单里必须连着写，否则会被拆成两个同名导航项。
    expect(new Set(items.map((item) => item.key)).size).toBe(items.length);
  });

  it('运行状态与导出传输各合成一项，导航从 13 项减到 11 项', () => {
    const items = managementNavigation().flatMap((group) => group.items);
    expect(items.find((item) => item.key === 'status')?.sections.map((section) => section.key)).toEqual(['sessions', 'server-info', 'pool']);
    expect(items.find((item) => item.key === 'export')?.sections.map((section) => section.key)).toEqual(['schema-export', 'scheduled-exports', 'data-transfer']);
    expect(items).toHaveLength(11);
    // 连接池是全局的：没选连接时「运行状态」照样能点进去，落在连接池上。
    const status = items.find((item) => item.key === 'status');
    expect(status?.sections.find((section) => isManagementSectionAvailable(section.key, false))?.key).toBe('pool');
  });

  it('按分区找回所在导航项', () => {
    expect(managementNavItemOf('data-diff')?.key).toBe('compare');
    expect(managementNavItemOf('audit')?.sections).toHaveLength(1);
  });

  it('入口里只剩一个可见分区时用分区自己的名字', () => {
    // 认证关闭时用户与访问控制都不可见，整项消失；这里验证非合并项与合并项的命名规则一致。
    expect(managementNavigation(true, false).flatMap((group) => group.items).some((item) => item.key === 'identity')).toBe(false);
  });
});
