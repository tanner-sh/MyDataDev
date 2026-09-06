export const API_FAILURE_EVENT = 'mydatadev:api-failure';
export type FailureInfo = { title: string; message: string; advice: string; code: string; requestId?: string; path?: string; time: string };

/** 诊断不包含请求体、Cookie、Token；驱动偶尔会把连接口令放进错误文本。 */
export function redactDiagnostic(message: string): string {
  return message.replace(/((?:password|passwd|pwd|token|secret|api[_-]?key)\s*[=:]\s*)[^\s,;&]+/gi, '$1[已隐藏]')
    .replace(/(\/\/)[^\s/@:]+:[^\s/@]+@/g, '$1[已隐藏]@');
}

export function describeFailure(error: { message: string; code?: string; status?: number; requestId?: string }, path?: string): FailureInfo {
  const code = error.code || (error.status === 401 ? 'AUTH_REQUIRED' : error.status === 403 ? 'FORBIDDEN' : 'REQUEST_FAILED');
  let title = '操作未完成';
  let advice = '检查下方详情，修正后重新操作；如需反馈，请复制诊断信息。';
  if (code === 'OPERATION_RESULT_UNKNOWN') { title = '操作结果待确认'; advice = '请求可能已在数据库生效。先查看 SQL 历史、刷新目标数据或检查事务状态，确认后再决定下一步。'; }
  else if (code === 'NETWORK_ERROR' || code === 'REQUEST_TIMEOUT') { title = '连接中断或响应超时'; advice = '检查网络和后端服务，连接恢复后可以重新加载数据。'; }
  else if (error.status === 401) { title = '登录已过期'; advice = '重新登录原账号后继续；SQL 草稿会保留，页面内重新登录也会保留表格修改。'; }
  else if (error.status === 403) { title = '当前账号没有操作权限'; advice = '请联系管理员核对这条连接的授权。'; }
  else if (code === 'TARGET_DATABASE_UNAVAILABLE') { title = '数据库暂时无法连接'; advice = '在连接管理中测试连接，检查数据库、SSH 隧道和账号配置。'; }
  else if (code === 'DATA_EDIT_CONFLICT') { title = '数据发生并发修改'; advice = '查看冲突行的数据库当前值，保留所需修改后重新提交。'; }
  return { title, advice, code, message: redactDiagnostic(error.message), requestId: error.requestId, path: path?.split('?')[0], time: new Date().toISOString() };
}

export function reportFailure(error: Parameters<typeof describeFailure>[0], path?: string) {
  if (['PRODUCTION_CONFIRMATION_REQUIRED', 'UNSCOPED_MUTATION_CONFIRMATION_REQUIRED'].includes(error.code || '')) return;
  if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent(API_FAILURE_EVENT, { detail: describeFailure(error, path) }));
}

export function mayChangeData(path: string, method = 'GET') {
  if (['GET', 'HEAD', 'OPTIONS'].includes(method.toUpperCase())) return false;
  if (/^\/data\/(?:table\/query|conflict-row)(?:\?|$)/.test(path)) return false;
  return !/(?:\/test|\/preview|\/preflight|\/query-page|\/explain|\/format|\/export|\/count|\/search|\/auth\/status)(?:\?|$)/.test(path);
}

export function diagnosticText(failure: FailureInfo) {
  return [failure.title, failure.message, failure.advice, `错误码：${failure.code}`, `请求编号：${failure.requestId || '未提供'}`, `接口：${failure.path || '界面'}`, `时间：${failure.time}`].join('\n');
}
