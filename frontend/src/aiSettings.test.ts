import { describe, expect, it } from 'vitest';
import {
  AI_DEFAULT_SAMPLE_ROWS,
  AI_MAX_SAMPLE_ROWS,
  AI_SECRET_MASK,
  applyProviderChange,
  clearApiKeyPayload,
  sharingOptionsFor,
  summarizePolicies,
  budgetUsage,
  formatTokens,
  toPolicyPayload,
  toSettingsForm,
  toSettingsPayload,
  validateSettingsForm
} from './aiSettings';
import type { AiConnectionPolicy, AiSettings } from './types';

const SAVED: AiSettings = {
  enabled: true,
  provider: 'ANTHROPIC',
  baseUrl: null,
  model: 'claude-opus-5',
  effort: 'HIGH',
  apiKeyConfigured: true,
  dailyTokenBudget: 0,
  userDailyTokenBudget: 0
};

describe('设置表单', () => {
  it('回填已保存的配置，Key 输入框始终是空的', () => {
    const form = toSettingsForm(SAVED);
    expect(form.model).toBe('claude-opus-5');
    expect(form.apiKey).toBe('');
  });

  it('没有配置时给出官方 API 的默认值', () => {
    expect(toSettingsForm(undefined)).toMatchObject({ enabled: false, provider: 'ANTHROPIC', model: 'claude-opus-5', effort: 'HIGH' });
  });

  it('没动过 Key 的表单提交掩码，让后端沿用旧值', () => {
    expect(toSettingsPayload(toSettingsForm(SAVED)).apiKey).toBe(AI_SECRET_MASK);
  });

  it('填了新 Key 就原样提交（去掉首尾空白）', () => {
    const form = { ...toSettingsForm(SAVED), apiKey: '  sk-ant-new \n' };
    expect(toSettingsPayload(form).apiKey).toBe('sk-ant-new');
  });

  it('清除 Key 用显式空串，并且顺带关掉功能', () => {
    const payload = clearApiKeyPayload(toSettingsForm(SAVED));
    expect(payload.apiKey).toBe('');
    expect(payload.enabled).toBe(false);
  });

  it('官方 API 不提交接口地址', () => {
    const form = { ...toSettingsForm(SAVED), baseUrl: 'https://example.invalid' };
    expect(toSettingsPayload(form).baseUrl).toBeNull();
  });

  it('兼容协议提交接口地址', () => {
    const form = applyProviderChange(toSettingsForm(SAVED), 'OPENAI_COMPATIBLE');
    const payload = toSettingsPayload({ ...form, baseUrl: ' http://127.0.0.1:11434/v1 ', model: 'qwen2.5' });
    expect(payload.baseUrl).toBe('http://127.0.0.1:11434/v1');
    expect(payload.model).toBe('qwen2.5');
  });
});

describe('表单校验', () => {
  it('兼容协议缺地址时拦在本地', () => {
    const form = applyProviderChange(toSettingsForm(SAVED), 'OPENAI_COMPATIBLE');
    expect(validateSettingsForm(form, true)).toContain('接口地址');
  });

  it('地址协议不对时拦在本地', () => {
    const form = { ...applyProviderChange(toSettingsForm(SAVED), 'OPENAI_COMPATIBLE'), baseUrl: 'ftp://models/v1', model: 'qwen2.5' };
    expect(validateSettingsForm(form, true)).toContain('http://');
  });

  it('启用官方 API 但既没存过 Key 也没填 Key 时拒绝', () => {
    const form = { ...toSettingsForm(undefined), enabled: true };
    expect(validateSettingsForm(form, false)).toContain('API Key');
  });

  it('已经存过 Key 时不再要求重填', () => {
    expect(validateSettingsForm({ ...toSettingsForm(SAVED), enabled: true }, true)).toBeUndefined();
  });

  it('本地模型可以不填 Key', () => {
    const form = {
      ...applyProviderChange(toSettingsForm(SAVED), 'OPENAI_COMPATIBLE'),
      enabled: true,
      baseUrl: 'http://127.0.0.1:11434/v1',
      model: 'qwen2.5'
    };
    expect(validateSettingsForm(form, false)).toBeUndefined();
  });
});

