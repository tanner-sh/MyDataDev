import { Component, type ReactNode } from 'react';
import { PERSIST_WORK_EVENT } from '../auth';

export class AppErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch() { window.dispatchEvent(new Event(PERSIST_WORK_EVENT)); }
  render() {
    if (!this.state.failed) return this.props.children;
    return <main className="auth-page"><section className="app-error-fallback" role="alert">
      <h2>工作台暂时无法显示</h2>
      <p>已保存的 SQL 草稿会在重新载入后恢复。未提交的表格修改需要重新核对。</p>
      <p>如果刚才正在执行 SQL，请先检查执行历史或目标数据，确认结果后再操作。</p>
      <button onClick={() => window.location.reload()}>重新载入工作台</button>
    </section></main>;
  }
}
