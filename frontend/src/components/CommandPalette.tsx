import { memo, useEffect, useMemo, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { Empty, Input, Modal, Tag, Typography } from 'antd';
import { ThunderboltOutlined } from '@ant-design/icons';
import {
  filterCommands,
  groupCommands,
  moveCommandSelection,
  type PaletteCommand
} from '../commandPalette';

const { Text } = Typography;

export type PaletteAction = PaletteCommand & { run: () => void };

/**
 * 命令面板（Ctrl/Cmd+K）。
 *
 * <p>Ctrl/Cmd+P 搜的是库里的对象，这里搜的是应用能做的事：管理抽屉的每个分区、SQL 工具条上
 * 的动作、切换连接。它不新增任何能力，只是让已有入口的名字变得可搜索 —— 并顺带成为快捷键
 * 的说明书。</p>
 */
export const CommandPalette = memo(function CommandPalette({
  open,
  actions,
  recentIds,
  onClose,
  onRun
}: {
  open: boolean;
  actions: PaletteAction[];
  recentIds: string[];
  onClose: () => void;
  onRun: (action: PaletteAction) => void;
}) {
  const [keyword, setKeyword] = useState('');
  const [selected, setSelected] = useState(0);
  const listRef = useRef<HTMLDivElement>(null);

  const ordered = useMemo(() => filterCommands(actions, keyword, recentIds), [actions, keyword, recentIds]);
  const groups = useMemo(() => groupCommands(ordered), [ordered]);
  const byId = useMemo(() => new Map(actions.map((action) => [action.id, action])), [actions]);

  useEffect(() => {
    if (open) setKeyword('');
  }, [open]);

  useEffect(() => {
    // 检索词一变，选中项要落回第一条可用的，否则回车会打开上一次的结果。
    setSelected(moveCommandSelection(-1, ordered.length, 1, ordered));
  }, [ordered]);

  useEffect(() => {
    if (selected < 0) return;
    listRef.current?.querySelectorAll('.command-palette-item')[selected]?.scrollIntoView({ block: 'nearest' });
  }, [selected]);

  function activate(id: string) {
    const action = byId.get(id);
    if (!action || action.disabledReason) return;
    onClose();
    onRun(action);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      setSelected((current) => moveCommandSelection(current, ordered.length, event.key === 'ArrowDown' ? 1 : -1, ordered));
      return;
    }
    if (event.key === 'Enter' && selected >= 0 && ordered[selected]) {
      event.preventDefault();
      activate(ordered[selected].id);
    }
  }

  let flatIndex = -1;

  return (
    <Modal
      open={open}
      title={null}
      footer={null}
      closable={false}
      width={640}
      rootClassName="command-palette-modal"
      onCancel={onClose}
      destroyOnHidden
    >
      <Input
        autoFocus
        size="large"
        variant="borderless"
        prefix={<ThunderboltOutlined />}
        placeholder="搜索命令：备份、历史、定时导出、切换连接…"
        aria-label="搜索命令"
        value={keyword}
        onChange={(event) => setKeyword(event.target.value)}
        onKeyDown={handleKeyDown}
      />
      <div className="command-palette-results" ref={listRef}>
        {ordered.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={`没有匹配「${keyword.trim()}」的命令`} />
        ) : (
          groups.map((group) => (
            <section className="command-palette-group" key={group.section}>
              <Text type="secondary" className="command-palette-group-label">{group.section}</Text>
              {group.commands.map((command) => {
                flatIndex += 1;
                const index = flatIndex;
                return (
                  <button
                    type="button"
                    key={command.id}
                    className={`command-palette-item${index === selected ? ' is-selected' : ''}`}
                    disabled={Boolean(command.disabledReason)}
                    onMouseEnter={() => !command.disabledReason && setSelected(index)}
                    onClick={() => activate(command.id)}
                  >
                    <span className="command-palette-item-title">{command.title}</span>
                    {command.hint && <Tag className="command-palette-item-hint">{command.hint}</Tag>}
                    {command.disabledReason && (
                      <Text type="secondary" className="command-palette-item-note">{command.disabledReason}</Text>
                    )}
                  </button>
                );
              })}
            </section>
          ))
        )}
      </div>
      <div className="command-palette-footer">
        <Text type="secondary">{ordered.length} 条命令</Text>
        <Text type="secondary">↑↓ 选择 · Enter 执行 · Esc 关闭</Text>
      </div>
    </Modal>
  );
});
