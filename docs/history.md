# 阶段版本与技术栈

整理日期：2026-10-26。

本仓库在项目完成后，根据实际功能依赖回溯重建。提交使用本次真实整理时间；早期页面和部分衔接代码是为阶段运行重新组织的实现，**不代表当时逐行保存的原始Git历史**。原开发日期和当时的真实接口验收记录可见day1至day4报告。

## 六个可检出的版本

| 标签 | 更新的技术栈 | 本阶段可运行的功能 | Java测试 |
| --- | --- | --- | --- |
| `stage-01-scaffold` | Java17、Spring Boot、Maven、H2、Vue3、TypeScript、Vite | 页面、双Java应用骨架、主后端健康检查、启动停止脚本 | 1 |
| `stage-02-chat-advisor` | DeepSeek、Spring AI ChatClient、MessageChatMemoryAdvisor、JDBC记忆、SSE、MCP基础连接 | 流式咨询、独立会话、近期记忆、模型/工具/MCP/Embedding连接探针 | 3 |
| `stage-03-itinerary-tools` | 版本化Prompt、JSON结构化输出、BeanOutputConverter、本地Tools、BigDecimal、JDBC/H2 | 生成、调整、步行/日程/费用校验、编辑、保存、查询和Markdown导出 | 20 |
| `stage-04-rag` | RAG、Ollama/BGE-M3、余弦检索、城市过滤、RAG Advisor、事务向量索引 | 资料上传/去重/重建、知识问答、可追溯引用与行程来源 | 30 |
| `stage-05-mcp-react` | 真实旅游MCP、Open-Meteo、Photon/OSM、Valhalla、受控ReAct、SSE事件 | 智能规划/调整、工具参数和结果面板、取消、超时与执行记录恢复 | 45 |
| `stage-06-mac-desktop` | Swift、AppKit、WKWebView、jlink、Java/Ollama进程管理、桌面会话校验 | Mac桌面外壳、独立数据目录、迁移、原生导出与构建脚本 | 48 |

六阶段均分别通过Maven构建、主后端随机端口启动和健康接口测试、前端类型检查与Vite生产构建。测试使用隔离模型和本机HTTP夹具，不调用付费DeepSeek。最终阶段另验证Mac原生代码编译与包含生产页面的desktop构建配置。

每个阶段都是可构建的开发版本。真实对话仍需要本机模型配置，RAG需要Ollama及模型；构建成功不等于当前外部API可用。既有真实联网验收记录保留在文档中，本次回溯未对每个早期阶段重复付费API验收。

## 切换查看

先停止运行服务并保存未提交修改。以下示例查看RAG阶段：

```bash
git switch --detach stage-04-rag
./mvnw clean
cd frontend
npm ci
cd ..
node scripts/build.mjs
node scripts/dev.mjs
```

返回最新版：

```bash
node scripts/stop.mjs
git switch main
./mvnw clean
node scripts/build.mjs
```

清理Maven产物是为了避免后期阶段的旧class文件留在早期版本的target中；它不清理.data运行数据。已有数据库不要用于破坏性回退实验；需要隔离演示时另克隆一份目录，准备自己的.env与模型。不要对含密钥/数据库的工作目录运行无差别文件清理。

查看中文提交及技术栈说明：

```bash
git log --reverse --format='%h %s%n%b'
git show stage-05-mcp-react
```

## 最新版仓库约定

- 只上传源码、依赖锁文件、资料摘要、文档与脱敏截图。
- .env、数据库、聊天记录、向量模型、运行日志、依赖缓存和安装包均排除。
- 安装包在本机生成，不随Git提交。Mac构建默认输出 `release/macos/`；可用 `TRAVELBAO_PACKAGE_DIR` 指定其他目录。
- Mac应用与原有运行数据保留。本次仅将源码和版本历史接入Git，不向GitHub上传个人运行数据。
- 汇报PPT、讲稿与最终彩排按用户要求留到最后。
