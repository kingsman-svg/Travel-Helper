# 前端

使用 Vue 3.5.44、TypeScript 5.9.3、Vite 8.3.5，依赖固定在包清单和 npm 锁文件中。当前已实现旅行规划、已保存行程、知识库、连接状态与智能体执行面板；由 App.vue 管理当前工作区，暂不增加 Router / Pinia。持久状态以服务端 H2 为准，localStorage 仅保存当前会话 ID。

- `src/App.vue`：工作区导航、会话恢复、对话与生成/调整/保存操作。
- `src/components/TravelForm.vue`：旅行需求表单与预算包含项。
- `src/components/TripPlan.vue`：每日卡片、活动/费用编辑、预算和真实检查记录。
- `src/components/AgentPanel.vue`：真实工具结果、来源、时间与停止操作。
- `src/components/{KnowledgeView,SourceReferences}.vue`：资料与引用。
- `src/components/ConnectionsView.vue`：四类服务探针。
- `src/api/`：业务请求与 POST 流式响应处理。
- `src/types/`：行程、来源和执行事件类型。
- `src/assets/`、`src/styles/`：静态资源与样式。
- `public/`：需要直接提供的公共静态文件。

`views/`、`stores/`、`router/` 仍为后续扩展预留目录。问答继续使用 SSE，可停止生成；普通行程生成/调整使用同步结构化接口；智能体模式使用 POST SSE 并支持停止，进行中锁定本会话操作。日期、费用与活动的最终校验由后端执行。

开发服务暂定 `127.0.0.1:5173`，通过开发代理访问 `/api`。API 密钥只在 Java 服务端读取，不放入 `VITE_*` 变量或浏览器存储。

在本目录运行 `npm ci --cache ../.data/npm-cache` 准备依赖，`npm run build` 执行类型检查和生产构建。整体启动请使用项目根目录的 `node scripts/dev.mjs`，以加载正确端口配置。
