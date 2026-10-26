import {existsSync,mkdirSync,readFileSync,writeFileSync,copyFileSync,cpSync,chmodSync,constants} from 'node:fs';
import {parseEnv} from 'node:util';
import {homedir} from 'node:os';
import path from 'node:path';
import {root,data} from './runtime.mjs';
const target=process.env.TRAVELBAO_DESKTOP_DATA||path.join(homedir(),'Library/Application Support/TravelBao');
if(existsSync(path.join(data,'dev-processes.json')))throw new Error('请先停止开发服务，再复制数据库。');
if(existsSync(path.join(target,'desktop-runtime.json')))throw new Error('请先退出桌面应用，再迁移。');
mkdirSync(target,{recursive:true,mode:0o700});chmodSync(target,0o700);
const configuration=path.join(target,'configuration.json');
if(!existsSync(configuration)){
 const local=existsSync(path.join(root,'.env'))?parseEnv(readFileSync(path.join(root,'.env'),'utf8')):{};
 const allowed=['DEEPSEEK_API_KEY','DEEPSEEK_BASE_URL','DEEPSEEK_MODEL','EMBEDDING_MODEL','KNOWLEDGE_MIN_SCORE','AGENT_MAX_STEPS','AGENT_MAX_TOOL_CALLS','AGENT_TIMEOUT_SECONDS','MODEL_TIMEOUT_SECONDS'];
 const selected=Object.fromEntries(allowed.filter(key=>local[key]!==undefined).map(key=>[key,local[key]]));
 writeFileSync(configuration,JSON.stringify(selected,null,2),{mode:0o600});
 console.log('本机配置已复制（不输出密钥）。');
}else console.log('保留已有桌面配置。');
const db=path.join(target,'db/travelbao.mv.db');mkdirSync(path.dirname(db),{recursive:true});
if(!existsSync(db)&&existsSync(path.join(data,'db/travelbao.mv.db'))){copyFileSync(path.join(data,'db/travelbao.mv.db'),db,constants.COPYFILE_FICLONE);chmodSync(db,0o600);console.log('已复制行程、聊天、知识库、向量与执行记录；原开发数据库保留。');}
else console.log('保留已有桌面数据库。');
const models=path.join(target,'ollama/models');
if(!existsSync(models)){mkdirSync(path.dirname(models),{recursive:true});cpSync(path.join(data,'ollama/models'),models,{recursive:true,verbatimSymlinks:true,mode:constants.COPYFILE_FICLONE});console.log('本机向量模型已复制，无需重新下载。');}
else console.log('保留已有桌面模型目录。');
console.log('桌面数据目录：'+target);
