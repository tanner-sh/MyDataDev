-- SSH 隧道：目标库只对跳板机开放时，由后端先建隧道再连数据库。
-- 口令、私钥、私钥口令与数据库密码同样加密存放；主机指纹是明文，要回显给用户核对。
ALTER TABLE db_connection ADD COLUMN IF NOT EXISTS ssh_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE db_connection ADD COLUMN IF NOT EXISTS ssh_host VARCHAR(500);
ALTER TABLE db_connection ADD COLUMN IF NOT EXISTS ssh_port INT;
ALTER TABLE db_connection ADD COLUMN IF NOT EXISTS ssh_username VARCHAR(240);
ALTER TABLE db_connection ADD COLUMN IF NOT EXISTS ssh_auth_mode VARCHAR(20);
ALTER TABLE db_connection ADD COLUMN IF NOT EXISTS ssh_encrypted_password CLOB;
ALTER TABLE db_connection ADD COLUMN IF NOT EXISTS ssh_encrypted_private_key CLOB;
ALTER TABLE db_connection ADD COLUMN IF NOT EXISTS ssh_encrypted_passphrase CLOB;
ALTER TABLE db_connection ADD COLUMN IF NOT EXISTS ssh_server_fingerprint VARCHAR(200);
ALTER TABLE db_connection ADD COLUMN IF NOT EXISTS ssh_skip_host_key_check BOOLEAN DEFAULT FALSE;

UPDATE db_connection SET ssh_enabled = FALSE WHERE ssh_enabled IS NULL;
UPDATE db_connection SET ssh_skip_host_key_check = FALSE WHERE ssh_skip_host_key_check IS NULL;
