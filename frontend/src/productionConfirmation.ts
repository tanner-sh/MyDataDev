export const PRODUCTION_CONFIRMATION_HEADER = 'X-Production-Confirmation';

export function normalizeProductionConfirmation(value: string) {
  return value.trim();
}

export function matchesProductionConnectionName(value: string, expected: string) {
  return normalizeProductionConfirmation(value) === expected;
}

/**
 * 构造生产确认请求头。
 *
 * 确认串就是连接名，而本项目的连接名几乎必然含中文。HTTP 头值只能是 ISO-8859-1：直接把
 * 中文塞进去，浏览器在构造 Headers 时就会抛
 * `TypeError: String contains non ISO-8859-1 code point`，请求根本发不出去 —— 也就是说
 * 生产连接只要用中文命名，执行 SQL、翻页、导出、提交表数据、表结构设计、表增删改名、
 * schema 对象生命周期与调用全部不可用。
 *
 * 因此这里统一用 encodeURIComponent 编码，后端 ProductionConfirmationCodec 负责还原。
 * 纯 ASCII 的名字编码后不变，所以对已有连接没有任何影响。
 *
 * 任何要发这个头的地方都必须走这个函数，不要自己拼对象。
 */
export function productionConfirmationHeaders(confirmation?: string | null): Record<string, string> {
  if (!confirmation) return {};
  return { [PRODUCTION_CONFIRMATION_HEADER]: encodeURIComponent(confirmation) };
}
