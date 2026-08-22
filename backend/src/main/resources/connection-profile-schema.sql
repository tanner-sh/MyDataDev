-- 连接档案：把「这条连接是什么、归谁管、默认落在哪个库」记在连接本身上。
-- 分组与标签只用于组织连接列表；默认命名空间与初始化 SQL 会影响真实会话。
ALTER TABLE db_connection ADD COLUMN IF NOT EXISTS group_name VARCHAR(120);
ALTER TABLE db_connection ADD COLUMN IF NOT EXISTS tags VARCHAR(500);
ALTER TABLE db_connection ADD COLUMN IF NOT EXISTS default_schema VARCHAR(240);
ALTER TABLE db_connection ADD COLUMN IF NOT EXISTS init_sql CLOB;
ALTER TABLE db_connection ADD COLUMN IF NOT EXISTS description VARCHAR(1000);
