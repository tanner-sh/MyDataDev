import { useEffect, useMemo, useRef, useState, type MutableRefObject } from 'react';
import { Input } from 'antd';
import type { MessageInstance } from 'antd/es/message/interface';
import type { HookAPI as ModalHookAPI } from 'antd/es/modal/useModal';
import { PERSIST_WORK_EVENT } from '../auth';
import { createUuid } from '../createUuid';
import { enforceResultBudget } from '../resultRetention';
import { readSqlSession, rememberClosedSqlTab, restoreClosedSqlTab, writeSqlSession } from '../sqlSessionStorage';
import { MAX_SQL_TABS, SQL_TAB_LIMIT_MESSAGE, closeSqlTabIn, duplicateSqlTabOf, normalizeSqlTabTitle, patchSqlTab, withLiveSql } from '../sqlTabs';
import type { SqlEditorHandle } from '../sqlEditorTypes';
import type { SqlTab } from '../types';
import { createCommandTab, createSqlTab } from '../utils';

const DEFAULT_TAB: SqlTab = { id: 'query-1', title: '查询 1', sql: 'select 1 as val', dirty: false, results: [], message: '' };

type UseSqlTabsOptions = {
  workspaceOwner: string;
  /** 当前选中的连接：SQL 标签按连接分会话保存，切换连接就换一套标签。 */
  connectionId: number | null;
  editorRef: MutableRefObject<SqlEditorHandle | null>;
  toast: MessageInstance;
  modal: ModalHookAPI;
  /** 同时写状态栏的提示（App 的 showInfo / showError）。 */
  notifyInfo: (text: string) => void;
  notifyError: (text: string) => void;
  /** 新开或切到某个查询标签后切回 SQL 工作台。 */
  onShowSql: () => void;
};

/**
 * SQL 标签页：一组状态（标签、当前标签、按连接保存的会话）加上它们的增删改。
 *
 * 从 App.tsx 搬出来的第二组状态（第一组是 useProductionConfirmation）。数组怎么变的规则在
 * sqlTabs.ts（有测试）；这里只管状态、会话持久化和两个确认弹窗。
 *
 * 编辑器里的改动要 300ms 才同步回标签，所以凡是「离开当前标签」的操作都先用 editorRef 取一次现值
 * —— 这是 withLiveSql 存在的原因。
 */
export function useSqlTabs({ workspaceOwner, connectionId, editorRef, toast, modal, notifyInfo, notifyError, onShowSql }: UseSqlTabsOptions) {
  const [sqlTabs, setSqlTabs] = useState<SqlTab[]>(() => readSqlSession(null, undefined, workspaceOwner)?.tabs || [DEFAULT_TAB]);
  const [activeSqlTabId, setActiveSqlTabId] = useState(() => readSqlSession(null, undefined, workspaceOwner)?.activeTabId || DEFAULT_TAB.id);
  /** 每次换一套会话就加一：SqlWorkspace 以它为 key 重建，编辑器里不残留上一条连接的内容。 */
  const [sqlSessionRevision, setSqlSessionRevision] = useState(0);
  const [draftSaveState, setDraftSaveState] = useState('草稿保存中…');
  const sqlTabsRef = useRef(sqlTabs);
  const activeSqlTabIdRef = useRef(activeSqlTabId);
  const sqlSessionConnectionIdRef = useRef<number | null>(null);
  const sqlTabSeqRef = useRef(1);
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

  function persistCurrentSqlSession(sessionConnectionId: number | null) {
    const tabs = withLiveSql(sqlTabsRef.current, activeSqlTabIdRef.current, liveEditorSql());
    writeSqlSession(sessionConnectionId, tabs, activeSqlTabIdRef.current, undefined, workspaceOwner);
  }

  /** 切换连接：先把上一条连接的标签存下，再取出这一条连接的。 */
  function activateSqlSession(nextConnectionId: number | null, captureLiveEditor: boolean) {
    const previousConnectionId = sqlSessionConnectionIdRef.current;
    if (previousConnectionId === nextConnectionId) return;
    if (captureLiveEditor) {
      persistCurrentSqlSession(previousConnectionId);
    } else {
      writeSqlSession(previousConnectionId, sqlTabsRef.current, activeSqlTabIdRef.current, undefined, workspaceOwner);
    }
    const restored = readSqlSession(nextConnectionId, undefined, workspaceOwner);
    const nextTabs = restored?.tabs || [{ ...DEFAULT_TAB, id: `query-${Date.now()}-1` }];
    const nextActiveTabId = restored?.activeTabId || nextTabs[0].id;
    sqlSessionConnectionIdRef.current = nextConnectionId;
    sqlTabSeqRef.current = Math.max(1, nextTabs.length);
    sqlTabsRef.current = nextTabs;
    activeSqlTabIdRef.current = nextActiveTabId;
    setSqlTabs(nextTabs);
    setActiveSqlTabId(nextActiveTabId);
    setSqlSessionRevision((current) => current + 1);
  }

  /**
   * 新开一个标签。build 拿到序号造出标签；当前标签的现值先写回去，原来的草稿与结果保持不变。
   * 返回新标签的 id，到上限时返回 null。
   */
  function openSqlTab(build: (index: number) => SqlTab): string | null {
    if (sqlTabsRef.current.length >= MAX_SQL_TABS) {
      toast.warning(SQL_TAB_LIMIT_MESSAGE);
      return null;
    }
    const nextIndex = sqlTabSeqRef.current + 1;
    sqlTabSeqRef.current = nextIndex;
    const tab = build(nextIndex);
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
    if (closing && !rememberClosedSqlTab(connectionId, closing, workspaceOwner)) {
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
    const tab = restoreClosedSqlTab(connectionId, workspaceOwner);
    if (!tab) { notifyInfo('没有可找回的 SQL 草稿'); return; }
    openSqlTab(() => ({ ...tab, id: createUuid() }));
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

  useEffect(() => {
    activateSqlSession(connectionId, false);
  }, [connectionId]);

  // 草稿自动保存到本机：停手 300ms 后写一次。
  useEffect(() => {
    const timer = window.setTimeout(() => {
      const saved = writeSqlSession(connectionId, sqlTabs, activeSqlTabId, undefined, workspaceOwner);
      setDraftSaveState(saved ? '草稿已保存到本机' : '草稿保存失败，请下载 SQL 文件留存');
    }, 300);
    return () => window.clearTimeout(timer);
  }, [activeSqlTabId, connectionId, sqlTabs]);

  // 离开页面（或会话过期前）把编辑器里还没同步的那一点也存下。
  useEffect(() => {
    const persist = () => persistCurrentSqlSession(sqlSessionConnectionIdRef.current);
    window.addEventListener('pagehide', persist);
    window.addEventListener(PERSIST_WORK_EVENT, persist);
    return () => { window.removeEventListener('pagehide', persist); window.removeEventListener(PERSIST_WORK_EVENT, persist); };
  }, []);

  return {
    sqlTabs, setSqlTabs, sqlTabsRef,
    activeSqlTabId, setActiveSqlTabId, activeSqlTabIdRef, activeSqlTab,
    sqlSessionRevision, sqlSessionConnectionIdRef, draftSaveState,
    activateSqlSession, updateSqlTab, updateActiveSqlTab, openSqlTab,
    addSqlTab, addCommandTab, duplicateSqlTab, closeSqlTab, reopenSqlTab, renameSqlTab
  };
}
