/**
 * AI 助手设置面板的纯逻辑。
 *
 * 哪些字段对哪个服务商有意义、掩码怎么解释、共享档位在生产连接上能选什么 —— 这些都是
 * 能单测的规则，不该埋在表单的 JSX 里。面板只负责把它们画出来。
 */

import type { AiConnectionPolicy, AiEffort, AiProvider, AiSchemaSharing, AiSettings } from './types';

/** 与后端 AiSettingsProfile.SECRET_MASK 一致：表示沿用已保存的 Key。 */
export const AI_SECRET_MASK = '******';
/** 与后端 AiSharingRules 一致。 */
export const AI_MAX_SAMPLE_ROWS = 20;
export const AI_DEFAULT_SAMPLE_ROWS = 5;

type ProviderMeta = {
  label: string;
  /** 兼容协议必须填接口地址；官方 API 填了也不生效。 */
  requiresBaseUrl: boolean;
  /** 官方 API 必须有 Key；本地模型通常没有 Key 的概念。 */
  requiresApiKey: boolean;
  defaultModel: string;
  hint: string;
};

export const AI_PROVIDERS: Readonly<Record<AiProvider, ProviderMeta>> = {
  ANTHROPIC: {
    label: 'Claude API',
    requiresBaseUrl: false,
    requiresApiKey: true,
    defaultModel: 'claude-opus-5',
    hint: '官方 API，地址由 SDK 决定，只需填写 API Key。'
  },
  OPENAI_COMPATIBLE: {
    label: 'OpenAI 兼容协议',
    requiresBaseUrl: true,
    requiresApiKey: false,
    defaultModel: '',
    hint: '自建网关、Ollama、vLLM 等；填写到 /v1 为止，例如 http://127.0.0.1:11434/v1。'
  }
};

export const AI_EFFORTS: ReadonlyArray<{ value: AiEffort; label: string }> = [
  { value: 'LOW', label: '低（补全类高频调用）' },
  { value: 'MEDIUM', label: '中' },
  { value: 'HIGH', label: '高（默认）' },
  { value: 'XHIGH', label: '较高' },
  { value: 'MAX', label: '最高（最慢最贵）' }
];

export const AI_SHARING_LABELS: Readonly<Record<AiSchemaSharing, string>> = {
  NONE: '不参与 AI',
  STRUCTURE: '仅结构',
  STRUCTURE_AND_SAMPLE: '结构 + 样本行'
};

export const AI_SHARING_HINTS: Readonly<Record<AiSchemaSharing, string>> = {
  NONE: 'AI 接口取不到这条连接的任何信息。',
  STRUCTURE: '只发送表名、列名、类型、主外键、索引与注释，不发送任何行数据。',
  STRUCTURE_AND_SAMPLE: '在结构之外附带少量样本行，诊断更准，但真实数据会离开本机。'
};

export type AiSettingsForm = {
  enabled: boolean;
  provider: AiProvider;
  baseUrl: string;
  model: string;
  effort: AiEffort;
  /** 空串表示不改动（提交时转成掩码）；用户清空输入框用的是显式的「清除」动作。 */
  apiKey: string;
  /** 每天全站 token 上限，0 表示不限制。 */
  dailyTokenBudget: number;
  /** 每天每人 token 上限，0 表示不限制。 */
  userDailyTokenBudget: number;
};

export function toSettingsForm(settings?: AiSettings): AiSettingsForm {
  const provider = settings?.provider || 'ANTHROPIC';
  return {
    enabled: settings?.enabled ?? false,
    provider,
    baseUrl: settings?.baseUrl || '',
    model: settings?.model || AI_PROVIDERS[provider].defaultModel,
    effort: settings?.effort || 'HIGH',
    apiKey: '',
    dailyTokenBudget: settings?.dailyTokenBudget ?? 0,
    userDailyTokenBudget: settings?.userDailyTokenBudget ?? 0
  };
}

/**
 * 表单转请求体。
 *
 * 没动过 Key 的输入框（空串）转成掩码，让后端沿用旧值；要清除得传显式的空串，
 * 由 {@link clearApiKeyPayload} 负责 —— 两种「空」必须分开，否则改个模型名就会把 Key 抹掉。
 */
export function toSettingsPayload(form: AiSettingsForm): {
  enabled: boolean;
  provider: AiProvider;
  baseUrl: string | null;
  model: string;
  effort: AiEffort;
  apiKey: string;
  dailyTokenBudget: number;
  userDailyTokenBudget: number;
} {
  const meta = AI_PROVIDERS[form.provider];
  return {
    enabled: form.enabled,
    provider: form.provider,
    baseUrl: meta.requiresBaseUrl ? form.baseUrl.trim() : null,
    model: form.model.trim() || meta.defaultModel,
    effort: form.effort,
    apiKey: form.apiKey.trim() ? form.apiKey.trim() : AI_SECRET_MASK,
    dailyTokenBudget: budgetValue(form.dailyTokenBudget),
    userDailyTokenBudget: budgetValue(form.userDailyTokenBudget)
  };
}

