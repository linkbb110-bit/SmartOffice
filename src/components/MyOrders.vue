<template>
  <ElDialog v-model="visible" title="我的预约" width="min(920px, calc(100vw - 32px))">
    <div class="orders-toolbar">
      <span>取消预约后，该时间段可再次预定。</span>
      <ElButton :loading="loading" :disabled="canceling.size > 0" @click="fetchOrders">刷新</ElButton>
    </div>
    <ElAlert v-if="loadFailed" title="预约加载失败，请点击刷新重试" type="error" :closable="false" show-icon />
    <ElTable v-loading="loading" :data="orders" empty-text="暂无预约" row-key="id">
      <ElTableColumn prop="topic" label="会议主题" min-width="150" />
      <ElTableColumn label="会议室" min-width="140">
        <template #default="{ row }">{{ roomName(row.roomId) }}</template>
      </ElTableColumn>
      <ElTableColumn label="时间" min-width="200">
        <template #default="{ row }">
          <div>{{ displayTime(row.startTime) }}</div>
          <div>至 {{ displayTime(row.endTime) }}</div>
        </template>
      </ElTableColumn>
      <ElTableColumn label="状态" width="100">
        <template #default="{ row }">
          <ElTag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '已预约' : '已取消' }}</ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="操作" width="115">
        <template #default="{ row }">
          <ElButton v-if="row.status === 1" type="danger" plain size="small" :loading="canceling.has(row.id)" @click="handleCancel(row)">取消预约</ElButton>
          <span v-else>—</span>
        </template>
      </ElTableColumn>
    </ElTable>
  </ElDialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { cancelOrderApi, getMyOrdersApi } from "@/api/orders";
import { createOrderCancellation } from "@/utils/orderCancellation";
import type { Order, Room } from "@/types/room";

const props = defineProps<{ modelValue: boolean; rooms: Room[] }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean]; canceled: [] }>();
const visible = computed({ get: () => props.modelValue, set: value => emit("update:modelValue", value) });
const orders = ref<Order[]>([]);
const loading = ref(false);
const loadFailed = ref(false);
const canceling = reactive(new Set<number>());
const cancelOrder = createOrderCancellation(cancelOrderApi, (id, pending) => {
  if (pending) canceling.add(id);
  else canceling.delete(id);
});

function roomName(id: number) {
  return props.rooms.find(room => room.id === id)?.roomName || `会议室 #${id}`;
}

function displayTime(value: string) {
  return value.replace("T", " ").slice(0, 16);
}

async function fetchOrders() {
  if (loading.value || canceling.size > 0) return;
  loading.value = true;
  loadFailed.value = false;
  try {
    orders.value = await getMyOrdersApi();
  } catch {
    orders.value = [];
    loadFailed.value = true;
  } finally {
    loading.value = false;
  }
}

async function handleCancel(order: Order) {
  try {
    if (!await cancelOrder(order)) return;
    ElMessage.success("预约已取消");
    emit("canceled");
  } catch {
    // Request errors are displayed centrally; the booked row remains retryable.
  }
}

watch(() => props.modelValue, open => { if (open) void fetchOrders(); });
</script>

<style scoped>
.orders-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; color: #667085; font-size: 14px; }
</style>
