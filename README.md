# 旅游宝

基于 DeepSeek API、Spring AI、RAG 和 ReAct 的旅行规划与管理应用，目标是在当前 Mac 上完成开发和课堂演示。

## 提交历史说明

本仓库于2026-10-26在项目完成后，按实际功能依赖重建六个阶段快照；提交时间是本次整理时间，并非当时开发时保存的原始Git记录。早期阶段的页面与部分衔接代码为本次回溯重建，不能用于推断当时的逐行实现顺序。每个阶段均单独构建、执行测试与启动检查。切换说明见 [阶段版本指南](docs/history.md)。

## 当前进度

- [x] 建立主后端、MCP 服务、前端和资料目录。
- [x] 建立 Maven 模块关系、前端包清单和环境变量示例。
- [x] 明确五天开发计划、模块边界和验收场景。
- [x] 初始化 Spring Boot / Vue 应用并锁定依赖版本。
- [x] 接入 DeepSeek 真实流式对话与 MCP 服务信息工具。
- [x] 完成行程生成、连续调整、编辑、保存、导出与持久化 Advisor 会话记忆。
- [x] 完成预算/时间检查工具与五组 Prompt 对照实验。
- [x] 完成 RAG 资料导入、城市检索、引用问答与行程来源（第 3 天）。
- [x] 完成 ReAct 与真实旅游 MCP 工具编排（第 4 天）。

当前为第 4 天版本：智能体能自行选择知识库、天气、地点、路线和预算工具，根据真实结果生成或调整行程。执行面板展示参数、来源、耗时和结果；最多6轮决策、10次工具、90秒，支持停止。校验合格后更新草稿，保存、导出和重启恢复均保留来源与执行记录。

六份官方资料摘要覆盖杭州、苏州、上海；本机 BGE-M3 检索，DeepSeek 组织答案。普通对话使用 MessageChatMemoryAdvisor 载入近期十条消息；智能体从同一 JDBC 记忆仓库载入已完成的普通消息，并以独立 RAG Advisor 注入本次命中。H2 保存行程、聊天、记忆、资料、向量与执行事件。

安装包通过 `node scripts/package-macos.mjs` 在本机生成，默认输出到 `release/macos/`，可设置 `TRAVELBAO_PACKAGE_DIR` 修改；安装包和模型权重不进入Git仓库。

## Mac桌面版

当前Mac已生成 `旅游宝.app` 与 `旅游宝-Mac-AppleSilicon.zip`，双击.app即可使用，无需终端启动。随包包含Java与Ollama程序，复用现有Vue；当前配置、行程、知识库与模型已复制到用户Application Support，原开发数据保留，之后两套数据不自动同步。安装包不含密钥、数据库和向量权重。详见 [桌面版使用说明](docs/desktop-macos.md)。汇报材料和讲稿留到最后。

## 从哪里开始

- [五天开发计划](docs/development-plan.md)：日期、任务、依赖和每天的验收标准。
- [第四天 MCP 与 ReAct 验收](docs/day4-report.md)：真实工具链、取消、上限和重启恢复。
- [旅游数据来源与边界](docs/tourism-providers.md)：公开接口、覆盖范围、坐标与使用约束。
- [第三天 RAG 验收](docs/day3-report.md)：资料导入、检索、引用与真实验证结果。
- [第二天验收与操作说明](docs/day2-report.md)：已完成的功能、数据口径和验证记录。
- [自行验证清单](docs/verification-guide.md)：页面操作、通过标准、自动验收和重启检查。
- [Mac桌面版操作与验收](docs/desktop-macos.md)：启动、配置、导出、数据位置和已知边界。
- [跨平台打包方向](docs/packaging-plan.md)：Mac 桌面包优先，Windows 与 Android 分阶段适配。
- [架构与接口草案](docs/architecture.md)：六项 AI 技术的职责、数据流和接口约定。
- [目录职责](docs/project-structure.md)：代码应该放在哪里。
- [课堂演示脚本](docs/demo-script.md)：最终需要跑通的用户场景。

## 项目组成

| 目录 | 职责 |
| --- | --- |
| `backend/` | Spring Boot 主后端：行程、会话、知识库、AI 编排 |
| `mcp-server/` | 独立旅游 MCP 服务：天气、地点与路线工具 |
| `frontend/` | Vue 3 + TypeScript：规划、保存行程、知识库、执行记录 |
| `knowledge-data/` | 杭州、苏州、上海的示例资料与来源 |
| `scripts/` | 环境检查、构建、启动、停止、真实验收与 Prompt 对照 |
| `docs/` | 开发计划、架构说明、演示文档 |
| `.data/` | 本机数据库、模型、运行日志与验收数据，不提交 |

## 本机准备

先在项目根目录运行只读环境检查：

```bash
node scripts/check-environment.mjs
```

已检查到 JDK 17.0.19、Node.js 24.12.0 和 npm 11.6.2。项目固定使用 Spring Boot 3.5.16、Spring AI 1.1.8、Vue 3.5.44、Vite 8.3.5；Java 和 npm 依赖缓存放在 `.data/`，前端提交 `package-lock.json`。

需要填写密钥时，将 `.env.example` 复制为 `.env` 并在本机编辑。启动脚本直接解析 `.env`，不通过 shell 执行文件内容。修改配置后重启服务。旧模板中的 `MCP_SERVER_URL` 仍兼容，新模板使用 `MCP_BASE_URL` 和 `MCP_ENDPOINT`。

