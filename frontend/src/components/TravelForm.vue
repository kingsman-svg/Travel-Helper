<script setup lang="ts">
import { reactive } from 'vue';
import { categoryLabels, type ExpenseCategory, type TravelRequest } from '../types/api';
const props = defineProps<{ busy: boolean; initial?: TravelRequest }>();
const emit = defineEmits<{ generate: [request: TravelRequest] }>();
function date(offset: number) { const d = new Date(); d.setDate(d.getDate() + offset); return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`; }
const form = reactive<TravelRequest>(props.initial ? JSON.parse(JSON.stringify(props.initial)) : { origin: '上海', destination: '杭州', startDate: date(7), endDate: date(9), people: 2, totalBudget: 3000, budgetIncludes: ['ACCOMMODATION','FOOD','TRANSPORT','TICKETS','OTHER'], preferences: '喜欢美食和湖景，节奏轻松，少走路。' });
const categories = Object.keys(categoryLabels) as ExpenseCategory[];
const error = reactive({ message: '' });
function submit() {
  const days = (Date.parse(form.endDate)-Date.parse(form.startDate))/86400000+1;
  error.message = !form.budgetIncludes.length ? '请至少选择一个预算包含项。' : days < 1 || days > 14 ? '请选择连续 1–14 天的旅行日期。' : '';
  if (!error.message) emit('generate', structuredClone({ ...form, budgetIncludes: [...form.budgetIncludes] }));
}
</script>
<template>
  <form class="travel-form" @submit.prevent="submit">
    <fieldset :disabled="busy">
      <div class="form-grid">
        <label>出发地<input v-model.trim="form.origin" required maxlength="60" placeholder="如：上海"></label>
        <label>目的地<input v-model.trim="form.destination" required maxlength="60" placeholder="如：杭州"></label>
        <label>出发日期<input v-model="form.startDate" type="date" required></label>
        <label>结束日期<input v-model="form.endDate" type="date" required :min="form.startDate"></label>
        <label>同行人数<input v-model.number="form.people" type="number" min="1" max="20" required></label>
        <label>总预算（全体 / 元）<input v-model.number="form.totalBudget" type="number" min="0.01" max="10000000" step="0.01" required></label>
      </div>
      <div class="field-label">预算包含哪些费用？</div>
      <div class="category-options"><label v-for="key in categories" :key="key"><input v-model="form.budgetIncludes" type="checkbox" :value="key">{{ categoryLabels[key] }}</label></div>
      <label class="preference-field">旅行偏好<textarea v-model="form.preferences" maxlength="1000" rows="2" placeholder="喜欢什么、与谁同行、希望怎样的节奏…"></textarea></label>
      <p v-if="error.message" class="form-error" role="alert">{{ error.message }}</p>
      <div class="form-actions"><span>费用、步行时间均为估计，保存前可继续调整。</span><button class="primary-button" type="submit">{{ busy ? '正在规划…' : '生成我的行程 ↗' }}</button></div>
    </fieldset>
  </form>
</template>
