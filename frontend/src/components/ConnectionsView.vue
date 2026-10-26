<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { integrations, probe } from '../api/client';
import type { IntegrationResponse, ProbeResult } from '../types/api';
const state = ref<IntegrationResponse | null>(null), error = ref(''), active = ref('');
const results = ref<Record<string, ProbeResult>>({});
const items = [
  { key: 'deepseek', type: 'chat', name: 'DeepSeek 对话', description: '验证真实文本回复', icon: '✦' },
  { key: 'tools', type: 'tools', name: '模型工具调用', description: '模型调用预算工具并读取结果', icon: '↗' },
  { key: 'mcp', type: 'mcp', name: '旅游 MCP 服务', description: '发现工具并读取服务信息', icon: '⌘' },
  { key: 'embedding', type: 'embedding', name: '本地向量模型', description: '将中文旅游文本转换为向量', icon: '▧' },
] as const;
async function refresh() { try { state.value = await integrations(); error.value = ''; } catch { error.value = '暂时无法连接后端，请确认本机服务已启动。'; } }
async function run(type: string) { active.value = type; try { results.value[type] = await probe(type); await refresh(); } catch { error.value = '验证未完成，请重新检查连接。'; } finally { active.value = ''; } }
function label(key: keyof IntegrationResponse['integrations']) { return { passed: '已验证', failed: '需检查', unverified: '待验证', unconfigured: '待配置' }[state.value?.integrations[key].status || 'unverified']; }
onMounted(refresh);
</script>
<template>
  <section class="connections-view">
    <div class="eyebrow"><span></span>让每一步，都有真实连接</div><h1>服务连接</h1><p class="intro">查看模型、工具和本地知识检索的准备情况。</p>
    <div v-if="error" class="notice error-notice" role="alert">{{ error }}<button @click="refresh">重新检查</button></div>
    <div class="setup-guide"><span class="setup-icon">01</span><div><strong>连接你的 DeepSeek</strong><p>在项目根目录的 <code>.env</code> 中配置密钥后重启后端。密钥始终由后端读取。</p></div></div>
    <div class="integration-grid"><article v-for="item in items" :key="item.key" class="integration-card">
      <div class="integration-top"><span class="integration-icon">{{ item.icon }}</span><span class="status-tag" :class="state?.integrations[item.key].status">{{ label(item.key) }}</span></div>
      <h2>{{ item.name }}</h2><p>{{ item.description }}</p><div class="integration-meta">{{ state?.integrations[item.key].label || '等待后端连接' }}</div>
      <button class="probe-button" :disabled="!!active || !state" @click="run(item.type)">{{ active === item.type ? '正在验证…' : '验证连接' }}<span>↗</span></button>
      <div v-if="results[item.type]" class="probe-result" :class="results[item.type]?.status" role="status"><strong>{{ results[item.type]?.message }}</strong><small>{{ results[item.type]?.durationMs }} ms</small><details><summary>查看验证结果</summary><pre>{{ JSON.stringify(results[item.type]?.details, null, 2) }}</pre></details></div>
    </article></div>
    <p class="connection-note">模型验证会发起真实请求；MCP当前提供服务信息。天气、地点和路线将在后续里程碑接入。</p>
  </section>
</template>
