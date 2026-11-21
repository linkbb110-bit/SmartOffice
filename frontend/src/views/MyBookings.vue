<template>
  <PageHeading title="我的预约" description="查看会议安排，管理自己的预约"><ElButton :icon="Refresh" :loading="orders.loading.value" :disabled="pending.size > 0" @click="refresh">刷新</ElButton><ElButton type="primary" @click="$router.push('/rooms')">预约会议室</ElButton></PageHeading>
  <section class="surface"><div class="filter-bar"><ElInput v-model="keyword" clearable placeholder="搜索会议主题或预约编号" aria-label="搜索预约" /><ElSelect v-model="status" clearable placeholder="全部状态" aria-label="预约状态"><ElOption label="已预约" :value="1" /><ElOption label="已取消" :value="2" /></ElSelect><span class="muted">共 {{ filtered.length }} 条</span></div>
    <LoadError :message="orders.error.value" @retry="refresh" /><LoadError :message="rooms.error.value" @retry="rooms.refresh" />
    <BookingTable v-if="!orders.error.value" v-loading="orders.loading.value" :orders="paged" :rooms="rooms.data.value || []" :pending="pending" allow-cancel @cancel="cancel" />
    <ElPagination v-model:current-page="page" :total="filtered.length" :page-size="10" layout="prev, pager, next" class="pagination" />
  </section>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import PageHeading from '@/components/PageHeading.vue';
import BookingTable from '@/components/BookingTable.vue';
import LoadError from '@/components/LoadError.vue';
import { getMyOrdersApi, cancelOrderApi } from '@/api/orders';
import { getRoomListApi } from '@/api/rooms';
import { useQuery } from '@/composables/useQuery';
import { filterOrders } from '@/utils/console';
import { createOrderCancellation } from '@/utils/orderCancellation';
import type { Order } from '@/types/room';
const orders = useQuery(getMyOrdersApi), rooms = useQuery(getRoomListApi);
const keyword = ref(''), status = ref<number | null>(null), page = ref(1), pending = reactive(new Set<number>());
const filtered = computed(() => filterOrders(orders.data.value || [], keyword.value, status.value ?? null));
const paged = computed(() => filtered.value.slice((page.value - 1) * 10, page.value * 10));
watch([keyword, status], () => { page.value = 1; });
watch(() => filtered.value.length, count => { page.value = Math.min(page.value, Math.max(1, Math.ceil(count / 10))); });
const cancelOrder = createOrderCancellation(cancelOrderApi, (id, active) => { if (active) pending.add(id); else pending.delete(id); });
async function cancel(order: Order) {
  if (pending.has(order.id)) return;
  try { await ElMessageBox.confirm(`确认取消「${order.topic}」？`, '取消预约', { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '保留预约' }); } catch { return; }
  try { if (await cancelOrder(order)) ElMessage.success('预约已取消'); } catch { /* 保留原状态，允许重试。 */ }
}
async function refresh() { if (!pending.size) await Promise.all([orders.refresh(), rooms.refresh()]); }
onMounted(refresh);
</script>
