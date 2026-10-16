<script setup lang="ts">
defineProps<{
  started: boolean
  finished: boolean
  canAct: boolean // 已同步、无 pending/动画、且轮到真人
  canPass: boolean
  canRetry: boolean // 存在待确认命令（结果不明），可原样重试
  selectedCount: number
}>()
const emit = defineEmits<{
  (e: 'start'): void
  (e: 'restart'): void
  (e: 'play'): void
  (e: 'pass'): void
  (e: 'hint'): void
  (e: 'retry'): void
}>()
</script>

<template>
  <div class="action-bar">
    <div class="status">单局 · 两副牌 108 张 · 固定打 2</div>
    <div class="buttons">
      <button v-if="!started" type="button" @click="emit('start')">开始一局</button>
      <template v-else>
        <button type="button" :disabled="!canAct || selectedCount === 0" @click="emit('play')">出牌</button>
        <button type="button" :disabled="!canAct || !canPass" @click="emit('pass')">过牌</button>
        <button type="button" :disabled="!canAct" @click="emit('hint')">提示</button>
        <button v-if="canRetry" type="button" class="retry" @click="emit('retry')">重试</button>
        <button type="button" class="restart" @click="emit('restart')">再来一局</button>
        <span class="sel-count">已选 {{ selectedCount }} 张</span>
      </template>
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
  gap: 10px;
  flex-wrap: wrap;
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
button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
button.restart {
  background: transparent;
  color: #f59e0b;
}
button.retry {
  background: #dc2626;
  border-color: #dc2626;
  color: #fff;
}
.sel-count {
  font-size: 13px;
  opacity: 0.85;
}
</style>
