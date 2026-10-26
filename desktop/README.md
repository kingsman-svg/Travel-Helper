# Mac桌面外壳

`macos/TravelBao.swift`使用AppKit、WKWebView和Foundation Process，不依赖Node或Rust运行时。由项目根目录 `node scripts/package-macos.mjs` 构建；产物在项目release/macos目录（可用TRAVELBAO_PACKAGE_DIR修改）。

- 服务只监听loopback；前端静态资源由随包后端提供。
- 用户Application Support存配置/数据，配置600、目录700；私有运行记录含每次启动的桌面会话令牌，不写入测试报告。
- 随包Java17/Ollama；已有外部Ollama可复用。模型权重独立准备。
- 稳定后端端口保留WebView origin与localStorage，冲突则换空闲端口。
- 退出、子进程失败、重新启动、超时等待、同数据目录单实例、崩溃遗留进程路径核验分别处理。
- 外部http(s)链接交给默认浏览器；上传使用原生文件选择；导出写唯一Markdown文件并提示实际路径。

本机CLT存在两个重复SwiftBridging module map，打包脚本通过构建目录VFS overlay隐藏旧映射，不修改系统工具链。`--reuse-build`仅供已确认Java/Swift产物与源码一致时重打包资源，日常使用默认完整构建。

详见 `docs/desktop-macos.md`。不包含开发者签名或公证，不声称跨平台安装包已经完成。
