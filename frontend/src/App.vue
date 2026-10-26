<script setup lang="ts">
import {onMounted,ref} from 'vue';
const status=ref('正在检查本机后端…');
onMounted(async()=>{try{const r=await fetch('/api/health');const body=await r.json();status.value=body.status==='UP'?'主后端已就绪':'后端需要检查';}catch{status.value='请先运行本机启动脚本';}});
</script>
<template><main class="connections-view" style="max-width:900px;margin:10vh auto;padding:40px"><div class="eyebrow">旅游宝 · 阶段1</div><h1>下一段旅行，从这里开始。</h1><p>{{status}}</p><p>已建立Vue、Spring Boot、独立MCP与启动脚本。下一阶段接入模型对话。</p></main></template>
