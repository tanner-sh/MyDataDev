import { useEffect, useState } from 'react';
import { Alert, Button, Modal, Space, Typography } from 'antd';
import { API_FAILURE_EVENT, diagnosticText, type FailureInfo } from '../errorFeedback';

export function ErrorCenter() {
  const [failures, setFailures] = useState<FailureInfo[]>([]);
  const [open, setOpen] = useState(false);
  const [copyStatus, setCopyStatus] = useState('');
  useEffect(() => {
    const receive = (event: Event) => {
      const failure = (event as CustomEvent<FailureInfo>).detail;
      setFailures(items => [failure, ...items.filter(item => item.code !== failure.code || item.path !== failure.path || item.message !== failure.message)].slice(0, 20));
      if (failure.code === 'OPERATION_RESULT_UNKNOWN') setOpen(true);
    };
    window.addEventListener(API_FAILURE_EVENT, receive);
    return () => window.removeEventListener(API_FAILURE_EVENT, receive);
  }, []);
  return <>
    {failures.length > 0 && <Button className="error-center-trigger" size="small" onClick={() => setOpen(true)}>查看操作问题 · {failures.length}</Button>}
    <Modal title="最近的操作问题" open={open} onCancel={() => setOpen(false)} footer={<Button onClick={() => { setFailures([]); setOpen(false); }}>清空记录</Button>} width={720}>
      <Space orientation="vertical" style={{ width: '100%' }}>
        {failures.map((failure, index) => <Alert key={`${failure.time}:${index}`} type={failure.code === 'OPERATION_RESULT_UNKNOWN' ? 'warning' : 'error'} showIcon
          title={failure.title} description={<>
            <Typography.Paragraph>{failure.advice}</Typography.Paragraph>
            <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{failure.message}</Typography.Paragraph>
            <Typography.Text type="secondary">{failure.code} · {failure.requestId || '无请求编号'} · {new Date(failure.time).toLocaleTimeString()}</Typography.Text>
            <div><Button size="small" onClick={() => void Promise.resolve().then(() => navigator.clipboard.writeText(diagnosticText(failure))).then(() => setCopyStatus('诊断信息已复制')).catch(() => setCopyStatus('复制失败，可手动选择详情文本'))}>复制诊断信息</Button></div>
          </>} />)}
        <Typography.Text role="status">{copyStatus}</Typography.Text>
      </Space>
    </Modal>
  </>;
}
