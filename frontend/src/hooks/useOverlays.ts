import { useEffect, useRef, useState } from 'react';
import type { ManagementSection } from '../managementSections';
import { useStableEvent } from './useStableEvent';

type UseOverlaysOptions = {
  /** 请求的分区不可用时退回哪里（取决于当前连接与权限，见 resolveManagementSection）。 */
  resolveSection: (section: ManagementSection) => ManagementSection;
  /** 管理抽屉关上时调用，参数是关之前所在的分区。 */
  onDrawerClosed?: (section: ManagementSection) => void;
};

/**
 * 抽屉与弹层的编排：管理抽屉（分区 + 备份标签）、连接归档、窄屏资源管理器、对象搜索、命令面板、SQL 片段。
 *
 * 从 App.tsx 搬出来的第三组状态（前两组是 useProductionConfirmation、useSqlTabs）。只搬「开没开、开在哪」
 * —— 连接编辑器、新建表、表生命周期这些弹层带着各自的表单数据，留在用它们的地方。
 */
export function useOverlays({ resolveSection, onDrawerClosed }: UseOverlaysOptions) {
  const [activeDrawer, setActiveDrawer] = useState<ManagementSection | null>(null);
  const [backupActiveTab, setBackupActiveTab] = useState('tasks');
  const [archivePanelOpen, setArchivePanelOpen] = useState(false);
  const [mobileExplorerOpen, setMobileExplorerOpen] = useState(false);
  const [objectSearchOpen, setObjectSearchOpen] = useState(false);
  const [snippetsOpen, setSnippetsOpen] = useState(false);
  const [commandPaletteOpen, setCommandPaletteOpen] = useState(false);
  const commandPaletteTriggerRef = useRef<HTMLElement | null>(null);
  const commandPaletteFocusFrameRef = useRef<number | undefined>(undefined);

  const openManagement = useStableEvent((section: ManagementSection, backupTab?: string) => {
    // 进备份分区默认落在「备份任务」；从后台任务点进来的会指定落在历史或恢复上。
    if (section === 'backups') setBackupActiveTab(backupTab ?? 'tasks');
    setActiveDrawer(resolveSection(section));
  });

  const closeManagementDrawer = useStableEvent(() => {
    if (activeDrawer) onDrawerClosed?.(activeDrawer);
    setActiveDrawer(null);
  });

  function cancelPaletteFocusFrame() {
    if (commandPaletteFocusFrameRef.current !== undefined) cancelAnimationFrame(commandPaletteFocusFrameRef.current);
    commandPaletteFocusFrameRef.current = undefined;
  }

  /** 打开命令面板，记下当时的焦点，关掉时还回去。 */
  const openCommandPalette = useStableEvent(() => {
    cancelPaletteFocusFrame();
    commandPaletteTriggerRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setCommandPaletteOpen(true);
  });

  /** 取消关闭：焦点回到打开前的位置。 */
  const closeCommandPalette = useStableEvent(() => {
    setCommandPaletteOpen(false);
    const trigger = commandPaletteTriggerRef.current;
    commandPaletteTriggerRef.current = null;
    cancelPaletteFocusFrame();
    commandPaletteFocusFrameRef.current = requestAnimationFrame(() => {
      commandPaletteFocusFrameRef.current = undefined;
      if (trigger?.isConnected) trigger.focus({ preventScroll: true });
    });
  });

  /** 执行了一条命令后关闭：焦点交给那条命令打开的东西，不再还回去。 */
  const dismissCommandPalette = useStableEvent(() => {
    setCommandPaletteOpen(false);
    commandPaletteTriggerRef.current = null;
    cancelPaletteFocusFrame();
  });

  useEffect(() => () => cancelPaletteFocusFrame(), []);

  return {
    activeDrawer, setActiveDrawer, openManagement, closeManagementDrawer,
    backupActiveTab, setBackupActiveTab,
    archivePanelOpen, setArchivePanelOpen,
    mobileExplorerOpen, setMobileExplorerOpen,
    objectSearchOpen, setObjectSearchOpen,
    snippetsOpen, setSnippetsOpen,
    commandPaletteOpen, openCommandPalette, closeCommandPalette, dismissCommandPalette
  };
}
