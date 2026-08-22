/**
 * 对象树里「单击看结构 / 双击看数据」的分发。
 *
 * DataGrip、DBeaver、Navicat、TablePlus 双击表都是打开数据 —— 那是使用频率最高的动作。
 * 这里此前只有单击（打开结构），看数据要先把鼠标停在行上、等 hover 图标浮现、再点那个
 * 24px 的小按钮。补上双击是为了对齐这个肌肉记忆，同时保留原有的单击行为。
 *
 * 单击要延后一小段再执行，否则双击的第一次点击会先把结构面板打开一次，用户会看到
 * 「结构闪一下再变成数据」。延迟只加在支持看数据的对象上；视图没有数据视图，单击立即生效。
 */

/**
 * 单击的等待窗口。取 250ms：低于「感觉不到延迟」的阈值，又足以覆盖常见的双击间隔。
 * 系统双击速度调得很慢时会退化成原来的行为（先结构后数据），不会出错。
 */
export const OBJECT_ROW_SINGLE_CLICK_DELAY_MS = 250;

export type ObjectRowActivationTimers = {
  setTimer: (callback: () => void, delayMs: number) => number;
  clearTimer: (timerId: number) => void;
};

export type ObjectRowActivation<T> = {
  /** 单击：支持看数据时延后执行，留出双击的判定窗口。 */
  click: (item: T, supportsData: boolean) => void;
  /** 双击：取消待执行的单击，直接打开数据。 */
  doubleClick: (item: T, supportsData: boolean) => void;
  /** 组件卸载时清掉未触发的定时器。 */
  dispose: () => void;
};

export function createObjectRowActivation<T>(
  handlers: { openDetail: (item: T) => void; openData: (item: T) => void },
  timers: ObjectRowActivationTimers,
  delayMs = OBJECT_ROW_SINGLE_CLICK_DELAY_MS
): ObjectRowActivation<T> {
  let pendingTimer: number | null = null;

  const cancelPending = () => {
    if (pendingTimer === null) return;
    timers.clearTimer(pendingTimer);
    pendingTimer = null;
  };

  return {
    click(item, supportsData) {
      cancelPending();
      if (!supportsData) {
        handlers.openDetail(item);
        return;
      }
      pendingTimer = timers.setTimer(() => {
        pendingTimer = null;
        handlers.openDetail(item);
      }, delayMs);
    },
    doubleClick(item, supportsData) {
      cancelPending();
      if (!supportsData) return;
      handlers.openData(item);
    },
    dispose: cancelPending
  };
}
