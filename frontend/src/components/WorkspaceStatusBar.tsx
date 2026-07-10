import { CheckCircleFilled, CloseCircleFilled, InfoCircleFilled, LoadingOutlined } from '@ant-design/icons';
import { Typography } from 'antd';
import type { WorkspaceStatus } from '../types';

const { Text } = Typography;

export function WorkspaceStatusBar({ status, trailing }: { status: WorkspaceStatus; trailing?: React.ReactNode }) {
  const icon = status.kind === 'loading'
    ? <LoadingOutlined spin />
    : status.kind === 'error'
      ? <CloseCircleFilled />
      : status.kind === 'success'
        ? <CheckCircleFilled />
        : <InfoCircleFilled />;

  return (
    <div className={`workspace-status workspace-status--${status.kind}`} role={status.kind === 'error' ? 'alert' : 'status'}>
      <span className="workspace-status-icon" aria-hidden="true">{icon}</span>
      <Text ellipsis title={status.detail || status.text}>{status.text}</Text>
      {trailing && <div className="workspace-status-trailing">{trailing}</div>}
    </div>
  );
}
