# Repository Guidelines

## 项目结构与模块组织

本仓库是一个私有网络使用的 Web 数据库管理工具，后端负责 JDBC 访问和数据操作，前端负责数据库连接、SQL 工作台、表格编辑和备份任务界面。

- `backend/`：Spring Boot 3、Java 17、JDBC 后端。
  - `src/main/java/com/example/dbadmin/api`：REST 控制器。
  - `src/main/java/com/example/dbadmin/service`：业务逻辑。
  - `src/main/java/com/example/dbadmin/repo`：JDBC 持久化访问。
  - `src/main/resources`：`application.yml` 和初始化 schema。
  - `src/test/java`：JUnit 单元测试。
- `frontend/`：React、Vite、TypeScript 前端。
  - `src/components`：页面和复用组件。
  - `src/api.ts`、`src/types.ts`、`src/utils.ts`：客户端公共能力。
  - `src/styles.css`：全局布局和组件样式。

## 构建、测试与本地开发命令

后端：

```bash
cd backend
mvn spring-boot:run   # 启动后端 API，默认端口 8080
mvn test              # 运行全部后端测试
```

前端：

```bash
cd frontend
npm install           # 安装前端依赖
npm run dev           # 启动 Vite 开发服务
npm run build         # TypeScript 检查并构建生产资源
```

## 编码风格与命名约定

后端按现有分层放置代码：HTTP 入口在 `api`，业务规则在 `service`，数据库访问在 `repo`。简单 DTO 和模型优先沿用 Java `record`。方法名应直接表达行为，例如 `findByConnectionId`、`updateStatus`、`backupFile`。

前端使用严格 TypeScript 和 React 函数组件。组件 Props 需要显式类型，公共类型放在 `src/types.ts`。优先使用项目已有的 Ant Design 组件和当前样式体系，避免引入新的 UI 风格。

## 测试要求

后端测试使用 JUnit 5、AssertJ 和 Mockito。新增业务规则、校验逻辑、错误路径或持久化行为时，应补充聚焦测试。测试类命名遵循现有 `*Test` 模式。

提交前至少运行：

```bash
cd backend && mvn test
cd frontend && npm run build
```

## Git 提交与 Pull Request 约定

Git 提交信息统一使用中文，并且尽量详细。简单修改可使用一行中文标题；涉及多个模块或行为变化时，使用中文标题加多条中文说明，明确说明改了什么、为什么改、影响哪些功能。

推荐格式：

```text
完善备份任务管理并优化结果区布局

- 新增备份任务编辑、启停和删除接口
- 补充前端任务管理入口和删除确认
- 增加服务层测试覆盖 cron 校验和文件删除策略
```

Pull Request 应说明用户可见变化、验证命令、配置或 schema 影响；涉及界面变化时附截图或录屏。

## 安全与配置提示

不要提交真实数据库凭据。后端本地元数据使用 H2，配置位于 `backend/src/main/resources/application.yml`。密码加密依赖 `DB_ADMIN_CRYPTO_KEY`，不同环境应通过环境变量提供强密钥，不要写入 Git。