export function clearApiKeyPayload(form: AiSettingsForm) {
  return { ...toSettingsPayload(form), enabled: false, apiKey: '' };
}

/** 保存前的本地校验：错在本地就别发一次请求再等后端说同样的话。 */
export function validateSettingsForm(form: AiSettingsForm, apiKeyConfigured: boolean): string | undefined {
  const meta = AI_PROVIDERS[form.provider];
  if (meta.requiresBaseUrl) {
    const url = form.baseUrl.trim().toLowerCase();
    if (!url) return '请填写接口地址，例如 http://127.0.0.1:11434/v1。';
    if (!url.startsWith('http://') && !url.startsWith('https://')) return '接口地址必须以 http:// 或 https:// 开头。';
  }
  if (!form.model.trim() && !meta.defaultModel) return '请填写模型名称。';
  if (form.enabled && meta.requiresApiKey && !apiKeyConfigured && !form.apiKey.trim()) {
    return '启用 AI 之前请先填写 API Key。';
  }
  if (form.dailyTokenBudget < 0 || form.userDailyTokenBudget < 0) return 'token 预算不能为负数，0 表示不限制。';
  // 每人额度大于全站额度时，前者永远轮不到生效 —— 与其让人以为设了，不如当场说清楚。
  if (form.dailyTokenBudget > 0 && form.userDailyTokenBudget > form.dailyTokenBudget) {
    return '每人每日预算不能超过全站每日预算。';
  }
  return undefined;
}

/**
 * token 数的显示。
 *
 * <p>用「万」而不是千分位：预算一开就是几十万上百万，`1,234,567` 这样的数字在一张表里
 * 要数位数才看得出量级，而这里要看的本来就只有量级。</p>
 */
export function formatTokens(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return '0';
  if (value < 10_000) return String(Math.round(value));
  const wan = value / 10_000;
  return `${wan >= 100 ? Math.round(wan) : wan.toFixed(1)} 万`;
}

/** 今日额度用掉的百分比；没设预算时返回 undefined，界面据此不画进度条。 */
export function budgetUsage(used: number, budget: number): number | undefined {
  if (budget <= 0) return undefined;
  return Math.min(100, Math.round((used / budget) * 100));
}

/** 输入框可能被清空成 null/NaN，这些都按「不限制」处理，而不是发一个 NaN 给后端。 */
function budgetValue(value: number): number {
  return Number.isFinite(value) && value > 0 ? Math.floor(value) : 0;
}

/** 切换服务商时的字段迁移：模型名回到新服务商的默认值，Key 输入框清空。 */
export function applyProviderChange(form: AiSettingsForm, provider: AiProvider): AiSettingsForm {
  const meta = AI_PROVIDERS[provider];
  const keepModel = provider === form.provider;
  return {
    ...form,
    provider,
    model: keepModel ? form.model : meta.defaultModel,
    baseUrl: meta.requiresBaseUrl ? form.baseUrl : '',
    apiKey: ''
  };
}

/** 一条连接能选的档位：生产连接不允许把样本数据发出去。 */
export function sharingOptionsFor(production: boolean): ReadonlyArray<{ value: AiSchemaSharing; label: string; disabled: boolean }> {
  return (Object.keys(AI_SHARING_LABELS) as AiSchemaSharing[]).map((value) => ({
    value,
    label: AI_SHARING_LABELS[value],
    disabled: production && value === 'STRUCTURE_AND_SAMPLE'
  }));
}

export function toPolicyPayload(sharing: AiSchemaSharing, sampleRowLimit: number): { sharing: AiSchemaSharing; sampleRowLimit: number } {
  if (sharing !== 'STRUCTURE_AND_SAMPLE') return { sharing, sampleRowLimit: 0 };
  const rows = Number.isFinite(sampleRowLimit) && sampleRowLimit > 0 ? Math.floor(sampleRowLimit) : AI_DEFAULT_SAMPLE_ROWS;
  return { sharing, sampleRowLimit: Math.min(rows, AI_MAX_SAMPLE_ROWS) };
}

/** 概览文案：管理员最该一眼看到的是「有几条连接把结构交出去了」。 */
export function summarizePolicies(policies: AiConnectionPolicy[]): { shared: number; sampled: number; total: number } {
  return {
    total: policies.length,
    shared: policies.filter((policy) => policy.sharing !== 'NONE').length,
    sampled: policies.filter((policy) => policy.sharing === 'STRUCTURE_AND_SAMPLE').length
  };
}