describe('切换服务商', () => {
  it('换服务商时清空 Key 输入框并换掉默认模型', () => {
    const form = applyProviderChange(toSettingsForm(SAVED), 'OPENAI_COMPATIBLE');
    expect(form.apiKey).toBe('');
    expect(form.model).toBe('');
  });

  it('选回同一个服务商时保留已填的模型名', () => {
    const form = applyProviderChange({ ...toSettingsForm(SAVED), model: 'claude-sonnet-5' }, 'ANTHROPIC');
    expect(form.model).toBe('claude-sonnet-5');
  });
});

describe('连接共享策略', () => {
  it('生产连接禁用样本档', () => {
    const options = sharingOptionsFor(true);
    expect(options.find((option) => option.value === 'STRUCTURE_AND_SAMPLE')?.disabled).toBe(true);
    expect(options.find((option) => option.value === 'STRUCTURE')?.disabled).toBe(false);
  });

  it('非样本档一律把行数清零', () => {
    expect(toPolicyPayload('STRUCTURE', 10)).toEqual({ sharing: 'STRUCTURE', sampleRowLimit: 0 });
  });

  it('样本档没填行数时用默认值', () => {
    expect(toPolicyPayload('STRUCTURE_AND_SAMPLE', 0).sampleRowLimit).toBe(AI_DEFAULT_SAMPLE_ROWS);
  });

  it('样本行数不超过上限', () => {
    expect(toPolicyPayload('STRUCTURE_AND_SAMPLE', 999).sampleRowLimit).toBe(AI_MAX_SAMPLE_ROWS);
  });

  it('概览统计出有几条连接交出了结构、几条带样本', () => {
    const policies: AiConnectionPolicy[] = [
      { connectionId: 1, connectionName: 'a', dbType: 'mysql', environment: 'dev', production: false, sharing: 'NONE', sampleRowLimit: 0 },
      { connectionId: 2, connectionName: 'b', dbType: 'mysql', environment: 'dev', production: false, sharing: 'STRUCTURE', sampleRowLimit: 0 },
      { connectionId: 3, connectionName: 'c', dbType: 'mysql', environment: 'test', production: false, sharing: 'STRUCTURE_AND_SAMPLE', sampleRowLimit: 5 }
    ];
    expect(summarizePolicies(policies)).toEqual({ total: 3, shared: 2, sampled: 1 });
  });
});

describe('token 预算', () => {
  it('回填已保存的额度，没配置时是 0（不限制）', () => {
    expect(toSettingsForm({ ...SAVED, dailyTokenBudget: 500000, userDailyTokenBudget: 50000 }))
      .toMatchObject({ dailyTokenBudget: 500000, userDailyTokenBudget: 50000 });
    expect(toSettingsForm()).toMatchObject({ dailyTokenBudget: 0, userDailyTokenBudget: 0 });
  });

  /** 数字输入框清空后是 null/NaN，直接发出去会变成一个后端读不懂的值。 */
  it('清空输入框按不限制提交，不发 NaN', () => {
    const form = { ...toSettingsForm(SAVED), dailyTokenBudget: NaN, userDailyTokenBudget: -5 };

    expect(toSettingsPayload(form)).toMatchObject({ dailyTokenBudget: 0, userDailyTokenBudget: 0 });
  });

  it('每人额度不能大于全站额度，否则前者永远轮不到生效', () => {
    const form = { ...toSettingsForm(SAVED), dailyTokenBudget: 1000, userDailyTokenBudget: 2000 };

    expect(validateSettingsForm(form, true)).toContain('每人每日预算');
  });

  it('全站不限制时，单独设每人额度是合法的', () => {
    const form = { ...toSettingsForm(SAVED), dailyTokenBudget: 0, userDailyTokenBudget: 2000 };

    expect(validateSettingsForm(form, true)).toBeUndefined();
  });
});

describe('formatTokens 与 budgetUsage', () => {
  it('万以下给原数，万以上给量级', () => {
    expect(formatTokens(0)).toBe('0');
    expect(formatTokens(9_999)).toBe('9999');
    expect(formatTokens(12_300)).toBe('1.2 万');
    expect(formatTokens(1_234_567)).toBe('123 万');
  });

  it('没设预算时不给百分比，界面据此不画进度条', () => {
    expect(budgetUsage(500, 0)).toBeUndefined();
    expect(budgetUsage(500, 1_000)).toBe(50);
  });

  /** 额度只在请求前检查，最后一次请求可以冲过头 —— 进度条不该出现 130%。 */
  it('超出的部分封顶在 100%', () => {
    expect(budgetUsage(1_300, 1_000)).toBe(100);
  });
});
