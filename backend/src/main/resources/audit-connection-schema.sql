-- 审计记录补上结构化的连接归属。
-- 之前连接是拼在 target 字符串里的，只要有一处调用方没照 "connection:<id>" 的格式写
-- （备份、恢复、连接自身的增删改都没有），按连接筛选就会静默漏掉那些记录。
ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS connection_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_audit_log_connection ON audit_log(connection_id, id);
