<script setup lang="ts">
import { computed } from 'vue';
import type { AgentRun, KnowledgeCitation } from '../types/api';
import SourceReferences from './SourceReferences.vue';
const props=defineProps<{run:AgentRun;cancelling:boolean}>();
defineEmits<{cancel:[]}>();
type Entry={id:string;name:string;arguments:Record<string,unknown>;result:Record<string,unknown>|null;durationMs?:number};
const labels:Record<string,string>={RUNNING:'正在执行',COMPLETED:'已完成',CANCELLED:'已停止',TIMED_OUT:'已超时',LIMIT_REACHED:'已达执行上限',FAILED:'未完成'};
const names:Record<string,string>={search_knowledge:'查询知识库',weather_forecast:'查询天气预报',search_places:'搜索地点',plan_route:'查询道路路线',calculate_budget:'校验预算与日程'};
const tools=computed(()=>{const result:Entry[]=[];for(const event of props.run.events){if(event.type==='tool_start')result.push({id:String(event.data.callId),name:String(event.data.name),arguments:event.data.arguments as Record<string,unknown>,result:null});if(event.type==='tool_result'){const item=result.find(t=>t.id===event.data.callId);if(item){item.result=event.data.result as Record<string,unknown>;item.durationMs=Number(event.data.durationMs);}}}return result;});
const lastAction=computed(()=>props.run.events.filter(e=>e.type==='action').at(-1));
const citations=computed(()=>{const map=new Map<string,KnowledgeCitation>();for(const event of props.run.events)if(event.type==='retrieval')for(const hit of event.data.hits as KnowledgeCitation[])map.set(hit.chunkId,hit);return [...map.values()];});
function values(tool:Entry){return (tool.result?.data||{}) as Record<string,unknown>;}
function places(tool:Entry){return (values(tool).places||[]) as {id:string;name:string;category:string;address:string;sourceUrl:string}[];}
function status(tool:Entry){const value=tool.result?.status;return value==='AVAILABLE'||value==='VALID'||value==='MATCHED'?'有结果':value==='NO_MATCH'?'暂无匹配':tool.result?'已返回状态':'等待返回';}
function weather(tool:Entry){const v=values(tool);return `${v.date} · ${v.temperature_2m_min}–${v.temperature_2m_max}°C · 最高降水概率 ${v.precipitation_probability_max}%`;}
</script>
<template>
  <details class="agent-panel" aria-label="智能体执行记录" :open="run.status==='RUNNING'">
    <summary class="agent-heading"><div><div class="eyebrow"><span></span>ReAct · 真实工具执行</div><h2>一步步，把旅行安排好</h2></div><span class="status-tag" :class="run.status==='COMPLETED'?'passed':''">{{labels[run.status]||run.status}} · {{tools.length}} 次工具 ⌄</span></summary>
    <div class="agent-progress"><p>{{run.status==='RUNNING'?lastAction?.data.summary||'正在连接模型与旅游工具…':run.message||'本次执行已结束。'}}</p><button v-if="run.status==='RUNNING'" class="stop-button" :disabled="cancelling" @click="$emit('cancel')">{{cancelling?'正在停止…':'停止智能规划 ■'}}</button></div>
    <p class="agent-meta">{{new Date(run.startedAt).toLocaleString('zh-CN')}} · 最多6轮决策 / 10次工具 / 90秒 · {{tools.length}} 次实际工具请求</p>
    <ol class="agent-timeline"><li v-for="(tool,index) in tools" :key="tool.id"><span class="agent-step">{{index+1}}</span><div class="agent-tool"><div class="agent-tool-title"><h3>{{names[tool.name]||tool.name}}</h3><small>{{status(tool)}}{{tool.durationMs!==undefined?' · '+tool.durationMs+' ms':''}}</small></div><p class="agent-tool-name">{{tool.name}} · 模型选择</p><p v-if="tool.result?.message" class="agent-result-note">{{tool.result.message}}</p>
      <template v-if="tool.result?.status==='AVAILABLE'"><p v-if="tool.name==='weather_forecast'" class="agent-value">{{weather(tool)}}</p><div v-if="tool.name==='search_places'" class="agent-places"><a v-for="place in places(tool)" :key="place.id" :href="place.sourceUrl" target="_blank" rel="noopener noreferrer"><strong>{{place.name}}</strong><small>{{place.category}} · {{place.address}}</small></a></div><template v-if="tool.name==='plan_route'"><p class="agent-value">{{values(tool).distanceKm}} km · 预计 {{values(tool).durationMinutes}} 分钟 · {{values(tool).mode==='walking'?'步行道路模型':'驾车道路模型'}}</p><p v-if="values(tool).hasFerry" class="form-error">此路线包含渡轮，需另行确认运营与班次。</p><a class="text-link" :href="String(values(tool).mapUrl)" target="_blank" rel="noopener noreferrer">打开地图重新查询 ↗</a></template></template>
      <p v-if="tool.result?.provider" class="agent-provider"><a :href="String(tool.result.sourceUrl)" target="_blank" rel="noopener noreferrer">{{tool.result.provider}} ↗</a> · 查询 {{new Date(String(tool.result.queriedAt)).toLocaleString('zh-CN')}}<span v-if="values(tool).fetchedAt"> · 数据获取 {{new Date(String(values(tool).fetchedAt)).toLocaleString('zh-CN')}}</span></p>
      <details><summary>查看参数与实际结果</summary><pre>{{JSON.stringify({arguments:tool.arguments,result:tool.result},null,2)}}</pre></details>
    </div></li></ol>
    <SourceReferences v-if="citations.length" title="本次检索命中（行程实际引用见下方）" :citations="citations"/>
    <p class="agent-footnote">天气预报来自 Open-Meteo · 地点来自 Photon / © OpenStreetMap contributors · 路线来自 FOSSGIS Valhalla。道路耗时为模型估计，无实时路况与公交班次；历史记录保留执行当时结果，后续编辑可能改变行程。</p>
  </details>
</template>
