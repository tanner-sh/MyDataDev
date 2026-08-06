import { describe, expect, it } from 'vitest';
import { buildRestoreUploadPath } from './restoreUpload';

describe('buildRestoreUploadPath', () => {
  it('includes the filename required by the binary upload endpoint', () => {
    const path = buildRestoreUploadPath('生产备份 01.sql', 'SQL', 'postgresql');
    const params = new URLSearchParams(path.split('?')[1]);

    expect(path.startsWith('/restores/uploads?')).toBe(true);
    expect(params.get('filename')).toBe('生产备份 01.sql');
    expect(params.get('fileFormat')).toBe('SQL');
    expect(params.get('sourceDbType')).toBe('postgresql');
  });
});
