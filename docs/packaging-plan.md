# 桌面打包与跨平台安排

2026-10-25：当前Apple Silicon Mac桌面包已交付，见 [桌面使用说明](desktop-macos.md)。汇报材料和讲稿留到最后。

| 平台 | 当前状态 | 方式与后续工作 |
| --- | --- | --- |
| 当前Apple Silicon Mac | `.app`与ZIP已构建并实测 | Swift/AppKit + WKWebView；复用Vue，随包Java17与Ollama，用户数据独立保存 |
| Intel Mac | 未构建/未测试 | 需对应架构的Java、Ollama与原生编译产物 |
| Windows | 未构建/未测试 | 可复用Vue与Java业务，另做Windows窗口、运行时与进程管理，并实机验证 |
| Android | 未构建/未测试 | 手机界面连接部署后的后端，另准备SDK、签名与实机测试 |

原计划使用Tauri2。当前Mac已有Swift/WebKit开发环境而没有Rust，因此首个Mac包采用原生外壳，避免引入额外工具链。原生代码集中在 `desktop/macos/`，业务仍在既有Vue、Spring AI与MCP模块中。

应用启动自己的Java/MCP与必要的Ollama服务，等待健康检查后显示页面；退出仅清理自身进程。安装目录只存程序，用户配置、H2数据库、日志、模型和导出位于用户Application Support。Java运行时通过jlink生成，使用其自带证书，未关闭HTTPS验证。编译时对当前CLT中重复的SwiftBridging模块映射采用项目内VFS overlay，不修改系统SDK。

构建：`node scripts/package-macos.mjs`。先构建Vue，再以Maven desktop profile把生产资源放入后端JAR，执行测试，编译原生程序，整理运行时/资源、临时签名并生成ZIP。当前Mac首次数据迁移使用 `node scripts/migrate-desktop-data.mjs`，执行前必须退出开发与桌面服务；已有桌面配置/数据库不会被覆盖。原数据复制保留，不是同步。

向量权重不放进安装包。当前Mac复制已有BGE-M3，新设备可通过应用菜单准备模型。模型和DeepSeek配置不随ZIP传播。公开分发前还需要Developer ID签名、Apple公证与其他设备验收。

Android不能把桌面JRE、Spring Boot JAR和Ollama作为普通网页资源装进APK直接运行。若要求完全在手机内运行，需要另外设计推理、存储和工具服务方案。现有后端仍只监听本机，本阶段未开放公网服务。

参考：[Apple WKWebView](https://developer.apple.com/documentation/webkit/wkwebview)、[WKDownload](https://developer.apple.com/documentation/webkit/wkdownload)、[Java17 jlink](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jlink.html)。
