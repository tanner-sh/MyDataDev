import { useState } from 'react';
import { MODAL_WIDTH } from '../constants';
import { Alert, Checkbox, Input, Modal, Radio, Select, Space, Typography, Upload, message } from 'antd';
import { UploadOutlined } from '@ant-design/icons';
import { api, downloadBlob } from '../api';

import {
  ARCHIVE_FILE_PREFIX,
  archiveImportSummary,
  archiveMcpImportNotes,
  archivePassphraseError,
  isConfigArchive,
  MIN_ARCHIVE_PASSPHRASE_LENGTH,
  type ArchiveEnvelope,
  type ArchiveImportResult
} from '../configArchive';
import type { Connection } from '../types';

const { Text } = Typography;

/**
 * 连接配置的加密导出与导入。
 *
 * <p>桌面版和 Web 版用的是完全独立的元数据库与加密密钥，换机器时几十条连接只能一条条重建。
 * 归档用调用者自己给的口令加密 —— 不能用本机密钥，因为目标端的密钥必然不同。</p>
 *
 * <p>导出的文件里装着全部选中连接的密码与 SSH 密钥（由口令保护）。界面上必须把这件事说清楚，
 * 而不是做成一个轻描淡写的「导出」按钮。</p>
 */
export function ConnectionArchivePanel({
  open,
  connections,
  onClose,
  onImported
}: {
  open: boolean;
  connections: Connection[];
  onClose: () => void;
  onImported: () => void;
}) {
  const [mode, setMode] = useState<'export' | 'import'>('export');
  const [passphrase, setPassphrase] = useState('');
  const [confirmPassphrase, setConfirmPassphrase] = useState('');
  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const [archive, setArchive] = useState<ArchiveEnvelope>();
  const [archiveName, setArchiveName] = useState('');
  const [onConflict, setOnConflict] = useState<'SKIP' | 'RENAME'>('SKIP');
  const [includeMcp, setIncludeMcp] = useState(false);
  const [importMcp, setImportMcp] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState<ArchiveImportResult>();
  const [messageApi, messageContext] = message.useMessage();

  function reset() {
    setPassphrase('');
    setConfirmPassphrase('');
    setSelectedIds([]);
    setArchive(undefined);
    setArchiveName('');
    setError('');
    setResult(undefined);
    setIncludeMcp(false);
    setImportMcp(true);
  }

  async function runExport() {
    const passphraseError = archivePassphraseError(passphrase, confirmPassphrase);
    if (passphraseError) {
      setError(passphraseError);
      return;
    }
    setBusy(true);
    setError('');
    try {
      const envelope = await api<ArchiveEnvelope>('/admin/connections/archive/export', {
        method: 'POST',
        body: JSON.stringify({ passphrase, connectionIds: selectedIds, includeMcp })
      });
      const stamp = new Date().toISOString().slice(0, 10);
      downloadBlob(new Blob([JSON.stringify(envelope, null, 2)], { type: 'application/json' }),
        `${ARCHIVE_FILE_PREFIX}-${stamp}.json`);
      messageApi.success('已导出。文件里包含密码，请妥善保管并单独传递口令。');
      reset();
      onClose();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '导出失败');
    } finally {
      setBusy(false);
    }
  }

  async function runImport() {
    if (!archive) {
      setError('请先选择归档文件');
      return;
    }
    if (passphrase.length < MIN_ARCHIVE_PASSPHRASE_LENGTH) {
      setError(`口令至少需要 ${MIN_ARCHIVE_PASSPHRASE_LENGTH} 位`);
      return;
    }
    setBusy(true);
    setError('');
    try {
      const imported = await api<ArchiveImportResult>('/admin/connections/archive/import', {
        method: 'POST',
        body: JSON.stringify({ passphrase, archive, onConflict, importMcp })
      });
      setResult(imported);
      onImported();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '导入失败');
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      open={open}
      title="连接与 MCP 配置导出 / 导入"
      okText={mode === 'export' ? '导出' : '导入'}
      cancelText="关闭"
      confirmLoading={busy}
      onOk={() => void (mode === 'export' ? runExport() : runImport())}
      onCancel={() => {
        reset();
        onClose();
      }}
      destroyOnHidden
      width={MODAL_WIDTH.form}
    >
      {messageContext}
      <Space className="full-width" orientation="vertical" size={12}>
        <Radio.Group
          value={mode}
          onChange={(event) => {
            setMode(event.target.value);
            reset();
          }}
          options={[{ value: 'export', label: '导出' }, { value: 'import', label: '导入' }]}
          optionType="button"
        />

        <Alert
          type="warning"
          showIcon
          title="归档包含数据库密码与 SSH 密钥"
          description="文件由你设置的口令加密，本机的加密密钥不参与——因此换机器也能解开。口令请与文件分开传递；口令丢失后文件无法恢复。"
        />

        {mode === 'export' ? (
          <>
            <div>
              <Text type="secondary">要导出的连接（留空表示全部有权限的连接）</Text>
              <Select
                mode="multiple"
                allowClear
                style={{ width: '100%' }}
                placeholder="全部连接"
                value={selectedIds}
                onChange={setSelectedIds}
                optionFilterProp="label"
                options={connections.map((connection) => ({
                  value: connection.id,
                  label: `${connection.name} · ${connection.environment}`
                }))}
              />
            </div>
            <div>
              <Checkbox checked={includeMcp} onChange={(event) => setIncludeMcp(event.target.checked)}>
                同时导出 MCP 配置（Agent 与全局上限）
              </Checkbox>
              <div>
                <Text type="secondary">
                  Agent 的 Key 以哈希形式随归档带走，导入后 AI 客户端不用换 Key，只需改地址。只带上述连接的授权；允许的 Origin 不导出。
                </Text>
              </div>
            </div>
            <Input.Password
              placeholder={`设置归档口令（至少 ${MIN_ARCHIVE_PASSPHRASE_LENGTH} 位）`}
              value={passphrase}
              onChange={(event) => setPassphrase(event.target.value)}
            />
            <Input.Password
              placeholder="再输入一次"
              value={confirmPassphrase}
              onChange={(event) => setConfirmPassphrase(event.target.value)}
            />
          </>
        ) : (
          <>
            <Upload.Dragger
              accept=".json"
              maxCount={1}
              showUploadList={false}
              beforeUpload={(file) => {
                void file.text().then((text) => {
                  try {
                    const parsed = JSON.parse(text);
                    if (!isConfigArchive(parsed)) {
                      setError('这不是 MyDataDev 配置归档文件');
                      setArchive(undefined);
                      return;
                    }
                    setArchive(parsed);
                    setArchiveName(file.name);
                    setError('');
                  } catch {
                    setError('文件不是合法的 JSON');
                    setArchive(undefined);
                  }
                });
                return false;
              }}
            >
              <p><UploadOutlined /> 点击或拖入归档文件</p>
              {archiveName && <Text type="secondary">{archiveName}</Text>}
            </Upload.Dragger>
            <Input.Password
              placeholder="归档口令"
              value={passphrase}
              onChange={(event) => setPassphrase(event.target.value)}
            />
            <div>
              <Text type="secondary">遇到同名连接</Text>
              <Radio.Group
                value={onConflict}
                onChange={(event) => setOnConflict(event.target.value)}
                options={[
                  { value: 'SKIP', label: '跳过，保留本机的' },
                  { value: 'RENAME', label: '改名后新建' }
                ]}
                optionType="button"
                style={{ display: 'block', marginTop: 4 }}
              />
            </div>
            <Checkbox checked={importMcp} onChange={(event) => setImportMcp(event.target.checked)}>
              归档里有 MCP 配置时一并导入（同名 Agent 跳过，不覆盖本机的）
            </Checkbox>
            {result && <Alert type="success" showIcon title={archiveImportSummary(result)} />}
            {result?.mcp && (
              <Alert
                type={result.mcp.skippedAgents.length > 0 || result.mcp.droppedGrants.length > 0 ? 'warning' : 'info'}
                showIcon
                title="MCP 配置"
                description={(
                  <ul className="archive-mcp-notes">
                    {archiveMcpImportNotes(result.mcp, `${window.location.origin}/mcp`).map((note) => (
                      <li key={note}>{note}</li>
                    ))}
                  </ul>
                )}
              />
            )}
          </>
        )}

        {error && <Alert type="error" showIcon title={error} closable onClose={() => setError('')} />}
      </Space>
    </Modal>
  );
}
