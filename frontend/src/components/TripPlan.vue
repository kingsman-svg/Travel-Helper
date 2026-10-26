<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import SourceReferences from './SourceReferences.vue';
import { categoryLabels, type Itinerary, type TripDraft } from '../types/api';
const props = defineProps<{ draft: TripDraft; busy: boolean }>();
const emit = defineEmits<{ edit: [itinerary: Itinerary] }>();
const editing = ref(false);
const edited = ref<Itinerary>(JSON.parse(JSON.stringify(props.draft.itinerary)));
watch(() => props.draft, value => { edited.value = JSON.parse(JSON.stringify(value.itinerary)); editing.value = false; });
const plan = computed(() => editing.value ? edited.value : props.draft.itinerary);
const sourceRefs = computed(() => plan.value.sources.map(source => ({ ...source, sourceUrl: source.url, excerpt: source.note })));
function money(value: number) { return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(value); }
function submit() { emit('edit', JSON.parse(JSON.stringify(edited.value))); }
</script>
<template>
  <form class="plan-grid" @submit.prevent="submit">
    <div class="daily-plans">
      <div class="plan-intro"><div class="eyebrow"><span></span>{{ draft.request.destination }} · {{ draft.request.people }} 人 · {{ plan.days.length }} 天</div>
        <input v-if="editing" v-model="edited.title" required maxlength="100" aria-label="行程标题"><h2 v-else>{{ plan.title }}</h2>
        <textarea v-if="editing" v-model="edited.summary" required maxlength="1500" rows="3" aria-label="行程简介"></textarea><p v-else>{{ plan.summary }}</p>
        <div class="plan-edit-actions"><template v-if="editing"><button class="primary-button" :disabled="busy" type="submit">应用编辑并校验</button><button class="secondary-button" type="button" :disabled="busy" @click="editing = false; edited = JSON.parse(JSON.stringify(draft.itinerary))">取消编辑</button></template><button v-else class="text-link" :disabled="busy" type="button" @click="editing = true">编辑安排与费用 ↗</button></div>
      </div>
      <details v-for="(day, index) in plan.days" :key="day.date" class="day-card" :open="editing || index === 0">
        <summary><header><span class="day-number">D{{ index+1 }}</span><div><small>{{ day.date }} · 估计步行 {{ draft.schedule.walkingMinutesByDate[day.date] }} 分钟</small><input v-if="editing" v-model="day.theme" required maxlength="100" :aria-label="`第${index+1}天主题`"><h3 v-else>{{ day.theme }}</h3></div><span class="day-chevron">⌄</span></header></summary>
        <div v-for="(activity, i) in day.activities" :key="i" class="activity-row">
          <div class="activity-time"><template v-if="editing"><input v-model="activity.startTime" type="time" required aria-label="活动开始时间"><input v-model="activity.endTime" type="time" required aria-label="活动结束时间"></template><template v-else>{{ activity.startTime }}<small>{{ activity.endTime }}</small></template></div>
          <div class="activity-detail">
            <template v-if="editing"><input v-model="activity.title" required maxlength="100" aria-label="活动名称"><input v-model="activity.location" required maxlength="150" aria-label="地点"><input v-model="activity.transport" required maxlength="300" aria-label="交通安排"><label>预计步行（分钟）<input v-model.number="activity.walkingMinutes" type="number" min="0" max="720" required></label></template>
            <template v-else><h4>{{ activity.title }}</h4><p class="location">{{ activity.location }}</p><p>{{ activity.transport }} · 步行约 {{ activity.walkingMinutes }} 分钟</p></template>
            <div v-for="(cost, c) in activity.costs" :key="c" class="cost-line"><span>{{ categoryLabels[cost.category] }}</span><template v-if="editing"><input v-model.number="cost.amount" type="number" step="0.01" min="0" max="10000000" required aria-label="活动费用"><input v-model="cost.note" required maxlength="200" aria-label="费用说明"></template><template v-else><strong>¥{{ money(cost.amount) }}</strong><small>{{ cost.note }}</small></template></div>
          </div>
        </div>
      </details>
      <section class="plan-notes"><h3>住宿与共同费用</h3><div v-for="(cost, i) in plan.extraCosts" :key="i" class="cost-line"><span>{{ categoryLabels[cost.category] }}</span><template v-if="editing"><input v-model.number="cost.amount" type="number" step="0.01" min="0" max="10000000" required aria-label="共同费用"><input v-model="cost.note" required maxlength="200" aria-label="共同费用说明"></template><template v-else><strong>¥{{ money(cost.amount) }}</strong><small>{{ cost.note }}</small></template></div><p v-if="!plan.extraCosts.length">暂无额外费用估算。</p></section>
      <section class="plan-notes"><h3>出发前，再确认一下</h3><ul><li v-for="note in plan.pendingConfirmations" :key="note">{{ note }}</li></ul><SourceReferences v-if="sourceRefs.length" :citations="sourceRefs"/><p v-else>此行程暂无知识库引用；安排、费用与开放情况请进一步核实。</p></section>
    </div>
    <aside class="budget-panel"><div class="budget-card"><div class="eyebrow">旅行预算 / CNY</div><div class="budget-total">¥{{ money(draft.budget.total) }}</div><p>预算包含项的估算总额 · 全体 {{ draft.request.people }} 人</p><div class="budget-row" v-for="(label, key) in categoryLabels" :key="key"><span>{{ label }}<small v-if="!draft.request.budgetIncludes.includes(key)">（预算外）</small></span><span>¥{{ money(draft.budget.byCategory[key]) }}</span></div><div class="budget-row budget-divider"><span>总预算</span><strong>¥{{ money(draft.request.totalBudget) }}</strong></div><div class="budget-row" :class="{ 'form-error': draft.budget.overBudget }"><span>{{ draft.budget.overBudget ? '超出预算' : '预计余量' }}</span><strong>¥{{ money(Math.abs(draft.budget.remaining)) }}</strong></div><p v-if="draft.budget.overBudget" class="form-error">建议继续调整费用后再出发。</p><p>全部费用估算 ¥{{ money(draft.budget.allEstimatedCosts) }}。票价、步行及交通安排尚需核实。</p><p v-if="editing">编辑后点击“应用编辑并校验”，重新计算预算与步行时间。</p></div>
      <details class="tool-panel" open><summary>本次行程检查 <span>✓ 2 项</span></summary><div v-for="tool in draft.toolExecutions" :key="tool.name"><strong>{{ tool.name }}</strong><p>{{ tool.summary }}</p><small>{{ tool.trigger }} · {{ tool.durationMs }} ms</small></div></details>
    </aside>
  </form>
</template>
