import { useEffect, useMemo, useRef, useState, type MutableRefObject } from 'react';
import { Input } from 'antd';
import type { MessageInstance } from 'antd/es/message/interface';
import type { HookAPI as ModalHookAPI } from 'antd/es/modal/useModal';
import { PERSIST_WORK_EVENT } from '../auth';
import { createUuid } from '../createUuid';
import { enforceResultBudget } from '../resultRetention';
import { migrateLegacySqlSessions, readGlobalSqlSession, rememberClosedSqlTab, restoreClosedSqlTab, writeGlobalSqlSession } from '../sqlSessionStorage';
import { MAX_SQL_TABS, SQL_TAB_LIMIT_MESSAGE, bindTabConnections, closeSqlTabIn, duplicateSqlTabOf, normalizeSqlTabTitle, patchSqlTab, withLiveSql, type SqlSessionSnapshot } from '../sqlTabs';
import type { SqlEditorHandle } from '../sqlEditorTypes';
import type { SqlTab } from '../types';
import { createCommandTab, createSqlTab } from '../utils';

const DEFAULT_TAB: SqlTab = { id: 'query-1', title: '查询 1', sql: 'select 1 as val', dirty: false, results: [], message: '' };

type UseSqlTabsOptions = {
  workspaceOwner: string;
  /**
   * 当前连接（App 的 selected，跟着当前标签走）。标签不再按连接分组：所有连接的标签在一份会话里，
   * 每个标签自己带 connectionId。这里只用它给还没有连接的标签补上连接、决定「找回关闭的 SQL」找哪条连接的。
   */
  currentConnectionId: number | null;
  /** 第一次打开新版本、把旧的按连接保存的会话并起来时，排在最前的那条连接（上次所在的）。 */
  legacyPreferredConnectionId?: number | null;
  editorRef: MutableRefObject<SqlEditorHandle | null>;
  toast: MessageInstance;
  modal: ModalHookAPI;
  /** 同时写状态栏的提示（App 的 showInfo / showError）。 */
  notifyInfo: (text: string) => void;
  notifyError: (text: string) => void;
  /** 新开或切到某个查询标签后切回 SQL 工作台。 */
  onShowSql: () => void;
  /**
   * 新标签落在哪个 Schema：沿用当前这个查询标签的。在 S2 的标签里点「+」多半是接着写同一个库的脚本，
   * 新标签也该在 S2；之后它自己记着，和其它标签、和资源管理器都互不影响（和 DBeaver 一样，每个编辑器
   * 各自绑定一个 Schema）。调用方显式给了 Schema 的（例如结构对比生成的迁移脚本）以调用方为准。
   */
  inheritedExecutionSchema?: () => string | undefined;
};

/**
 * SQL 标签页：一组状态（标签、当前标签、全局会话）加上它们的增删改。
 *
 * 从 App.tsx 搬出来的第二组状态（第一组是 useProductionConfirmation）。数组怎么变的规则在
 * sqlTabs.ts（有测试）；这里只管状态、会话持久化和两个确认弹窗。
 *
 * 编辑器里的改动要 300ms 才同步回标签，所以凡是「离开当前标签」的操作都先用 editorRef 取一次现值
 * —— 这是 withLiveSql 存在的原因。
 */
