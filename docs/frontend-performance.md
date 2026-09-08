# 前端性能验证

## 本次优化

- 查询结果的排序、筛选与编辑状态分开计算。未修改的行保留引用，切换编辑单元格不会重建所有行，也不会触发滚动归零。
- 结构标签页通过稳定参数和 `memo` 隔离更新。隐藏时暂停表格测量、取消关系与 DDL 的在途读取；设计稿和当前分区保留。
- 列宽只读取前 30 行，长文本达到显示宽度上限后停止计数。
- 本批导出达到 10,000 个单元格或 256,000 个文本字符时使用 Worker，提供生成状态和取消。小结果与 Worker 共用序列化实现，保留各格式、NULL、大整数与中文编码语义。
- SQL 结果、表数据快照和撤销历史共用 400,000 单位的估算预算。按最近访问时间回收非活动、无修改的缓存；表标签保留筛选、排序和页大小，重新打开时重取第一页。活动数据和未提交修改始终受保护，因此这是软预算，不是浏览器堆大小的硬限制。撤销历史里的共享对象只计一次。

## 构建与回归

```bash
cd frontend
npm test
npm run build
```

构建同时校验登录页首屏、SQL 工作台完整功能依赖链、编辑器，以及进入可输入工作台的静态依赖链。静态体积是传输与解析成本的参考，不能代替运行时耗时。

本次改动前首屏 gzip 238.9 KiB，可输入工作台静态依赖约 582.1 KiB，完整功能链 787.3 KiB。新增功能后首屏保持 238.9 KiB，可输入工作台约 583 KiB，完整功能链约 791 KiB。完整功能链预算从 790 调整为 798 KiB；首屏和编辑器预算不变，可输入工作台另设 610 KiB 上限。Worker 是独立按需资源，不包含在上述主线程静态依赖中。

2026-09-08 在本机 Node 24 上用 10,000 行、50 列做纯函数基准（预热 3 次，采样 15 次中位数）：旧版列宽计算约 5.78 ms，取样优化后约 0.16 ms，列宽输出一致。同一份数据进入一个单元格编辑时，行对象引用的变化数量从 10,000 降到 1。这些数字只说明该段计算与更新范围，不代表页面帧率或用户交互耗时的改善比例。

生产包浏览器回归使用系统 Chrome 与独立 H2 测试库：

```bash
cd backend
mvn -Pweb -DskipTests clean package
cd ..
node scripts/ui-smoke.mjs --serve "$PWD/backend/target/mydatadev-web.jar" --auth --work /tmp/mydatadev-ui-perf --shots /tmp/mydatadev-ui-perf-shots
```

脚本验证登录、表浏览与修改、分页、SQL 编辑器、结果表编辑不重置滚动、切换结构标签保留草稿，以及真实 Worker 生成的 Excel 包含全部 400 行。截图目录同时保存 `workbench-timings.json`。

## 运行时计时

浏览器 Performance 面板中可查看以下 User Timing 阶段；也可在控制台读取：

```js
console.table(performance.getEntriesByType('measure')
  .filter(entry => entry.name.startsWith('mydatadev:'))
  .map(entry => ({ stage: entry.name, milliseconds: Math.round(entry.duration) })));
```

| 阶段 | 起点 | 终点 |
| --- | --- | --- |
| `mydatadev:connections-ready` | 请求连接列表 | 连接和权限加载完成并提交界面后的绘制机会 |
| `mydatadev:sql-editor-ready` | 开始加载编辑器模块 | CodeMirror 挂载后的绘制机会 |
| `mydatadev:result-ready` | 提交 SQL 查询 | 结果虚拟表挂载、可用高度确定后的绘制机会 |

计时只记录阶段名和耗时，每阶段最多保留最近 20 次，不上报服务器，不写入 SQL、连接名或结果内容。计时包含相应请求和客户端处理，不能直接理解成数据库执行时间；后台标签页的绘制调度也会影响它。

比较前后版本时使用相同浏览器、机器、窗口尺寸和数据，分别记录冷缓存与热缓存。推荐覆盖默认分页、大量列、长文本、多结构标签，以及持续打开/关闭工作区后的堆快照。用 React Profiler 确认提交一次编辑时无关行和隐藏结构面板是否更新；用 Chrome Performance 检查滚动帧、主线程长任务和导出期间的响应。Node 纯函数基准或单元测试不能代替这些界面指标。
