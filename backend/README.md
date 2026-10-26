# 主后端

基础包名：`cn.travelbao`。使用 Spring Boot、Spring AI、H2 文件数据库，监听 `127.0.0.1:8080`。

- `config/`：模型、向量存储、MCP Client 和 Web 配置。
- `ai/agent/`：旅行智能体、执行上下文、调用轮数和超时控制。
- `ai/advisor/`：RAG 片段注入；普通会话记忆使用 Spring AI MessageChatMemoryAdvisor。
- `ai/prompt/`：提示词资源加载和变量组装。
- `ai/rag/`：分块、向量化、检索、引用和索引持久化。
- `ai/tools/`：预算计算与行程检查等本地工具。
- `trip/`：行程的生成结果校验、保存、编辑、查询和导出。
- `chat/`：会话、消息和流式事件。
- `knowledge/`：文档上传、元数据和索引状态。
- `common/`：异常处理等少量跨模块代码。

提示词原文放在 `src/main/resources/prompts/`。Controller 不直接拼提示词或调用外部旅游接口；费用使用十进制定点数或整数分计算。会话记忆按会话 ID 隔离。

`POST /api/chat` 接收 `message` 与可选 `conversationId`，返回 `conversation`、`status`、`delta`、`done` 或 `error` 事件。未指定会话时创建独立会话。启动时未配置密钥也能显示页面，发送时返回明确错误。

第二天加入：TripService 负责结构化生成与修改；ItineraryValidator 校验字段、日期、费用归属和时间；TravelRepository 使用参数化 JDBC 存储完整聊天历史与行程快照。AiConfiguration 注册 MessageChatMemoryAdvisor + MessageWindowChatMemory（十条消息）+ Spring AI JDBC 仓库，记忆不等于完整聊天历史。每个会话只允许一个写操作，行程修改/保存校验 expectedVersion，冲突返回 409。

本地 ItineraryTools 暴露 calculate_budget 与 check_schedule，第二天由后端在生成和编辑后固定执行，界面明确标注“后端校验”。第四天智能体提供同名最终提交工具，并复用这些确定性检查；实际调用记录与后端固定检查分别展示。金额使用 BigDecimal，全部交通/餐饮/门票按活动列支，住宿与备用金列为共同费用。

规划使用 DeepSeek JSON Output、BeanOutputConverter 和业务校验三层约束。同值的重复 JSON 字段先无损归一化；不同值的重复字段直接拒绝。无效结果最多向模型反馈一次校验错误，两次均失败则保留原草稿。支持“第 N 天少走路”的单日调整保护；可识别“不超过/降到 N 分钟”等上限，其他日期与共同费用由业务代码保留。偏好中的“每天/每日/全天步行不超过 N 分钟”也参与校验。更复杂的自然语言条件仍需用户核对。

DeepSeek 使用 Spring AI 1.1.8 的 `OpenAiChatModel` 兼容协议适配器，地址和密钥均来自 DeepSeek 配置，补充非思考模式参数以适配当前接口。BudgetProbeTool 是每次连接探针独立创建的工具，仍用于验证模型实际发起工具调用；与 ItineraryTools 业务检查分别记录。

接口与操作见 [第二天记录](../docs/day2-report.md)、[架构文档](../docs/architecture.md)。

第四天 AgentService 管理唯一工具循环，关闭 Spring AI 自动工具执行。三个旅游工具经 McpGateway 调用独立服务，加上 search_knowledge 与 calculate_budget 供模型选择。默认6轮/10工具/90秒，支持取消；通过最终校验才提交草稿。agent_runs 与 agent_events 保存全过程，重启恢复历史并终止中断任务。详见 [第四天验收](../docs/day4-report.md)。
