import {spawnSync,execFileSync} from 'node:child_process';
import {existsSync,mkdirSync,copyFileSync,cpSync,rmSync,readFileSync,writeFileSync,constants} from 'node:fs';
import path from 'node:path';
import {homedir} from 'node:os';
import {root,data} from './runtime.mjs';
const output=path.resolve(process.env.TRAVELBAO_PACKAGE_DIR || path.join(root,'release/macos'));
if(process.platform!=='darwin'||process.arch!=='arm64')throw new Error('本脚本只构建当前Apple Silicon Mac。');
const app=path.join(output,'旅游宝.app'),contents=path.join(app,'Contents'),resources=path.join(contents,'Resources'),build=path.join(data,'desktop-build');
const runtimeRecord=path.join(process.env.TRAVELBAO_DESKTOP_DATA||path.join(homedir(),'Library/Application Support/TravelBao'),'desktop-runtime.json');
if(existsSync(runtimeRecord)){
 const record=JSON.parse(readFileSync(runtimeRecord,'utf8'));
 let running=false;try{process.kill(record.pid,0);running=true;}catch{}
 if(running&&record.bundlePath===app)throw new Error('请先退出旅游宝桌面应用，再重新打包。');
}
mkdirSync(build,{recursive:true});mkdirSync(output,{recursive:true});
function run(command,args,cwd=root){const r=spawnSync(command,args,{cwd,stdio:'inherit',env:{...process.env,MAVEN_USER_HOME:path.join(data,'m2'),CLANG_MODULE_CACHE_PATH:path.join(build,'clang')}});if(r.status!==0)throw new Error(`构建失败：${path.basename(command)}`);}
const swiftArgs=['-module-cache-path',path.join(build,'modules-clean')];
const staleMap='/Library/Developer/CommandLineTools/usr/include/swift/module.modulemap';
const currentMap='/Library/Developer/CommandLineTools/usr/include/swift/bridging.modulemap';
if(existsSync(staleMap)&&existsSync(currentMap)&&readFileSync(staleMap,'utf8').includes('module SwiftBridging')&&readFileSync(currentMap,'utf8').includes('module SwiftBridging')){
 writeFileSync(path.join(build,'empty.modulemap'),'// Ignore duplicate legacy CLT module map for this build only.\n');
 const overlay=path.join(build,'sdk-overlay.json');
 writeFileSync(overlay,JSON.stringify({version:0,roots:[{type:'file',name:staleMap,'external-contents':path.join(build,'empty.modulemap')}]}));
 swiftArgs.push('-vfsoverlay',overlay);
}
const jdk=execFileSync('/usr/libexec/java_home',['-v','17'],{encoding:'utf8'}).trim();
if(!process.argv.includes('--reuse-build')){
 run('npm',['run','build'],path.join(root,'frontend'));
 run(path.join(root,'mvnw'),['-B','-ntp','-Pdesktop','clean','package']);
 run('/usr/bin/swiftc',['-swift-version','5','-target','arm64-apple-macos14.0',...swiftArgs,path.join(root,'desktop/macos/TravelBao.swift'),'-o',path.join(build,'TravelBao'),'-framework','AppKit','-framework','WebKit']);
}
if(!existsSync(path.join(build,'runtime/bin/java')))run(path.join(jdk,'bin/jlink'),['--add-modules','ALL-MODULE-PATH','--strip-debug','--no-header-files','--no-man-pages','--compress=2','--output',path.join(build,'runtime')]);
if(!existsSync(path.join(build,'TravelBao.icns'))){
 run('/usr/bin/swiftc',[...swiftArgs,path.join(root,'desktop/macos/Icon.swift'),'-o',path.join(build,'icon-generator'),'-framework','AppKit']);
 run(path.join(build,'icon-generator'),[path.join(build,'TravelBao.iconset')]);
 run('/usr/bin/iconutil',['-c','icns',path.join(build,'TravelBao.iconset'),'-o',path.join(build,'TravelBao.icns')]);
}
// Only the named generated bundle is replaced. User configuration and databases are elsewhere.
if(existsSync(app))rmSync(app,{recursive:true});
mkdirSync(path.join(contents,'MacOS'),{recursive:true});mkdirSync(path.join(resources,'services'),{recursive:true});
copyFileSync(path.join(root,'desktop/macos/Info.plist'),path.join(contents,'Info.plist'));
copyFileSync(path.join(build,'TravelBao'),path.join(contents,'MacOS/TravelBao'));
copyFileSync(path.join(build,'TravelBao.icns'),path.join(resources,'TravelBao.icns'));
copyFileSync(path.join(root,'desktop/macos/OLLAMA-LICENSE'),path.join(resources,'OLLAMA-LICENSE'));
for(const [source,target] of [['backend/target/travelbao-backend-0.1.0-SNAPSHOT.jar','services/backend.jar'],['mcp-server/target/travelbao-mcp-server-0.1.0-SNAPSHOT.jar','services/mcp.jar']])copyFileSync(path.join(root,source),path.join(resources,target));
cpSync(path.join(build,'runtime'),path.join(resources,'runtime'),{recursive:true,verbatimSymlinks:true,mode:constants.COPYFILE_FICLONE});
cpSync(path.join(data,'tools/ollama'),path.join(resources,'ollama'),{recursive:true,verbatimSymlinks:true,mode:constants.COPYFILE_FICLONE});
cpSync(path.join(root,'knowledge-data'),path.join(resources,'knowledge-data'),{recursive:true});
writeFileSync(path.join(resources,'THIRD-PARTY-NOTICES.txt'),'Bundled OpenJDK 17: GPLv2 with Classpath Exception. See runtime/legal and runtime/release.\nOllama and inference dependencies: see ollama/*LICENSE and *NOTICE.\nBGE-M3 model is not distributed in this app. First setup downloads it or migrates the user local copy.\nTourism data: Open-Meteo / Photon / OpenStreetMap / FOSSGIS Valhalla. See application attribution.\n');
// The bundle is local/ad-hoc signed. Public distribution requires Developer ID and notarization.
run('/usr/bin/codesign',['--force','--deep','--sign','-',app]);
run('/usr/bin/codesign',['--verify','--deep','--strict',app]);
const zip=path.join(output,'旅游宝-Mac-AppleSilicon.zip');if(existsSync(zip))rmSync(zip);
run('/usr/bin/ditto',['-c','-k','--sequesterRsrc','--keepParent',app,zip]);
writeFileSync(path.join(build,'package.json'),JSON.stringify({builtAt:new Date().toISOString(),platform:'darwin-arm64',app,zip,runtime:'OpenJDK17',bundledOllama:true,bundledModels:false,signing:'ad-hoc'},null,2));
console.log('Mac桌面包已完成：'+app+'\n'+zip);
