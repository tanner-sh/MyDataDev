import { describe, expect, it } from 'vitest';
import {
  buildCombinedDdlPreview,
  canRunAssess,
  createInitialMigrationForm,
  formatMigrationBytes,
  isDestructiveConflictMode,
  isMigrationJobActive,
  sourceConfirmationTarget,
  targetConfirmationTarget,
  withColumnTypeOverride,
  withProgramObjectOverride
} from './databaseMigration';
import type { Connection, MigrationAssessmentResponse } from './types';

const CAPS = {
  tableBrowse: true,
  tableEdit: true,
  tableDesign: true,
  explain: true,
  nativeBackupMethods: [],
  nativeRestoreMethods: [],
  schemaObjects: []
};

const SAMPLE_CONNECTIONS: Connection[] = [
  { id: 1, name: 'Oracle生产库', dbType: 'oracle', jdbcUrl: 'jdbc:oracle:thin:@//h:1521/orcl', environment: 'PROD', readonly: false, capabilities: CAPS },
  { id: 2, name: 'OB测试库', dbType: 'oceanbase-mysql', jdbcUrl: 'jdbc:oceanbase://h:2881/app', environment: 'TEST', readonly: false, capabilities: CAPS },
  { id: 3, name: 'MySQL生产库', dbType: 'mysql', jdbcUrl: 'jdbc:mysql://h:3306/app', environment: 'PROD', readonly: false, capabilities: CAPS }
];

describe('databaseMigration pure helpers', () => {
  it('按三种模式正确校验是否允许发起预检评估', () => {
    const base = createInitialMigrationForm(1, 'HR');
    expect(canRunAssess(base)).toBe(false);
    expect(canRunAssess({ ...base, targetConnectionId: 2 })).toBe(true);

    expect(canRunAssess({ ...base, mode: 'EXPORT_ARTIFACT' })).toBe(true);
    expect(canRunAssess({ ...base, mode: 'IMPORT_ARTIFACT', sourceArtifactId: undefined, targetConnectionId: 2 })).toBe(false);
    expect(canRunAssess({ ...base, mode: 'IMPORT_ARTIFACT', sourceArtifactId: 9, targetConnectionId: 2 })).toBe(true);
  });

  it('生产库与破坏性覆盖策略强制要求二次确认', () => {
    const form = { ...createInitialMigrationForm(1, 'HR'), targetConnectionId: 2 };
    expect(sourceConfirmationTarget(form, SAMPLE_CONNECTIONS)).toBe('Oracle生产库');
    expect(targetConfirmationTarget(form, SAMPLE_CONNECTIONS)).toBeUndefined();

    // 目标即使为测试库，选了 DROP_AND_RECREATE 仍必须确认目标连接名
    const dropForm = { ...form, options: { ...form.options, conflictMode: 'DROP_AND_RECREATE' as const } };
    expect(isDestructiveConflictMode(dropForm.options.conflictMode)).toBe(true);
    expect(targetConfirmationTarget(dropForm, SAMPLE_CONNECTIONS)).toBe('OB测试库');

    // 目标是生产库时任何模式都需要确认
    const prodTarget = { ...form, targetConnectionId: 3 };
    expect(targetConfirmationTarget(prodTarget, SAMPLE_CONNECTIONS)).toBe('MySQL生产库');
  });

  it('支持列级类型覆写与对象源码覆写的增删', () => {
    const base = createInitialMigrationForm().options;
    const added = withColumnTypeOverride(base, 'DOCS', 'AMOUNT', 'DECIMAL(20,4)');
    expect(added.columnTypeOverrides['DOCS.AMOUNT']).toBe('DECIMAL(20,4)');
    const removed = withColumnTypeOverride(added, 'DOCS', 'AMOUNT', '   ');
    expect(removed.columnTypeOverrides['DOCS.AMOUNT']).toBeUndefined();

    const prog = withProgramObjectOverride(base, 'VIEW:V_DOCS', 'CREATE VIEW v_docs AS SELECT 1');
    expect(prog.programObjectOverrides['VIEW:V_DOCS']).toBe('CREATE VIEW v_docs AS SELECT 1');
  });

  it('字节与任务运行态格式化正确', () => {
    expect(formatMigrationBytes(0)).toBe('0 B');
    expect(formatMigrationBytes(1536)).toBe('1.5 KB');
    expect(formatMigrationBytes(2.5 * 1024 * 1024 * 1024)).toBe('2.5 GB');
    expect(isMigrationJobActive('RUNNING')).toBe(true);
    expect(isMigrationJobActive('QUEUED')).toBe(true);
    expect(isMigrationJobActive('SUCCESS')).toBe(false);
  });

  it('DDL 预览包含阶段 0 自动创建目标 Schema/数据库语句且默认开启自动创建', () => {
    const init = createInitialMigrationForm(1, 'HR');
    expect(init.options.createTargetSchemaIfMissing).toBe(true);
    const preview = buildCombinedDdlPreview({
      planToken: 'p1',
      mode: 'IMPORT_ARTIFACT',
      sourceDbType: 'oracle',
      targetDbType: 'mysql',
      targetSchema: 'new_biz_db',
      targetSchemaExists: false,
      createSchemaSql: ['CREATE DATABASE IF NOT EXISTS `new_biz_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin'],
      sameFamily: false,
      canExecute: true,
      aiTranslationAvailable: false,
      summary: {
        tableCount: 0, sequenceCount: 0, programObjectCount: 0,
        estimatedTotalRows: 0, estimatedTotalBytes: 0,
        okCount: 1, warningCount: 0, blockerCount: 0, hasLobTables: false
      },
      tables: [],
      programObjects: [],
      globalFindings: [],
      effectiveOptions: init.options
    } as MigrationAssessmentResponse);
    expect(preview).toContain('STAGE0'.replace('STAGE0', '阶段0'));
    expect(preview).toContain('CREATE DATABASE IF NOT EXISTS `new_biz_db`');
  });
});