export function useSqlTabs({ workspaceOwner, currentConnectionId, legacyPreferredConnectionId, editorRef, toast, modal, notifyInfo, notifyError, onShowSql, inheritedExecutionSchema }: UseSqlTabsOptions) {
  const [initialSession] = useState<SqlSessionSnapshot>(() => readGlobalSqlSession(undefined, workspaceOwner)
    ?? migrateLegacySqlSessions(legacyPreferredConnectionId, MAX_SQL_TABS, undefined, workspaceOwner)
    ?? { tabs: [DEFAULT_TAB], activeTabId: DEFAULT_TAB.id });
  const [sqlTabs, setSqlTabs] = useState<SqlTab[]>(initialSession.tabs);
  const [activeSqlTabId, setActiveSqlTabId] = useState(initialSession.activeTabId);
  const [draftSaveState, setDraftSaveState] = useState('草稿保存中…');
  const sqlTabsRef = useRef(sqlTabs);
  const activeSqlTabIdRef = useRef(activeSqlTabId);
  const currentConnectionIdRef = useRef(currentConnectionId);
  currentConnectionIdRef.current = currentConnectionId;
  // 标题里的序号接着已有的标签往下数，不从 1 重来。
  const sqlTabSeqRef = useRef(Math.max(1, initialSession.tabs.length));
  sqlTabsRef.current = sqlTabs;
  activeSqlTabIdRef.current = activeSqlTabId;

  const activeSqlTab = useMemo(() => sqlTabs.find((tab) => tab.id === activeSqlTabId) || sqlTabs[0], [activeSqlTabId, sqlTabs]);

  function liveEditorSql() {
    return editorRef.current?.getValue();
  }

  function updateSqlTab(targetId: string, patch: Partial<SqlTab>) {
    setSqlTabs((tabs) => enforceResultBudget(tabs.map((tab) => tab.id === targetId ? patchSqlTab(tab, patch) : tab), targetId));
  }

  function updateActiveSqlTab(patch: Partial<SqlTab>) {
    updateSqlTab(activeSqlTabIdRef.current, patch);
  }

  function persistCurrentSqlSession() {
    const tabs = withLiveSql(sqlTabsRef.current, activeSqlTabIdRef.current, liveEditorSql());
    writeGlobalSqlSession(tabs, activeSqlTabIdRef.current, undefined, workspaceOwner);
  }

  /**
   * 新开一个标签。build 拿到序号造出标签；当前标签的现值先写回去，原来的草稿与结果保持不变。
   * 返回新标签的 id，到上限时返回 null。
   *
   * 新标签绑定哪条连接：build 自己给了的为准，其次是 connectionId 参数（从别的连接打开脚本，例如结构对比），
   * 再次是当前连接（它跟着当前页签走）。Schema 只在新标签和当前标签是同一条连接时才沿用 —— 别的连接没有这个 Schema。
   */
  function openSqlTab(build: (index: number) => SqlTab, connectionId?: number): string | null {
    if (sqlTabsRef.current.length >= MAX_SQL_TABS) {
      toast.warning(SQL_TAB_LIMIT_MESSAGE);
      return null;
    }
    const nextIndex = sqlTabSeqRef.current + 1;
    sqlTabSeqRef.current = nextIndex;
    const built = build(nextIndex);
    const current = sqlTabsRef.current.find((item) => item.id === activeSqlTabIdRef.current);
    // 当前连接跟着当前页签走：在表页签里点「+」，新标签绑的是那张表所在的连接。
    const boundConnectionId = built.connectionId ?? connectionId ?? currentConnectionIdRef.current ?? current?.connectionId ?? undefined;
    const sameConnection = boundConnectionId != null && boundConnectionId === current?.connectionId;
    const tab: SqlTab = {
      ...built,
      ...(boundConnectionId != null ? { connectionId: boundConnectionId } : {}),
      executionSchema: built.executionSchema ?? (sameConnection ? inheritedExecutionSchema?.() : undefined)
    };
    const nextTabs = [...withLiveSql(sqlTabsRef.current, activeSqlTabIdRef.current, liveEditorSql()), tab];
    sqlTabsRef.current = nextTabs;
    activeSqlTabIdRef.current = tab.id;
    setSqlTabs(nextTabs);
    setActiveSqlTabId(tab.id);
    onShowSql();
    return tab.id;
  }

  function addSqlTab() {
    openSqlTab(createSqlTab);
  }

  /**
   * 新开一个独立的命令窗口。sql 给了就是「把这段脚本搬到命令窗口里」，选项沿用来源标签设过的那一套。
   */
  function addCommandTab(sql?: string, commandOptions?: SqlTab['commandOptions']) {
    openSqlTab((index) => ({
      ...createCommandTab(index),
      ...(sql !== undefined ? { sql, dirty: Boolean(sql.trim()) } : {}),
      ...(commandOptions ? { commandOptions } : {})
    }));
  }

  function duplicateSqlTab(targetId: string, liveSql?: string) {
    const source = sqlTabsRef.current.find((tab) => tab.id === targetId);
    if (!source) return;
    openSqlTab((index) => duplicateSqlTabOf(source, index, liveSql));
  }

  function removeSqlTab(targetId: string) {
    const closing = sqlTabsRef.current.find((tab) => tab.id === targetId);
    if (closing && !rememberClosedSqlTab(closing.connectionId ?? currentConnectionIdRef.current, closing, workspaceOwner)) {
      notifyError('保存最近关闭的草稿失败，请先下载 SQL 文件。');
      return;
    }
    const next = closeSqlTabIn(sqlTabsRef.current, targetId, activeSqlTabIdRef.current);
    sqlTabsRef.current = next.tabs;
    activeSqlTabIdRef.current = next.activeId;
    setSqlTabs(next.tabs);
    setActiveSqlTabId(next.activeId);
  }

  /** 关闭标签。有草稿的先确认 —— 关掉之后还能从「找回关闭的 SQL」拿回来，但得让人知道。 */
  function closeSqlTab(targetId: string, liveSql?: string) {
    if (sqlTabsRef.current.length <= 1) return;
    const tabs = withLiveSql(sqlTabsRef.current, targetId, liveSql);
    const target = tabs.find((tab) => tab.id === targetId);
    if (!target) return;
    if (tabs !== sqlTabsRef.current) {
      sqlTabsRef.current = tabs;
      setSqlTabs(tabs);
    }
    if (!target.dirty) {
      removeSqlTab(targetId);
      return;
    }
    modal.confirm({
      title: `关闭“${target.title}”？`,
      content: '该标签包含修改过的 SQL，关闭后可通过“找回关闭的 SQL”恢复最近的草稿。',
      okText: '关闭标签',
      cancelText: '继续编辑',
      okButtonProps: { danger: true },
      onOk: () => removeSqlTab(targetId)
    });
  }

  function reopenSqlTab() {
    if (sqlTabsRef.current.length >= MAX_SQL_TABS) { notifyInfo('请先关闭一个 SQL 标签页'); return; }
    // 找回的是当前连接最近关掉的那个，回来时仍绑在这条连接上。
    const connectionId = currentConnectionIdRef.current;
    const tab = restoreClosedSqlTab(connectionId, workspaceOwner);
    if (!tab) { notifyInfo('没有可找回的 SQL 草稿'); return; }
    openSqlTab(() => ({ ...tab, id: createUuid(), ...(connectionId != null ? { connectionId } : {}) }));
  }

  function renameSqlTab(targetId: string) {
    const target = sqlTabsRef.current.find((tab) => tab.id === targetId);
    if (!target) return;
    let title = target.title;
    modal.confirm({
      title: '重命名 SQL 标签',
      content: <Input autoFocus defaultValue={target.title} maxLength={80} aria-label="SQL 标签名称" onChange={(event) => { title = event.target.value; }} />,
      okText: '保存名称',
      cancelText: '取消',
      onOk: () => {
        const normalized = normalizeSqlTabTitle(title);
        if (!normalized) {
          toast.error('标签名称不能为空');
          return Promise.reject(new Error('标签名称不能为空'));
        }
        setSqlTabs((tabs) => tabs.map((tab) => tab.id === targetId ? { ...tab, title: normalized } : tab));
      }
    });
  }

  // 还没有连接的标签（升级前「未选连接」的会话、全新安装的默认标签）绑到当前连接上。
  useEffect(() => {
    if (currentConnectionId == null) return;
    setSqlTabs((tabs) => bindTabConnections(tabs, currentConnectionId));
  }, [currentConnectionId, sqlTabs]);

  // 草稿自动保存到本机：停手 300ms 后写一次。
  useEffect(() => {
    const timer = window.setTimeout(() => {
      const saved = writeGlobalSqlSession(sqlTabs, activeSqlTabId, undefined, workspaceOwner);
      setDraftSaveState(saved ? '草稿已保存到本机' : '草稿保存失败，请下载 SQL 文件留存');
    }, 300);
    return () => window.clearTimeout(timer);
  }, [activeSqlTabId, sqlTabs]);

  // 离开页面（或会话过期前）把编辑器里还没同步的那一点也存下。
  useEffect(() => {
    const persist = () => persistCurrentSqlSession();
    window.addEventListener('pagehide', persist);
    window.addEventListener(PERSIST_WORK_EVENT, persist);
    return () => { window.removeEventListener('pagehide', persist); window.removeEventListener(PERSIST_WORK_EVENT, persist); };
  }, []);

  return {
    sqlTabs, setSqlTabs, sqlTabsRef,
    activeSqlTabId, setActiveSqlTabId, activeSqlTabIdRef, activeSqlTab,
    draftSaveState,
    updateSqlTab, updateActiveSqlTab, openSqlTab,
    addSqlTab, addCommandTab, duplicateSqlTab, closeSqlTab, reopenSqlTab, renameSqlTab
  };
}
