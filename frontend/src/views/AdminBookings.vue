<template>
  <PageHeading title="全部预约" description="查看所有用户的预约，按需取消或导出完整列表">
    <ElButton :icon="Refresh" :loading="orders.loading.value" :disabled="pending.size > 0" @click="refresh">刷新</ElButton>
    <ElButton type="primary" :icon="Download" :loading="exporting" @click="exportCsv">导出全部 CSV</ElButton>
  </PageHeading>
  <section class="surface">
    <div class="filter-bar">
      <ElInput v-model="keyword" clearable placeholder="搜索会议主题或订单 ID" aria-label="搜索预约" />
      <ElInputNumber v-model="userId" :min="1" :precision="0" placeholder="用户 ID" aria-label="用户 ID" />
      <ElSelect v-model="status" clearable placeholder="全部状态" aria-label="预约状态"><ElOption label="已预约" :value="1" /><ElOption label="已取消" :value="2" /></ElSelect>
      <span class="muted">共 {{ filtered.length }} 条</span>
    </div>
    <LoadError :message="orders.error.value" @retry="refresh" /><LoadError :message="rooms.error.value" @retry="rooms.refresh" />
    <LoadError :message="exportError" @retry="exportCsv" />
    <BookingTable v-if="!orders.error.value" v-loading="orders.loading.value" :orders="paged" :rooms="rooms.data.value || []" :pending="pending" show-user allow-cancel @cancel="cancel" />
    <ElPagination v-model:current-page="page" :total="filtered.length" :page-size="10" layout="prev, pager, next" class="pagination" />
    <p class="data-caption">筛选仅影响当前列表；CSV 由服务端导出全部预约，不受当前筛选及分页影响。</p>
  </section>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Download, Refresh } from '@element-plus/icons-vue';
import PageHeading from '@/components/PageHeading.vue';
import BookingTable from '@/components/BookingTable.vue';
import LoadError from '@/components/LoadError.vue';
import { getAllOrdersApi, cancelOrderApi, exportOrdersApi } from '@/api/orders';
import { getRoomListApi } from '@/api/rooms';
import { useQuery } from '@/composables/useQuery';
import { errorMessage, filterOrders } from '@/utils/console';
import { requireCsv } from '@/utils/admin';
import { createOrderCancellation } from '@/utils/orderCancellation';
import { getToken } from '@/utils/auth';
import type { Order } from '@/types/room';

const orders = useQuery(getAllOrdersApi), rooms = useQuery(getRoomListApi);
const keyword = ref(''), status = ref<number | null>(null), userId = ref<number | null>(null), page = ref(1);
const pending = reactive(new Set<number>()), exporting = ref(false), exportError = ref('');
const filtered = computed(() => filterOrders(orders.data.value || [], keyword.value, status.value ?? null).filter(row => userId.value == null || row.userId === userId.value));
const paged = computed(() => filtered.value.slice((page.value - 1) * 10, page.value * 10));
watch([keyword, status, userId], () => { page.value = 1; });
watch(() => filtered.value.length, count => { page.value = Math.min(page.value, Math.max(1, Math.ceil(count / 10))); });
const cancelOrder = createOrderCancellation(cancelOrderApi, (id, active) => { if (active) pending.add(id); else pending.delete(id); });
async function cancel(order: Order) {
  if (pending.has(order.id)) return;
  try { await ElMessageBox.confirm(`以管理员身份取消「${order.topic}」（用户 #${order.userId}）？`, '取消预约', { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '保留预约' }); } catch { return; }
  try { if (await cancelOrder(order)) ElMessage.success('预约已取消'); } catch { /* 不提前变更状态，失败后可重试。 */ }
}
async function exportCsv() {
  if (exporting.value) return;
  exporting.value = true; exportError.value = '';
  const token = getToken();
  try {
    const blob = await requireCsv(await exportOrdersApi());
    if (getToken() !== token) return;
    const url = URL.createObjectURL(blob), link = document.createElement('a');
    link.href = url; link.download = 'bookings.csv'; document.body.append(link); link.click(); link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (error) { exportError.value = errorMessage(error); }
  finally { exporting.value = false; }
}
async function refresh() { if (!pending.size) await Promise.all([orders.refresh(), rooms.refresh()]); }
onMounted(refresh);
</script>