DeepSeek 负责对话和工具决策；Embedding 单独通过 Ollama 接入。默认模型名为 `deepseek-flash`，可在 `.env` 修改。通过 Spring AI 的兼容协议适配器设置 `thinking.type=disabled`；请求仍发送至 DeepSeek，无需其他模型提供方密钥。参考 [DeepSeek 更新日志](https://api-docs.deepseek.com/updates/) 与 [Ollama BGE-M3 模型说明](https://ollama.com/library/bge-m3)。

计划使用前端 `5173`、主后端 `8080`、MCP 服务 `8081`、Ollama `11434`；应用服务默认只监听本机。

## 启动与验证

在项目根目录运行：

```bash
# 构建后端、执行自动化测试，并构建前端
node scripts/build.mjs

# 启动 Ollama、MCP、后端和前端；本机已有的 Ollama 服务会复用
node scripts/dev.mjs
```

然后访问 [旅游宝本机页面](http://127.0.0.1:5173)。配置好密钥后，可在“连接状态”页面分别验证模型、工具、MCP 和 Embedding。

```bash
# 真实验收：会调用少量 DeepSeek API，报告保存在 .data/verification/day1.json
node scripts/verify-day1.mjs

# 第二天业务验收：真实生成、调整、编辑、保存、会话隔离
node scripts/verify-day2.mjs
# 停止并重新启动服务后验证数据与记忆恢复
node scripts/verify-day2.mjs --after-restart

# 第三天 RAG 验收：十条有据问答、两个无据问题及导入/引用校验
node scripts/verify-day3.mjs
# 停止并重新启动服务后验证向量与来源恢复
node scripts/verify-day3.mjs --after-restart

# 第四天真实智能体验收；停止并重启后再执行第二条
node scripts/verify-day4.mjs
node scripts/verify-day4.mjs --after-restart

# 可选：五组 Prompt 对照，共 10 次真实模型调用
node scripts/evaluate-prompts.mjs

# 停止本项目启动的服务，不关闭复用的外部 Ollama
node scripts/stop.mjs
```

首次准备前端依赖时，在 `frontend/` 运行 `npm ci --cache ../.data/npm-cache`。`./mvnw package` 也可单独构建 Java 模块。Maven Central 主域名在当前网络返回 403，项目通过 `.mvn/settings.xml` 使用公共镜像；Maven Wrapper 从 Apache 官方归档下载，默认缓存到项目 `.data/m2/`。

启动脚本将 JAR 复制到 `.data/run/` 再启动，重建 `target/` 不会破坏运行中的类加载；要启用新后端代码，需要停止后重新启动。前端支持热更新。日志位于 `.data/logs/`。

Ollama 二进制和模型缓存位于项目 `.data/`；官方程序还会在 `~/.ollama/` 管理默认本机服务标识。正常网络可在启动服务后运行 `node scripts/pull-embedding.mjs` 下载模型。若代理导致下载器地址校验失败，可用 Python 3.12+ 运行 `scripts/import-embedding.py`，从官方仓库下载模型、校验 SHA-256 后通过本地导入安装，不关闭下载器的安全校验。初次下载工具可使用同样版本的 Python 运行 `scripts/bootstrap-tools.py`。

阶段记录：[第一天](docs/day1-report.md)、[第二天](docs/day2-report.md)、[第三天](docs/day3-report.md)、[第四天](docs/day4-report.md)。

业务数据库位于 `.data/db/travelbao.mv.db`。浏览器仅记录当前会话 ID，刷新后从后端恢复；点击“保存行程”生成明确的保存版本，后续修改先更新草稿，导出始终使用最近保存版本。费用为全体旅客的人民币估算，住宿与备用金列在共同费用中，所有交通、餐饮和门票列在当天活动中；预算汇总使用 BigDecimal。超预算会提示，时间冲突与无效字段会拦截。

## 知识库使用

打开“旅行知识库”，点击“导入六份示例资料”（当前本机已导入，重复点击会去重）。先检索原文，或点击“让助手依据资料回答”跳转聊天，确认城市后发送。也可在聊天下方直接选“查知识库”；“咨询建议”保持普通流式问答。引用可展开查看摘录、核对日、片段 ID 和官方链接。

自有资料需填城市、标题、来源链接与核对日期；单文件不超过256KB、文本20–40000字，最多60份。上传者提供的来源不等于应用自动审核。六份示例的来源审核见 [资料核对记录](docs/rag-source-audit.md)，固定问题与人工参考答案在 `knowledge-data/qa-cases.json`。

向量索引保存在同一个 H2 文件中，以事务同时提交片段和索引元数据，无需额外向量服务。记录模型名称、权重摘要及维度；更换模型后点击“重建索引”。当前按城市筛选后逐条计算余弦相似度，适合本机小资料库。检索阈值默认0.50、最多4片段，不是正确率或可信度分数。

## 智能规划与智能调整

需求表上方默认勾选“智能体规划”。选择杭州、苏州或上海以及未来两周内的日期，再提交。生成后展开“ReAct · 真实工具执行”，可查看真实工具结果。已有行程可在聊天处选择“智能调整”；执行期间点击“停止智能规划”，原有效行程保留。取消勾选可使用原来的普通规划。

目前真实提供方为 Open-Meteo、Photon 与 FOSSGIS Valhalla，无需地图密钥。超预报范围会明确返回状态；公开服务可能暂时不可用。天气为预报，路线无实时路况和公交班次，费用为估计。详见 [提供方说明](docs/tourism-providers.md)。

## 五天交付范围

完成需求收集、行程生成与调整、行程保存与导出、带来源的知识库问答、预算工具、天气/地点/路线 MCP 工具，以及可查看执行记录的 ReAct 流程。首版面向本机单用户，重点覆盖三个示例城市。

暂缓账号体系、支付预订、酒店实时库存和全国攻略采集。真实信息、费用估算和假设场景在界面上分别标识。
