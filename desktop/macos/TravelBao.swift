import AppKit
import WebKit
import Foundation
import Darwin

struct DesktopError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

@MainActor
final class TravelBaoApp: NSObject, NSApplicationDelegate, NSWindowDelegate, WKNavigationDelegate, WKUIDelegate, WKDownloadDelegate {
    let files = FileManager.default
    let resources = Bundle.main.resourceURL!
    let dataURL: URL
    var config: [String:String] = [:]
    var owned: [String:Process] = [:]
    var logs: [FileHandle] = []
    var window: NSWindow!
    var web: WKWebView!
    var statusView: NSView!
    var statusLabel: NSTextField!
    var progress: NSProgressIndicator!
    var starting = false
    var restartRequested = false
    var stopping = false
    var exiting = false
    var lockFD: Int32 = -1
    var backendPort = 0
    var mcpPort = 0
    var ollamaURL = ""
    var token = ""
    var healthTimer: Timer?
    var generation = 0
    var downloads: [ObjectIdentifier:URL] = [:]
    let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 2
        c.timeoutIntervalForResource = 3
        return URLSession(configuration: c)
    }()
    var runtimeFile: URL { dataURL.appendingPathComponent("desktop-runtime.json") }
    var configFile: URL { dataURL.appendingPathComponent("configuration.json") }

    override init() {
        if let override = ProcessInfo.processInfo.environment["TRAVELBAO_DESKTOP_DATA"] {
            dataURL = URL(fileURLWithPath: override, isDirectory: true)
        } else {
            dataURL = FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent("TravelBao",isDirectory:true)
        }
        super.init()
    }
    func applicationDidFinishLaunching(_ notification: Notification) {
        do {
            try files.createDirectory(at:dataURL,withIntermediateDirectories:true,attributes:[.posixPermissions:0o700])
            for folder in ["db","logs","ollama/models"] { try files.createDirectory(at:dataURL.appendingPathComponent(folder),withIntermediateDirectories:true) }
            lockFD = Darwin.open(dataURL.appendingPathComponent("desktop.lock").path,O_CREAT | O_RDWR,0o600)
            guard lockFD >= 0 && flock(lockFD,LOCK_EX | LOCK_NB) == 0 else { throw DesktopError(message:"这个数据目录已有旅游宝桌面进程。请先退出原窗口。") }
            try recoverOwnedProcesses()
            try loadConfiguration()
            menu(); makeWindow()
            Task { await launchServices() }
        } catch {
            let alert=NSAlert();alert.messageText="旅游宝无法启动";alert.informativeText=error.localizedDescription;alert.runModal()
            NSApp.terminate(nil)
        }
    }
    func menu() {
        let main=NSMenu();let appItem=NSMenuItem();main.addItem(appItem)
        let app=NSMenu();appItem.submenu=app
        for (title,selector,key) in [("关于旅游宝",#selector(about),""),("模型设置…",#selector(settings),","),("准备向量模型…",#selector(prepareModel),""),("重新启动服务",#selector(restart),"r"),("打开数据目录",#selector(openData),""),("打开日志目录",#selector(openLogs),""),("打开导出目录",#selector(openExports),""),("退出旅游宝",#selector(quit),"q")] {
            let item=NSMenuItem(title:title,action:selector,keyEquivalent:key);item.target=self;app.addItem(item)
        }
        let editItem=NSMenuItem();main.addItem(editItem);let edit=NSMenu(title:"编辑");editItem.submenu=edit
        for (name,sel,key) in [("撤销","undo:","z"),("剪切","cut:","x"),("复制","copy:","c"),("粘贴","paste:","v"),("全选","selectAll:","a")] { edit.addItem(withTitle:name,action:Selector(sel),keyEquivalent:key) }
        NSApp.mainMenu=main
    }
    func makeWindow() {
        window=NSWindow(contentRect:NSRect(x:0,y:0,width:1280,height:820),styleMask:[.titled,.closable,.miniaturizable,.resizable],backing:.buffered,defer:false)
        window.title="旅游宝";window.minSize=NSSize(width:900,height:650);window.delegate=self;window.center()
        let root=NSView();window.contentView=root
        let wc=WKWebViewConfiguration();wc.websiteDataStore = .default()
        web=WKWebView(frame:.zero,configuration:wc);web.navigationDelegate=self;web.uiDelegate=self;web.translatesAutoresizingMaskIntoConstraints=false;web.isHidden=true;root.addSubview(web)
        statusView=NSView();statusView.translatesAutoresizingMaskIntoConstraints=false;root.addSubview(statusView)
        NSLayoutConstraint.activate([web.leadingAnchor.constraint(equalTo:root.leadingAnchor),web.trailingAnchor.constraint(equalTo:root.trailingAnchor),web.topAnchor.constraint(equalTo:root.topAnchor),web.bottomAnchor.constraint(equalTo:root.bottomAnchor),statusView.leadingAnchor.constraint(equalTo:root.leadingAnchor),statusView.trailingAnchor.constraint(equalTo:root.trailingAnchor),statusView.topAnchor.constraint(equalTo:root.topAnchor),statusView.bottomAnchor.constraint(equalTo:root.bottomAnchor)])
        statusView.wantsLayer=true;statusView.layer?.backgroundColor=NSColor(calibratedRed:0.96,green:0.97,blue:0.93,alpha:1).cgColor
        let title=NSTextField(labelWithString:"旅游宝 · 让旅行慢慢安排好");title.font = .systemFont(ofSize:27,weight:.medium)
        statusLabel=NSTextField(wrappingLabelWithString:"正在准备本机服务…");statusLabel.alignment = .center;statusLabel.maximumNumberOfLines=5
        progress=NSProgressIndicator();progress.style = .spinning;progress.startAnimation(nil)
        let retry=NSButton(title:"重新启动",target:self,action:#selector(restart));let setup=NSButton(title:"模型设置",target:self,action:#selector(settings));let log=NSButton(title:"查看日志",target:self,action:#selector(openLogs))
        let buttons=NSStackView(views:[retry,setup,log]);buttons.spacing=14
        let stack=NSStackView(views:[title,progress,statusLabel,buttons]);stack.orientation = .vertical;stack.spacing=24;stack.translatesAutoresizingMaskIntoConstraints=false;statusView.addSubview(stack)
        NSLayoutConstraint.activate([stack.centerXAnchor.constraint(equalTo:statusView.centerXAnchor),stack.centerYAnchor.constraint(equalTo:statusView.centerYAnchor),stack.widthAnchor.constraint(lessThanOrEqualToConstant:700)])
        window.makeKeyAndOrderFront(nil);NSApp.activate(ignoringOtherApps:true)
    }
    func status(_ message:String) { statusLabel.stringValue=message }
    func loadConfiguration() throws {
        if files.fileExists(atPath:configFile.path) { config = try JSONDecoder().decode([String:String].self,from:Data(contentsOf:configFile)) }
        config["DEEPSEEK_BASE_URL"] = config["DEEPSEEK_BASE_URL"] ?? "https://api.deepseek.com"
        config["DEEPSEEK_MODEL"] = config["DEEPSEEK_MODEL"] ?? "deepseek-flash"
        config["EMBEDDING_MODEL"] = config["EMBEDDING_MODEL"] ?? "bge-m3"
    }
    func privateJSON(_ value:Any,to url:URL) throws {
        let bytes=try JSONSerialization.data(withJSONObject:value,options:[.prettyPrinted,.sortedKeys])
        try bytes.write(to:url,options:.atomic);try files.setAttributes([.posixPermissions:0o600],ofItemAtPath:url.path)
    }
    func baseEnvironment() -> [String:String] {
        let current=ProcessInfo.processInfo.environment
        var result:[String:String]=[:]
        for key in ["HOME","TMPDIR","LANG","LC_ALL","USER","PATH"] { result[key]=current[key] }
        result["PATH"]="/usr/bin:/bin:/usr/sbin:/sbin";return result
    }
    func freePort(preferred:Int=0) throws -> Int {
        let fd=socket(AF_INET,SOCK_STREAM,0);guard fd>=0 else { throw DesktopError(message:"无法分配本机端口。") };defer{close(fd)}
        var reuse:Int32=1;setsockopt(fd,SOL_SOCKET,SO_REUSEADDR,&reuse,socklen_t(MemoryLayout<Int32>.size))
        var address=sockaddr_in();address.sin_len=UInt8(MemoryLayout<sockaddr_in>.size);address.sin_family=sa_family_t(AF_INET);address.sin_addr.s_addr=inet_addr("127.0.0.1");address.sin_port=UInt16(clamping:preferred).bigEndian
        let rc=withUnsafePointer(to:&address){$0.withMemoryRebound(to:sockaddr.self,capacity:1){Darwin.bind(fd,$0,socklen_t(MemoryLayout<sockaddr_in>.size))}}
        if rc != 0 {if preferred>0{return try freePort()};throw DesktopError(message:"无法绑定本机端口。") }
        var length=socklen_t(MemoryLayout<sockaddr_in>.size)
        let result=withUnsafeMutablePointer(to:&address){$0.withMemoryRebound(to:sockaddr.self,capacity:1){getsockname(fd,$0,&length)}}
        guard result==0 else { throw DesktopError(message:"无法读取本机端口。") };return Int(UInt16(bigEndian:address.sin_port))
    }
    func start(_ name:String,_ executable:URL,_ arguments:[String],_ env:[String:String]) throws {
        let logURL=dataURL.appendingPathComponent("logs/\(name).log")
        if !files.fileExists(atPath:logURL.path) { files.createFile(atPath:logURL.path,contents:nil,attributes:[.posixPermissions:0o600]) }
        let handle=try FileHandle(forWritingTo:logURL);try handle.seekToEnd();logs.append(handle)
        let p=Process();p.executableURL=executable;p.arguments=arguments;p.environment=env;p.currentDirectoryURL=dataURL;p.standardOutput=handle;p.standardError=handle
        let thisGeneration=generation
        p.terminationHandler={ [weak self] _ in Task { @MainActor in
            guard let self=self,self.generation==thisGeneration,!self.stopping,!self.exiting else{return}
            self.statusView.isHidden=false;self.web.isHidden=true;self.status("\(name) 服务已退出。请查看日志后重新启动。");self.progress.stopAnimation(nil)
            await self.stopServices()
        }}
        try p.run();owned[name]=p;try saveRuntime(ready:false)
    }
    func saveRuntime(ready:Bool) throws {
        try privateJSON(["pid":ProcessInfo.processInfo.processIdentifier,"backendPort":backendPort,"mcpPort":mcpPort,"ollamaURL":ollamaURL,"token":token,"ready":ready,"bundlePath":Bundle.main.bundlePath,"processes":owned.map{["name":$0.key,"pid":$0.value.processIdentifier,"executable":$0.value.executableURL!.path]}],to:runtimeFile)
    }
    func request(_ url:String) -> URLRequest { var r=URLRequest(url:URL(string:url)!);r.setValue(token,forHTTPHeaderField:"X-TravelBao-Desktop");return r }
    func ready(_ url:String,seconds:Double=35) async -> Bool {
        let deadline=Date().addingTimeInterval(seconds)
        while Date()<deadline && !stopping && !exiting {
            if let (_,response)=try? await session.data(for:request(url)),(response as? HTTPURLResponse)?.statusCode==200 { return true }
            try? await Task.sleep(nanoseconds:300_000_000)
        };return false
    }
    func launchServices() async {
        guard !starting && !exiting else{return};starting=true;defer{starting=false;if restartRequested{restartRequested=false;restart()}}
        generation+=1;statusView.isHidden=false;web.isHidden=true;progress.startAnimation(nil)
        do {
            try loadConfiguration();backendPort=try freePort(preferred:Int(config["DESKTOP_BACKEND_PORT"] ?? "") ?? 0);config["DESKTOP_BACKEND_PORT"]=String(backendPort);try privateJSON(config,to:configFile);repeat{mcpPort=try freePort()}while mcpPort==backendPort
            token=UUID().uuidString+UUID().uuidString
            let java=resources.appendingPathComponent("runtime/bin/java"),ollama=resources.appendingPathComponent("ollama/ollama")
            let external=config["OLLAMA_BASE_URL"] ?? "http://127.0.0.1:11434"
            status("正在检查本机向量服务…")
            if await ready(external+"/api/version",seconds:1) { ollamaURL=external }
            else {
                let port=try freePort();ollamaURL="http://127.0.0.1:\(port)"
                var env=baseEnvironment();env["OLLAMA_HOST"]="127.0.0.1:\(port)";env["OLLAMA_MODELS"]=dataURL.appendingPathComponent("ollama/models").path;env["OLLAMA_NO_CLOUD"]="1"
                try start("ollama",ollama,["serve"],env)
                guard await ready(ollamaURL+"/api/version",seconds:25) else { throw DesktopError(message:"Ollama未就绪，请查看ollama日志。") }
            }
            status("正在启动旅游 MCP 服务…")
            var mcp=baseEnvironment();mcp["MCP_PORT"]=String(mcpPort)
            try start("mcp",java,["-Xmx256m","-jar",resources.appendingPathComponent("services/mcp.jar").path],mcp)
            guard await ready("http://127.0.0.1:\(mcpPort)/health") else { throw DesktopError(message:"MCP未就绪，请查看mcp日志。") }
            status("正在启动行程与知识库服务…")
            var env=baseEnvironment()
            for key in ["DEEPSEEK_BASE_URL","DEEPSEEK_MODEL","DEEPSEEK_API_KEY","EMBEDDING_MODEL","KNOWLEDGE_MIN_SCORE","AGENT_MAX_STEPS","AGENT_MAX_TOOL_CALLS","AGENT_TIMEOUT_SECONDS","MODEL_TIMEOUT_SECONDS"] { env[key]=config[key] }
            env["BACKEND_PORT"]=String(backendPort);env["MCP_BASE_URL"]="http://127.0.0.1:\(mcpPort)";env["MCP_ENDPOINT"]="/mcp";env["OLLAMA_BASE_URL"]=ollamaURL
            env["TRAVEL_DB_URL"]="jdbc:h2:file:\(dataURL.appendingPathComponent("db/travelbao").path);DB_CLOSE_ON_EXIT=FALSE"
            env["KNOWLEDGE_EXAMPLES_PATH"]=resources.appendingPathComponent("knowledge-data").path;env["TRAVEL_DESKTOP_TOKEN"]=token
            try start("backend",java,["-Xmx768m","-jar",resources.appendingPathComponent("services/backend.jar").path],env)
            guard await ready("http://127.0.0.1:\(backendPort)/api/health",seconds:45) else { throw DesktopError(message:"后端未就绪，请查看backend日志。") }
            let properties:[HTTPCookiePropertyKey:Any]=[.name:"travelbao_desktop",.value:token,.domain:"127.0.0.1",.path:"/",.version:0,.init("HttpOnly"):"TRUE",.init("SameSite"):"Strict"]
            guard let cookie=HTTPCookie(properties:properties) else { throw DesktopError(message:"无法建立桌面会话。") }
            await web.configuration.websiteDataStore.httpCookieStore.setCookie(cookie)
            try saveRuntime(ready:true)
            web.load(URLRequest(url:URL(string:"http://127.0.0.1:\(backendPort)/")!));web.isHidden=false;statusView.isHidden=true;progress.stopAnimation(nil)
            healthTimer?.invalidate();healthTimer=Timer.scheduledTimer(withTimeInterval:5,repeats:true){[weak self] _ in Task{@MainActor in
                guard let self=self,!self.starting,!self.stopping,!self.exiting else{return}
                if !(await self.ready("http://127.0.0.1:\(self.backendPort)/api/health",seconds:1)) {
                    self.statusView.isHidden=false;self.web.isHidden=true;self.status("后端连接中断，请重新启动服务。");self.healthTimer?.invalidate()
                }
            }}
            if (config["DEEPSEEK_API_KEY"] ?? "").isEmpty { settings() }
            if let (data,_)=try? await session.data(for:request(ollamaURL+"/api/tags")),let list=try? JSONSerialization.jsonObject(with:data) as? [String:Any],let models=list["models"] as? [[String:Any]],!models.contains(where:{($0["name"] as? String ?? "").split(separator:":").first==Substring(config["EMBEDDING_MODEL"] ?? "bge-m3")}) {
                let a=NSAlert();a.messageText="首次使用需准备向量模型";a.informativeText="普通对话和规划可先使用。知识库需要下载 \(config["EMBEDDING_MODEL"] ?? "bge-m3")，可在“旅游宝 → 准备向量模型”中完成；模型文件可能达数GB。";a.runModal()
            }
        } catch { await stopServices();statusView.isHidden=false;web.isHidden=true;progress.stopAnimation(nil);status(error.localizedDescription) }
    }
    func stopServices() async {
        stopping=true;generation+=1;healthTimer?.invalidate()
        let children=Array(owned.values)
        for p in children where p.isRunning { p.terminate() }
        let deadline=Date().addingTimeInterval(6)
        while children.contains(where:{$0.isRunning}) && Date()<deadline { try? await Task.sleep(nanoseconds:100_000_000) }
        for p in children where p.isRunning { kill(p.processIdentifier,SIGKILL) }
        owned.removeAll();for f in logs {try? f.close()};logs.removeAll();try? files.removeItem(at:runtimeFile);stopping=false
    }
    func recoverOwnedProcesses() throws {
        guard let data=try? Data(contentsOf:runtimeFile),let saved=try? JSONSerialization.jsonObject(with:data) as? [String:Any],let rows=saved["processes"] as? [[String:Any]] else{return}
        for row in rows {
            guard let pid=row["pid"] as? Int32,let executable=row["executable"] as? String,executable.contains("/旅游宝.app/Contents/Resources/") else{continue}
            // Compare the actual executable path before signalling a possibly reused PID.
            var buffer=[CChar](repeating:0,count:4 * Int(MAXPATHLEN))
            if proc_pidpath(pid,&buffer,UInt32(buffer.count))>0,String(cString:buffer)==executable { kill(pid,SIGTERM) }
        }
        try? files.removeItem(at:runtimeFile)
    }
    @objc func restart() { guard !starting && !stopping && !exiting else{return};Task{await stopServices();await launchServices()} }
    @objc func openData() { NSWorkspace.shared.open(dataURL) }
    @objc func openExports() { let folder=dataURL.appendingPathComponent("Exports");try? files.createDirectory(at:folder,withIntermediateDirectories:true);NSWorkspace.shared.open(folder) }
    @objc func openLogs() { NSWorkspace.shared.open(dataURL.appendingPathComponent("logs")) }
    @objc func about() { let a=NSAlert();a.messageText="旅游宝 0.5.0";a.informativeText="Mac本机课堂版本 · Apple Silicon\nVue + Spring AI + DeepSeek + MCP + RAG\n数据存放在用户应用支持目录；退出会停止自身服务。";a.runModal() }
    @objc func quit() { NSApp.terminate(nil) }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender:NSApplication)->Bool { true }
    func applicationShouldTerminate(_ sender:NSApplication)->NSApplication.TerminateReply {
        if exiting{return .terminateLater};exiting=true
        Task {await stopServices();if lockFD>=0 {flock(lockFD,LOCK_UN);close(lockFD)};NSApp.reply(toApplicationShouldTerminate:true)}
        return .terminateLater
    }
    @objc func settings() {
        guard !stopping && !exiting else{return}
        let alert=NSAlert();alert.messageText="模型设置";alert.informativeText="密钥仅保存在本机数据目录（文件权限600），不进入安装包或网页。留空保留已有密钥。保存后重新启动服务。"
        let box=NSView(frame:NSRect(x:0,y:0,width:410,height:148))
        let model=NSTextField(string:config["DEEPSEEK_MODEL"] ?? "deepseek-flash");model.frame=NSRect(x:90,y:104,width:310,height:26)
        let base=NSTextField(string:config["DEEPSEEK_BASE_URL"] ?? "https://api.deepseek.com");base.frame=NSRect(x:90,y:60,width:310,height:26)
        let key=NSSecureTextField(frame:NSRect(x:90,y:16,width:310,height:26));key.placeholderString=(config["DEEPSEEK_API_KEY"] ?? "").isEmpty ? "填写DeepSeek API密钥" : "已配置，留空保留"
        for (text,y) in [("模型名称",108),("API地址",64),("API密钥",20)] {let l=NSTextField(labelWithString:text);l.frame=NSRect(x:0,y:y,width:85,height:22);box.addSubview(l)}
        for view in [model,base,key] {box.addSubview(view)};alert.accessoryView=box;alert.addButton(withTitle:"保存并重启");alert.addButton(withTitle:"取消")
        guard alert.runModal() == .alertFirstButtonReturn else{return}
        guard let url=URL(string:base.stringValue),url.scheme=="https",url.host != nil,!model.stringValue.trimmingCharacters(in:.whitespaces).isEmpty else { let a=NSAlert();a.messageText="请填写有效的HTTPS API地址与模型名称。";a.runModal();return }
        config["DEEPSEEK_BASE_URL"]=base.stringValue;config["DEEPSEEK_MODEL"]=model.stringValue
        if !key.stringValue.isEmpty {config["DEEPSEEK_API_KEY"]=key.stringValue}
        do{try privateJSON(config,to:configFile);if starting{restartRequested=true}else{restart()}}catch{status(error.localizedDescription)}
    }
    @objc func prepareModel() {
        guard !starting && !stopping && !exiting,!ollamaURL.isEmpty,owned["model-download"]==nil else{return}
        let a=NSAlert();a.messageText="下载本地向量模型";a.informativeText="将通过Ollama官方模型服务下载 \(config["EMBEDDING_MODEL"] ?? "bge-m3")。文件可能达数GB，请保持应用开启。进度写入日志目录的model-download.log。";a.addButton(withTitle:"开始下载");a.addButton(withTitle:"取消")
        guard a.runModal() == .alertFirstButtonReturn else{return}
        do{var env=baseEnvironment();env["OLLAMA_HOST"]=ollamaURL;try start("model-download",resources.appendingPathComponent("ollama/ollama"),["pull",config["EMBEDDING_MODEL"] ?? "bge-m3"],env)
            owned["model-download"]?.terminationHandler={ [weak self] p in Task{@MainActor in guard let self=self,!self.exiting,!self.stopping else{return};self.owned.removeValue(forKey:"model-download");let alert=NSAlert();alert.messageText=p.terminationStatus==0 ? "向量模型已准备完成" : "模型下载未完成，请查看日志后重试";alert.runModal()} }
        }catch{let alert=NSAlert();alert.messageText="无法启动模型下载";alert.informativeText=error.localizedDescription;alert.runModal()}
    }
    func webView(_ webView:WKWebView,decidePolicyFor action:WKNavigationAction,decisionHandler:@escaping(WKNavigationActionPolicy)->Void) {
        guard let url=action.request.url else{decisionHandler(.cancel);return}
        if url.scheme=="http",url.host=="127.0.0.1",url.port==backendPort {decisionHandler(action.shouldPerformDownload ? .download : .allow)}
        else if ["https","http"].contains(url.scheme ?? "") {if action.navigationType == .linkActivated {NSWorkspace.shared.open(url)};decisionHandler(.cancel)}
        else {decisionHandler(.cancel)}
    }
    func webView(_ webView:WKWebView,decidePolicyFor response:WKNavigationResponse,decisionHandler:@escaping(WKNavigationResponsePolicy)->Void) {
        let disposition=(response.response as? HTTPURLResponse)?.value(forHTTPHeaderField:"Content-Disposition") ?? ""
        decisionHandler(disposition.lowercased().contains("attachment") ? .download : .allow)
    }
    func webView(_ webView:WKWebView,navigationAction:WKNavigationAction,didBecome download:WKDownload) {download.delegate=self}
    func webView(_ webView:WKWebView,navigationResponse:WKNavigationResponse,didBecome download:WKDownload) {download.delegate=self}
    func download(_ download:WKDownload,decideDestinationUsing response:URLResponse,suggestedFilename:String,completionHandler:@escaping(URL?)->Void) {
        do {
            let folder=dataURL.appendingPathComponent("Exports",isDirectory:true)
            try files.createDirectory(at:folder,withIntermediateDirectories:true,attributes:[.posixPermissions:0o700])
            let stem=URL(fileURLWithPath:suggestedFilename).deletingPathExtension().lastPathComponent
            let destination=folder.appendingPathComponent(stem+"-"+UUID().uuidString.prefix(8)+".md")
            downloads[ObjectIdentifier(download)]=destination;completionHandler(destination)
        } catch { completionHandler(nil) }
    }
    func downloadDidFinish(_ download:WKDownload) {
        guard let destination=downloads.removeValue(forKey:ObjectIdentifier(download)) else{return}
        let alert=NSAlert();alert.messageText="行程已导出";alert.informativeText="Markdown文件已保存至：\n"+destination.path;alert.addButton(withTitle:"完成");alert.addButton(withTitle:"打开所在位置")
        alert.beginSheetModal(for:window){ result in if result == .alertSecondButtonReturn {NSWorkspace.shared.activateFileViewerSelecting([destination])} }
    }
    func download(_ download:WKDownload,didFailWithError error:Error,resumeData:Data?) {downloads.removeValue(forKey:ObjectIdentifier(download));if (error as NSError).code == NSURLErrorCancelled{return};let a=NSAlert();a.messageText="导出未完成";a.informativeText="请重试或检查目标目录。";a.runModal()}
    func webView(_ webView:WKWebView,runOpenPanelWith parameters:WKOpenPanelParameters,initiatedByFrame frame:WKFrameInfo,completionHandler:@escaping([URL]?)->Void) {
        let panel=NSOpenPanel();panel.allowsMultipleSelection=parameters.allowsMultipleSelection;panel.canChooseDirectories=false;panel.canChooseFiles=true
        panel.beginSheetModal(for:window){ result in completionHandler(result == .OK ? panel.urls : nil) }
    }
    func webViewWebContentProcessDidTerminate(_ webView:WKWebView) {webView.reload()}
}
MainActor.assumeIsolated {
    let app=NSApplication.shared
    let delegate=TravelBaoApp()
    app.setActivationPolicy(.regular);app.delegate=delegate;app.run()
}
