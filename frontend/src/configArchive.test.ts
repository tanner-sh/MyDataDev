import { describe, expect, it } from 'vitest';
import {
  archiveImportSummary,
  archivePassphraseError,
  ARCHIVE_FORMAT,
  isConfigArchive,
  MIN_ARCHIVE_PASSPHRASE_LENGTH
} from './configArchive';

const envelope = {
  format: ARCHIVE_FORMAT,
  version: 1,
  kdf: 'PBKDF2WithHmacSHA256',
  iterations: 210000,
  salt: 'c2FsdA==',
  cipher: 'AES/GCM/NoPadding',
  iv: 'aXY=',
  payload: 'cGF5bG9hZA=='
};

describe('归档文件识别', () => {
  it('认出本程序的归档信封', () => {
    expect(isConfigArchive(envelope)).toBe(true);
  });

  it('别的 JSON 一律拒绝，免得把口令发给一个随机文件', () => {
    expect(isConfigArchive({ ...envelope, format: 'something-else' })).toBe(false);
    expect(isConfigArchive({ ...envelope, payload: '' })).toBe(false);
    expect(isConfigArchive({ format: ARCHIVE_FORMAT })).toBe(false);
    expect(isConfigArchive(null)).toBe(false);
    expect(isConfigArchive('mydatadev-config-archive')).toBe(false);
    expect(isConfigArchive([])).toBe(false);
  });
});

describe('导出口令校验', () => {
  it('长度不够直接拦下', () => {
    expect(archivePassphraseError('short', 'short')).toContain(String(MIN_ARCHIVE_PASSPHRASE_LENGTH));
  });

  it('两次输入必须一致', () => {
    expect(archivePassphraseError('a-long-enough-passphrase', 'a-different-one')).toBe('两次输入的口令不一致');
  });

  it('合格时返回 null', () => {
    expect(archivePassphraseError('a-long-enough-passphrase', 'a-long-enough-passphrase')).toBeNull();
  });
});

describe('导入结果说明', () => {
  it('全部导入时只说导入了多少', () => {
    expect(archiveImportSummary({ imported: 3, skipped: 0, renamed: 0, importedNames: [], skippedNames: [] }))
      .toBe('导入 3 条。');
  });

  it('跳过的连接要点名，否则用户会以为配置齐了', () => {
    const summary = archiveImportSummary({
      imported: 1, skipped: 2, renamed: 0,
      importedNames: ['a'], skippedNames: ['prod-1', 'prod-2']
    });
    expect(summary).toContain('跳过 2 条重名连接：prod-1、prod-2');
  });

  it('跳过太多时只列前几条', () => {
    const summary = archiveImportSummary({
      imported: 0, skipped: 7, renamed: 0, importedNames: [],
      skippedNames: ['a', 'b', 'c', 'd', 'e', 'f', 'g']
    });
    expect(summary).toContain('等 7 条');
    expect(summary).not.toContain('、g');
  });

  it('改名的条数单独说明', () => {
    expect(archiveImportSummary({ imported: 2, skipped: 0, renamed: 2, importedNames: [], skippedNames: [] }))
      .toContain('其中 2 条因重名已改名');
  });
});
