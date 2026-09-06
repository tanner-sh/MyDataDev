import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { Alert, Button, Card, ConfigProvider, Form, Input, Spin, Typography } from 'antd';
import { DatabaseOutlined, LockOutlined, UserOutlined } from '@ant-design/icons';
import zhCN from 'antd/locale/zh_CN';
const App = lazy(() => import('../App'));
import { AUTH_REQUIRED_EVENT, AUTH_LOGOUT_EVENT, PERSIST_WORK_EVENT, workspaceIdentity, loadAuthStatus, login, type AuthStatus } from '../auth';

type LoginFields = { username: string; password: string };

export function AuthGate() {
  const refreshGeneration = useRef(0);
  const [status, setStatus] = useState<AuthStatus>();
  const [error, setError] = useState('');
  const [owner, setOwner] = useState<string>();
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let mounted = true;
    const refresh = () => {
      const generation = ++refreshGeneration.current;
      setError('');
      void loadAuthStatus()
        .then((next) => { if (mounted && generation === refreshGeneration.current) {
          window.dispatchEvent(new Event(PERSIST_WORK_EVENT));
          if (!next.enabled || next.authenticated) setOwner(workspaceIdentity());
          setStatus(next);
        } })
        .catch((cause) => { if (mounted && generation === refreshGeneration.current) setError(cause instanceof Error ? cause.message : '无法连接服务器'); });
    };
    refresh();
    const clearOwner = () => setOwner(undefined);
    window.addEventListener(AUTH_LOGOUT_EVENT, clearOwner);
    const expire = () => { setStatus(previous => previous ? { ...previous, authenticated: false } : previous); refresh(); };
    window.addEventListener(AUTH_REQUIRED_EVENT, expire);
    return () => {
      mounted = false;
      window.removeEventListener(AUTH_LOGOUT_EVENT, clearOwner);
      window.removeEventListener(AUTH_REQUIRED_EVENT, expire);
    };
  }, []);

  useEffect(() => {
    document.body.dataset.sessionLocked = String(Boolean(status?.enabled && !status.authenticated));
    return () => { delete document.body.dataset.sessionLocked; };
  }, [status?.enabled, status?.authenticated]);

  async function submit(values: LoginFields) {
    refreshGeneration.current++;
    setSubmitting(true);
    setError('');
    try {
      setStatus(await login(values.username, values.password));
      setOwner(workspaceIdentity());
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '登录失败');
    } finally {
      setSubmitting(false);
    }
  }

  if (!status && !error) {
    return <div className="auth-loading"><Spin size="large" tip="正在连接 MyDataDev…" /></div>;
  }
  if (!status) {
    return (
      <ConfigProvider locale={zhCN}>
        <main className="auth-page">
          <Card className="auth-card" variant="borderless">
            <Alert type="error" showIcon message="无法连接 MyDataDev" description={error} />
            <Button block type="primary" className="auth-retry" onClick={() => window.location.reload()}>重新连接</Button>
          </Card>
        </main>
      </ConfigProvider>
    );
  }
  const unlocked = !status.enabled || status.authenticated;

  return (
    <ConfigProvider locale={zhCN}>
      {owner && <div hidden={!unlocked} style={{ height: '100%' }}><Suspense fallback={<div className="auth-loading"><Spin tip="正在恢复工作台…" /></div>}><App key={owner} workspaceOwner={owner} workspaceLocked={!unlocked} /></Suspense></div>}
      {!unlocked && <main className="auth-page">
        <Card className="auth-card" variant="borderless">
          <div className="auth-brand"><DatabaseOutlined /></div>
          <Typography.Title level={3}>登录 MyDataDev</Typography.Title>
          <Typography.Paragraph type="secondary">使用分配给你的个人账号进入数据库工作台</Typography.Paragraph>
          {error && <Alert type="error" showIcon message={error} className="auth-error" />}
          {status.passwordLogin === false ? (
            <Button block type="primary" href={status.loginUrl || '/api/auth/sso/login'}>使用单点登录</Button>
          ) : <Form<LoginFields> layout="vertical" initialValues={{ username: 'admin' }} requiredMark={false} onFinish={submit}>
            <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
              <Input autoFocus autoComplete="username" prefix={<UserOutlined />} />
            </Form.Item>
            <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
              <Input.Password autoComplete="current-password" prefix={<LockOutlined />} />
            </Form.Item>
            <Button block type="primary" htmlType="submit" loading={submitting}>登录</Button>
          </Form>}
        </Card>
      </main>}
    </ConfigProvider>
  );
}
