import { memo, type ReactNode } from 'react';
import { Empty, Spin, Typography } from 'antd';

const { Text } = Typography;

/**
 * 空状态与加载态的统一外观。
 *
 * 改之前全仓有 22 个各自为政的类名（workspace-lazy-loading、table-viewport-loading、
 * object-tree-empty、relation-empty、history-loading…），`<Empty>` 散在 19 个组件里、
 * `<Spin>` 在 18 个。每个面板都自己发明了一套骨架和留白，于是同一个应用里「没有内容」
 * 长出了十几种样子。
 *
 * 这两个组件不做任何布局假设：由调用方决定放在哪、占多大，它们只负责「长什么样」。
 */

/** 面板加载中。文案统一用「正在…」的进行时，不要写成「加载中…」。 */
export const PanelLoading = memo(function PanelLoading({ text, compact }: {
  text: string;
  /** 嵌在表格视口、树节点这类小区域里时用紧凑版。 */
  compact?: boolean;
}) {
  return (
    <div className={compact ? 'panel-state panel-state-compact' : 'panel-state'} role="status" aria-live="polite">
      <Spin size={compact ? 'small' : 'default'} />
      <Text type="secondary">{text}</Text>
    </div>
  );
});

/**
 * 面板空状态。
 *
 * `description` 说明为什么空，`action` 给一条出路 —— 只有「暂无数据」而不告诉用户下一步
 * 做什么的空状态，等于把问题原样丢回去。
 */
export const PanelEmpty = memo(function PanelEmpty({ title, description, action, compact, fill }: {
  title: string;
  description?: ReactNode;
  action?: ReactNode;
  compact?: boolean;
  /** 占满可用高度并垂直居中，用于整块工作区为空的场合。 */
  fill?: boolean;
}) {
  return (
    <div className={['panel-state', compact ? 'panel-state-compact' : '', fill ? 'panel-state-fill' : ''].filter(Boolean).join(' ')}>
      <Empty
        image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={
          <span className="panel-state-copy">
            <Text>{title}</Text>
            {description && <Text type="secondary">{description}</Text>}
          </span>
        }
      >
        {action}
      </Empty>
    </div>
  );
});
