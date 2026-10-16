<script setup lang="ts">
defineProps<{ started: boolean; selectedCount: number }>()
const emit = defineEmits<{ (e: 'start'): void; (e: 'restart'): void }>()
</script>

<template>
  <div class="action-bar">
    <div class="status">单局 · 两副牌 108 张 · 固定打 2</div>
    <div class="buttons">
      <button v-if="!started" type="button" @click="emit('start')">开始一局</button>
      <button v-else type="button" @click="emit('restart')">再来一局</button>
      <span class="sel-count">已选 {{ selectedCount }} 张</span>
    </div>
  </div>
</template>

<style scoped>
.action-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 16px;
  background: rgba(0, 0, 0, 0.25);
  flex-wrap: wrap;
}
.status {
  font-size: 14px;
  letter-spacing: 0.5px;
}
.buttons {
  display: flex;
  align-items: center;
  gap: 12px;
}
button {
  padding: 6px 16px;
  border-radius: 6px;
  border: 1px solid #f59e0b;
  background: #f59e0b;
  color: #1f2937;
  font-weight: 700;
  cursor: pointer;
}
.sel-count {
  font-size: 13px;
  opacity: 0.85;
}
</style>
