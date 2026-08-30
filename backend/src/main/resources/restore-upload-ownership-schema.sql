-- 既有上传文件没有可靠用户归属，保持 NULL，仅允许管理员或无 Web 身份的桌面模式使用。
ALTER TABLE restore_upload ADD COLUMN IF NOT EXISTS owner_user_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_restore_upload_owner ON restore_upload(owner_user_id, expires_at);
