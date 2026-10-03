export const PARAMETER_TYPES = [
  { value: 'TEXT', label: '文本' }, { value: 'INTEGER', label: '整数' },
  { value: 'DECIMAL', label: '小数' }, { value: 'BOOLEAN', label: '布尔' },
  { value: 'DATE', label: '日期' }, { value: 'TIMESTAMP', label: '日期时间' }
] as const;
export type SqlParameterType = typeof PARAMETER_TYPES[number]['value'];
export type SqlParameterDefinition = { name: string; type: SqlParameterType; required: boolean; defaultValue?: string | null };
export type SqlParameter = { type: SqlParameterType; value: string | null };
export type SqlParameterValues = Record<string, SqlParameter>;

export function reconcileParameters(names: string[], definitions: SqlParameterDefinition[] = []): SqlParameterDefinition[] {
  return names.map((name) => definitions.find((item) => item.name === name) ?? { name, type: 'TEXT', required: true });
}
export function initialParameterValues(definitions: SqlParameterDefinition[]): SqlParameterValues {
  return Object.fromEntries(definitions.map((item) => [item.name, {
    type: item.type, value: item.defaultValue ?? (item.required ? '' : null)
  }]));
}
export function parameterError(definition: SqlParameterDefinition, value: string | null): string | undefined {
  if (value === null) return definition.required ? '此参数不允许 NULL' : undefined;
  if (value.length > 100000) return '参数值过长';
  switch (definition.type) {
    case 'INTEGER':
      if (!/^[+-]?\d+$/.test(value)) return '请输入整数';
      if (value.replace(/^[+-]?0*/, '').length > 19) return '整数超出 64 位范围';
      if (BigInt(value) < -9223372036854775808n || BigInt(value) > 9223372036854775807n) return '整数超出 64 位范围';
      break;
    case 'DECIMAL': {
      if (!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(value)) return '请输入小数';
      const [coefficient, exponent = '0'] = value.toLowerCase().split('e');
      const precision = coefficient.replace(/[+.-]/g, '').replace(/^0+/, '').length || 1;
      const scale = (coefficient.split('.')[1]?.length || 0) - Number(exponent);
      if (precision > 1000 || !Number.isFinite(scale) || Math.abs(scale) > 1000) return '小数精度或指数超出范围';
      break;
    }
    case 'BOOLEAN': if (!['true', 'false'].includes(value)) return '请选择 true 或 false'; break;
    case 'DATE': if (!validDate(value)) return '请输入有效日期（YYYY-MM-DD）'; break;
    case 'TIMESTAMP': if (!/^\d{4}-\d{2}-\d{2}[T ](?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?$/.test(value) || !validDate(value.slice(0, 10))) return '请输入有效日期时间（YYYY-MM-DD HH:mm:ss）'; break;
  }
  return undefined;
}

function validDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [year, month, day] = value.split('-').map(Number);
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return month >= 1 && month <= 12 && day >= 1 && day <= days[month - 1];
}

/** Validate persisted template metadata before restoring a workspace. */
export function normalizeParameterDefinitions(value: unknown): SqlParameterDefinition[] | undefined {
  if (!Array.isArray(value) || value.length > 100) return undefined;
  const names = new Set<string>();
  const result: SqlParameterDefinition[] = [];
  for (const item of value) {
    if (!item || typeof item !== 'object' || typeof item.name !== 'string' || !/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(item.name)
      || names.has(item.name) || !PARAMETER_TYPES.some((type) => type.value === item.type) || typeof item.required !== 'boolean'
      || item.defaultValue != null && (typeof item.defaultValue !== 'string' || item.defaultValue.length > 100000)) return undefined;
    names.add(item.name);
    result.push({ name: item.name, type: item.type, required: item.required, ...(item.defaultValue != null ? { defaultValue: item.defaultValue } : {}) });
  }
  return result;
}
