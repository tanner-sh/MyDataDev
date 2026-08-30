import { describe, expect, it } from 'vitest';
import {
  isManagementSectionAvailable,
  MANAGEMENT_SECTIONS,
  isManagementSectionVisible,
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
});
