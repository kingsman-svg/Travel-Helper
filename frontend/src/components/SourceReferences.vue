<script setup lang="ts">
defineProps<{ title?: string; citations: { title: string; sourceUrl: string; excerpt: string; city?: string; documentId?: string | null; chunkId?: string | null; verifiedAt?: string | null; origin?: string | null; score?: number }[] }>();
</script>
<template>
  <section v-if="citations.length" class="source-references" :aria-label="title || '资料引用'">
    <div class="source-heading">{{ title || '资料引用' }}<span>{{ citations.length }} 个片段</span></div>
    <details v-for="(source,index) in citations" :key="source.chunkId || source.title" class="source-reference">
      <summary><span class="source-number">{{ index+1 }}</span><span>{{ source.title }}<small>{{ source.city ? source.city+' · ' : '' }}{{ source.verifiedAt ? '核对日 '+source.verifiedAt : '未记录核对日' }}</small></span><span class="source-arrow">⌄</span></summary>
      <div class="source-content"><p class="source-origin">{{ source.origin === 'CURATED_EXAMPLE' ? '示例资料：已人工核对官方来源' : '用户导入：来源与核对日期由上传者提供' }}</p><p class="source-excerpt">{{ source.excerpt }}</p><p v-if="source.chunkId" class="source-identifiers">片段 {{ source.chunkId }}<span v-if="source.score !== undefined"> · 相似度 {{ source.score.toFixed(3) }}</span></p><a :href="source.sourceUrl" target="_blank" rel="noopener noreferrer">打开来源网页 ↗</a></div>
    </details>
    <p class="source-footnote">引用对应导入时的资料片段；临时开放、价格与现场情况请另行确认。</p>
  </section>
</template>
