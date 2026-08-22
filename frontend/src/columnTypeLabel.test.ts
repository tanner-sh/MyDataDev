import { describe, expect, it } from 'vitest';
import { compactColumnType } from './columnTypeLabel';

describe('compactColumnType', () => {
  it('shortens the SQL standard spellings that wrap in a narrow column', () => {
    expect(compactColumnType('CHARACTER VARYING')).toBe('VARCHAR');
    expect(compactColumnType('character varying')).toBe('VARCHAR');
    expect(compactColumnType('DOUBLE PRECISION')).toBe('DOUBLE');
    expect(compactColumnType('TIMESTAMP WITHOUT TIME ZONE')).toBe('TIMESTAMP');
    expect(compactColumnType('TIMESTAMP WITH TIME ZONE')).toBe('TIMESTAMP');
    expect(compactColumnType('CHARACTER LARGE OBJECT')).toBe('CLOB');
  });

  it('leaves anything it does not recognise exactly as the driver reported it', () => {
    expect(compactColumnType('INTEGER')).toBe('INTEGER');
    expect(compactColumnType('NUMBER')).toBe('NUMBER');
    expect(compactColumnType('geometry(Point,4326)')).toBe('geometry(Point,4326)');
    expect(compactColumnType('  DECIMAL  ')).toBe('DECIMAL');
  });

  it('handles missing types without throwing', () => {
    expect(compactColumnType(undefined)).toBe('');
    expect(compactColumnType(null)).toBe('');
    expect(compactColumnType('')).toBe('');
  });
});
