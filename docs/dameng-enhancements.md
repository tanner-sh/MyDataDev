# 达梦执行计划与真实库回归

本轮开放达梦 SQL 工作台、MCP 只读入口的估算执行计划，并修复真实库回归发现的表设计问题。无需 schema 迁移或新增应用配置。

## 执行计划

普通连接使用 `EXPLAIN FOR <查询>` 返回结构化计划，保留数据库提供的操作符、层级、表/索引、扫描范围、预测行数和代价等列。它不执行目标查询，也不使用 `AUTOTRACE` 或会话开关；沿用查询语句校验、权限、超时、结果行数/大小限制、审计和历史记录。

本轮 DM8 实测 `EXPLAIN FOR` 会被只读事务拒绝。因此只读连接和 MCP 入口使用原生 `EXPLAIN`，通过驱动 `DmdbStatement.getExplain()` 读取文本计划，逐行展示在 `DM_PLAN` 列中。此路径保留只读限制，不临时切换为可写；文本同样限制行数和长度。驱动作为运行时依赖，通过 JDBC unwrap 调用其扩展接口。

界面标记「达梦估算计划」，对 `CSCN2` 聚集索引扫描及较大的 `ROW_NUMS` 提供中文提示。`ROW_NUMS` 是算子结果集的预测行数，`COST` 是优化器估算代价，不能当成实际扫描量或运行耗时。未识别的算子照常展示，不强行给出结论。

参考：[达梦官方查询优化文档](https://eco.dameng.com/document/dm/zh-cn/pm/query-optimization.html)。

## 表设计修复

旧实现用完整列定义修改字段，但省略默认值或 `NOT NULL` 并不代表删除已有属性；注释变更也没有执行。

现在按变化分别生成类型、`DEFAULT NULL`、`NULL / NOT NULL` 和 `COMMENT ON COLUMN`，可清除默认值、切换可空性，并支持仅修改注释；未修改的属性保留。

## 已验证环境与范围

- 官方镜像：`dm8_single:dm8_20241022_rev244896_x86_rh6_64`，x86_64。
- 服务端报告：`03134284294-20241009-244896-20119`，DB Version `0x7000c`。
- JDBC：项目现有 `DmJdbcDriver8 8.1.5.45`。
- 测试实例：UTF-8、大小写敏感、兼容模式 0 / 2；使用独立容器和仅授予 RESOURCE 的 MYDATADEV_TEST 普通账号，未访问应用保存的连接。
- 新版 DM8、模式 0 / 2 之外的兼容模式、其他账号授权组合、集群环境尚未验证。

`DatabaseDamengCompatibilityTest` 覆盖：工作台与 MCP 服务入口的结构化计划、截断、编译失败后继续查询、拒绝非查询语句、序列 NEXTVAL 不被估算计划消费，以及默认值、可空性和注释往返。

`DatabaseCompatibilityTest` 的 7 条通用用例新增达梦：元数据/分页/编辑冲突/CSV，空值与中文特殊字符，SQL 导出回放（精度、时间、二进制），编辑批次失败回滚，手动事务提交/回滚，事务中阻止 DDL/COMMIT，表生命周期与设计。

本轮没有宣称覆盖达梦原生备份恢复、故障注入或跨兼容模式迁移。

## 本地运行

需要 Docker 和 Maven。脚本下载约 770 MiB 的官方镜像归档并校验固定 SHA-256；首次启动通常需要几十秒。测试容器仅绑定本机端口，不挂载用户数据，无需 privileged 权限。

```bash
bash scripts/start-dameng-test.sh
DM_TEST_MODE=0 bash scripts/configure-dameng-test.sh
TEST_REQUIRED_DATABASES=dm \
TEST_DM_URL='jdbc:dm://127.0.0.1:15239' \
TEST_DM_USER=MYDATADEV_TEST TEST_DM_COMPATIBLE_MODE=0 \
TEST_DM_PASSWORD='MyDataDev_Test2026!' \
mvn -f backend/pom.xml test '-Dtest=Database*CompatibilityTest'
docker rm -fv mydatadev-dm-test
```

以上是隔离实例的公开测试密码，不能用于实际部署。可以通过 `DM_TEST_CONTAINER`、`DM_TEST_PORT`、`DM_TEST_ARCHIVE` 调整容器名、端口及归档路径；复用已存在的容器会报错，不会删除已有容器。

连接自己的独立测试库时直接配置 `TEST_DM_*`，无需启动容器。`TEST_REQUIRED_DATABASES=dm` 使漏配 URL 直接失败；不配置真实库时，本地常规单元测试会跳过实库用例。

## CI

`dameng-compatibility.yml` 被 `ci.yml` 调用，也可单独手动触发。真实回归纳入 `CI Gate`，失败会阻止合并；保留 JUnit 报告和容器日志，任务结束清理测试实例。固定归档校验失败会中止，升级镜像时需同步检查版本、校验值和回归结果。

## 普通账号与恢复增强（J）

CI 为模式 0 / 2 分别启动全新的实例，configure-dameng-test.sh 只配置这个测试容器。模式 2 改动在重启后由管理员检查，普通账号通过空串语义核对测试环境。

新增 SQL 逻辑备份往返、关联表约束恢复和失败回滚/重试回归。修复 DM 系统生成索引和外键名称无法重新创建的问题，自动名称会转换为稳定名称；保留业务数据和唯一/外键约束。没有增加原生物理备份或跨模式迁移支持。
