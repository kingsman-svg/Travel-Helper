# 目录职责

## 仓库布局

```text
travel/
├── pom.xml                     Maven 父工程和两个 Java 模块
├── .mvn/wrapper/               第 1 天补充 Wrapper 文件
├── backend/
│   └── src/main/
│       ├── java/cn/travelbao/
│       │   ├── config/
│       │   ├── ai/
│       │   │   ├── agent/
│       │   │   ├── advisor/
│       │   │   ├── prompt/
│       │   │   ├── rag/
│       │   │   └── tools/
│       │   ├── trip/{controller,service,repository,entity,dto}/
│       │   ├── chat/{controller,service,repository,entity,dto}/
│       │   ├── knowledge/{controller,service,repository,entity,dto}/
│       │   └── common/{exception,web}/
│       └── resources/prompts/
├── mcp-server/
│   └── src/main/java/cn/travelbao/mcp/
│       ├── config/
│       ├── tools/
│       ├── provider/
│       └── model/
├── frontend/
│   ├── public/
│   └── src/{views,components,api,stores,types,router,assets,styles}/
├── knowledge-data/{hangzhou,suzhou,shanghai}/
├── scripts/
├── docs/
└── .data/
```

空目录用 `.gitkeep` 保留。两个 Java 模块各有测试目录。第二天已加入 trip 业务、chat 会话、ai/prompt 与 ai/tools 的实现；第三天已加入知识管理、向量检索和 RAG Advisor；第四天已加入 ReAct 执行、真实 MCP 提供方和执行面板。前端由 App.vue 与功能组件组成，views/stores/router 预留，未引入 Router/Pinia。

## 第二天的主要实现

- `backend/.../config/AiConfiguration.java`：DeepSeek 客户端、MessageChatMemoryAdvisor、十条消息窗口与 JDBC 记忆仓库。
- `backend/.../ai/prompt/TravelPrompts.java`：加载版本化提示词，组合权威需求、旧行程、JSON schema 与示例。
- `backend/.../ai/prompt/StructuredPlanGenerator.java`：DeepSeek JSON 模式、结构化转换、最多一次校验纠正。
- `backend/.../ai/prompt/{ItineraryOutputParser,AdjustmentRules}.java`：同值重复字段归一化、冲突字段拒绝、单日少走路约束及未修改日期保护。
- `backend/.../trip/service/{TripService,ItineraryValidator,MarkdownExporter}.java`：生成/修改、业务校验、Markdown 导出。
- `backend/.../trip/repository/TravelRepository.java`：H2 行程、草稿、版本、完整聊天历史；参数化 JDBC。
- `backend/.../chat/service/ConversationGate.java`：防止同会话并发写入。
- `backend/.../ai/tools/ItineraryTools.java`：BigDecimal 预算与时间/步行检查。
- `backend/src/main/resources/{schema.sql,prompts/v1/,prompts/v2/}`：业务表与 Prompt 原文。
- `frontend/src/components/{TravelForm,TripPlan,ConnectionsView}.vue`：需求、每日计划/费用编辑与连接检查。
- `backend/src/test/.../{Day2BusinessTest,Day2PersistenceTest,PromptEvaluation}.java`：离线业务测试与单独执行的真实 Prompt 实验。

## 第三天的主要实现

- `backend/.../knowledge/`：上传、去重、导入/失败/重试状态、原文查看、索引重建、搜索和引用问答接口。
- `backend/.../ai/rag/{KnowledgeChunker,OllamaKnowledgeEmbedder,PlanKnowledgeGrounding}.java`：正文分块、本机向量模型与行程引用绑定。
- `backend/.../ai/advisor/TravelKnowledgeAdvisor.java`：Spring AI BaseAdvisor，在记忆载入后注入本次检索片段，明确其为资料数据。
- `backend/.../knowledge/repository/KnowledgeRepository.java`：H2 文档、向量和索引元数据的事务存储。
- `backend/src/main/resources/prompts/v3/`：带资料约束的规划、调整、问答提示词；保留 v1/v2。
- `frontend/src/components/{KnowledgeView,SourceReferences}.vue`：知识库操作与可展开的原文引用；App.vue 接入“查知识库”。
- `knowledge-data/{catalog.json,qa-cases.json}`：六份示例目录与十二条固定验收问题。
- `backend/src/test/.../Day3KnowledgeTest.java`：去重、来源校验、城市隔离、模型变化、失败恢复和事务回滚。
- `scripts/verify-day3.mjs`：真实导入、问答、行程来源与重启验收。

## 第四天的主要实现

- `backend/.../ai/agent/{AgentService,AgentDecisionClient,AgentModels}.java`：唯一模型—工具循环、五个模型工具、版本校验、取消/超时/上限和成功提交。
- `backend/.../ai/agent/{AgentRepository,AgentController,TourismController}.java`：执行事件 H2 持久化、SSE、恢复与只读工具接口。
- `backend/.../config/{AgentConfiguration,AgentSettings,McpGateway}.java`：关闭 Spring AI 自动工具执行、运行限制、独立 MCP Client 调用。
- `backend/src/main/resources/prompts/v4/agent.txt`：真实工具、资料边界和最终提交约束。
- `mcp-server/.../provider/{PublicTravelProvider,ProviderSettings}.java`：Open-Meteo、Photon、Valhalla HTTP调用、缓存、节流与状态映射。
- `mcp-server/.../tools/TourismTools.java`：三个真实旅游 MCP 工具。
- `frontend/src/components/AgentPanel.vue`：工具参数/结果、来源、时间、耗时与停止操作。
- `backend/src/test/.../Day4AgentTest.java`、`mcp-server/src/test/.../PublicTravelProviderTest.java`：受控模型/HTTP夹具，覆盖终止和解析，不将夹具标作实时数据。
- `scripts/verify-day4.mjs`：真实工具链、步行上限、保存导出、取消与重启验收。

## 代码放置规则

| 要修改的内容 | 所在位置 |
| --- | --- |
| DeepSeek、Embedding、MCP Client 配置 | `backend/.../config/` |
| 模型工具循环、执行状态与终止控制 | `backend/.../ai/agent/` |
| 会话/偏好增强、调用统计 | `backend/.../ai/advisor/` |
| 提示词原文 | `backend/src/main/resources/prompts/` |
| 提示词加载与变量组装 | `backend/.../ai/prompt/` |
| 分块、向量化、检索与引用 | `backend/.../ai/rag/` |
| 预算与时间检查 | `backend/.../ai/tools/` |
| 行程保存、修改、导出 | `backend/.../trip/` |
| 会话与流式事件 | `backend/.../chat/` |
| 文档上传、索引状态与元数据 | `backend/.../knowledge/` |
| MCP 工具定义 | `mcp-server/.../tools/` |
| 旅游提供方 HTTP 接口 | `mcp-server/.../provider/` |
| 页面交互、卡片、引用与执行面板 | `frontend/src/views/` 与 `components/` |

主后端按业务模块组织，模块内部区分 Controller、Service、Repository、Entity、DTO。AI 能力集中于 `ai/`，避免在页面接口中重复拼装模型调用。首版不额外建立通用框架或共享 Java 模块。
