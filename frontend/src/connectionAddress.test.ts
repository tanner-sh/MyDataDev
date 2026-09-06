import { describe, expect, it } from 'vitest';
import { addressProblem, buildConnectionAddress, parseConnectionAddress } from './connectionAddress';
describe('connection address', () => {
  it('preserves advanced options during a host edit', () => {
    const address = parseConnectionAddress('mysql', 'jdbc:mysql://localhost:3306/shop?useSSL=true&serverTimezone=UTC')!;
    expect(buildConnectionAddress('mysql', { ...address, host: 'db.internal' })).toBe('jdbc:mysql://db.internal:3306/shop?useSSL=true&serverTimezone=UTC');
  });
  it('supports IPv6 and leaves complex JDBC addresses in advanced mode', () => {
    expect(parseConnectionAddress('postgresql', 'jdbc:postgresql://[::1]:5432/shop')?.host).toBe('[::1]');
    expect(parseConnectionAddress('oracle', 'jdbc:oracle:thin:@localhost:1521:ORCL')).toBeUndefined();
    expect(addressProblem({ host: 'db', port: 0, database: '', suffix: '' })).toContain('端口');
  });
});
