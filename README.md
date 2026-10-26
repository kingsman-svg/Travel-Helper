# 旅游宝 · 阶段04

本版本：RAG知识库、向量索引与引用。

> 本仓库在2026-10-26项目完成后回溯重建。阶段快照是教学整理，提交使用实际整理时间，并非原始开发时保存的版本；早期页面和衔接实现按依赖重新组织。

## 构建与启动

需要JDK17、Node22.12以上。安装前端依赖后构建：

```bash
cd frontend
npm ci
cd ..
node scripts/build.mjs
cp .env.example .env
# 在本机.env填写DeepSeek密钥；准备Ollama及BGE-M3后启动
node scripts/dev.mjs
```

页面 http://127.0.0.1:5173 ，后端8080，MCP8081，Ollama11434。`node scripts/stop.mjs`停止项目服务。没有密钥仍可显示页面；真实对话需要配置模型。构建与自动化测试不调用付费模型。

当前阶段没有真实旅游工具和ReAct智能体。
