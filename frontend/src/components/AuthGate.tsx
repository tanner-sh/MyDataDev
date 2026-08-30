import { useEffect, useState } from 'react';
import { Alert, Button, Card, ConfigProvider, Form, Input, Spin, Typography } from 'antd';
import { DatabaseOutlined, LockOutlined, UserOutlined } from '@ant-design/icons';
import zhCN from 'antd/locale/zh_CN';
import App from '../App';
import { AUTH_REQUIRED_EVENT, loadAuthStatus, login, type AuthStatus } from '../auth';

type LoginFields = { username: string; password: string };

export function AuthGate() {
  const [status, setStatus] = useState<AuthStatus>();
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let mounted = true;
    const refresh = () => {
      setError('');
      void loadAuthStatus()
        .then((next) => { if (mounted) setStatus(next); })
        .catch((cause) => { if (mounted) setError(cause instanceof Error ? cause.message : '无法连接服务器'); });
    };
    refresh();
    window.addEventListener(AUTH_REQUIRED_EVENT, refresh);
    return () => {
      mounted = false;
      window.removeEventListener(AUTH_REQUIRED_EVENT, refresh);
    };
  }, []);

  async function submit(values: LoginFields) {
    setSubmitting(true);
    setError('');
    try {
      setStatus(await login(values.username, values.password));
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
  if (status && (!status.enabled || status.authenticated)) return <App />;

  return (
    <ConfigProvider locale={zhCN}>
      <main className="auth-page">
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
      </main>
    </ConfigProvider>
  );
}
