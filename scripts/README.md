# 本地脚本

当前可用（在项目根目录运行）：

```bash
node scripts/check-environment.mjs
node scripts/build.mjs
node scripts/dev.mjs
node scripts/verify-day1.mjs
node scripts/verify-day2.mjs
node scripts/verify-day2.mjs --after-restart
node scripts/verify-day3.mjs
node scripts/verify-day3.mjs --after-restart
node scripts/verify-day4.mjs
node scripts/verify-day4.mjs --after-restart
node scripts/evaluate-prompts.mjs
node scripts/package-macos.mjs
node scripts/migrate-desktop-data.mjs
node scripts/verify-desktop.mjs
node scripts/verify-day4.mjs --desktop
node scripts/verify-day4.mjs --desktop --after-restart
node scripts/stop.mjs
```

`check-environment.mjs` 只检查命令和文件，不读取密钥、不联网、不安装依赖。其他脚本职责如下：

- `build.mjs`：Maven 构建与测试、前端类型检查与生产构建。
- `dev.mjs`：解析 `.env`、复制运行 JAR、按依赖顺序启动服务；前端与 Ollama 子进程不继承模型密钥。
- `verify-day1.mjs`：真实接口验收，会消耗少量模型 API；记录状态、工具执行结果和向量维度，不记录密钥。
- `verify-day2.mjs`：真实业务验收，首次运行后需重启再加 `--after-restart` 验证；报告在 `.data/verification/day2.json`。会创建标记为验收的本机行程和独立会话。
- `verify-day3.mjs`：真实知识库验收，六份资料导入与去重、上传错误、城市过滤、重建、十条有据问答和两个无据问题、行程引用保存导出；再加 `--after-restart` 检查向量与出处恢复，报告在 `.data/verification/day3.json`。只有重启验收完成才标记 allPassed。
- `verify-day4.mjs`：真实 MCP 天气/地点/路线、DeepSeek 受控 ReAct、步行上限、保存导出和取消验收；重启后加 `--after-restart` 验证执行事件与已保存行程摘要完全恢复，报告在 `.data/verification/day4.json`。两阶段均通过才标记 allPassed。
- `evaluate-prompts.mjs`：运行单独的 PromptEvaluation，五组固定需求分别使用 v1/v2，共十次模型调用；正常构建不执行这项联网实验。报告在 `.data/verification/prompt-comparison.json`。
- `stop.mjs`：按进程记录停止本项目服务；不关闭复用的外部 Ollama。
- `package-macos.mjs`：当前Apple Silicon Mac完整构建、打包、临时签名和ZIP；默认运行全部构建与测试，运行前退出桌面应用。
- `migrate-desktop-data.mjs`：服务关闭时复制本机配置/数据库/模型至Application Support；保留原数据，不覆盖已有桌面配置或数据库。
- `verify-desktop.mjs`：桌面包运行期间验证生产页面、私有接口、迁移数据、真实检索/MCP天气/DeepSeek流式回复与包内数据隔离。报告desktop.json。
- `verify-day4.mjs --desktop`：使用桌面运行记录中的私有会话执行完整真实验收，报告day4-desktop.json；退出重开后加--after-restart。令牌不写入报告。
- `pull-embedding.mjs`：使用正常 Ollama 下载流程安装 Embedding 模型。
- `bootstrap-tools.py`：Python 3.12+，从官方源下载项目本地 Maven/Ollama，验证 Maven SHA-512。
- `import-embedding.py`：Python 3.12+，在代理不兼容时下载官方模型并验证 SHA-256，通过 Ollama 本地导入。

日志、进程记录、运行 JAR、下载缓存、向量模型和验收 JSON 都放在 `.data/`。模型密钥仅在服务端配置。第 5 天继续补齐完整业务验收。
