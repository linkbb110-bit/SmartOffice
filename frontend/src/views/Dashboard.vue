<template>
  <PageHeading title="工作台" description="会议与空间，一目了然"><ElButton type="primary" @click="$router.push('/rooms')">预约会议室</ElButton><ElButton :loading="busy" :icon="Refresh" @click="refresh">刷新</ElButton></PageHeading>
  <section class="summary-band" aria-label="个人办公概览"><div><span>会议室数量</span><strong>{{ summary.rooms ?? '—' }}</strong></div><div><span>我的待开始预约</span><strong>{{ summary.upcoming ?? '—' }}</strong></div><div><span>今日预约</span><strong>{{ summary.today ?? '—' }}</strong></div></section>
  <LoadError :message="roomQuery.error.value" @retry="roomQuery.refresh" />
  <div class="dashboard-columns">
    <section class="surface" v-loading="orderQuery.loading.value"><div class="section-heading"><h2>今日预约</h2><RouterLink to="/bookings">查看全部</RouterLink></div><LoadError :message="orderQuery.error.value" @retry="orderQuery.refresh" /><BookingTable v-if="!orderQuery.error.value" :orders="todayOrders" :rooms="roomQuery.data.value || []" /></section>
    <section class="surface" v-loading="noticeQuery.loading.value"><div class="section-heading"><h2>最近通知</h2><RouterLink to="/notifications">查看全部</RouterLink></div><LoadError :message="noticeQuery.error.value" @retry="noticeQuery.refresh" /><ul v-if="noticeQuery.data.value?.length" class="notice-list"><li v-for="notice in noticeQuery.data.value.slice(0, 5)" :key="notice.id"><p>{{ notice.content }}</p><small>{{ displayTime(notice.created_at) }}</small></li></ul><ElEmpty v-else-if="!noticeQuery.error.value && !noticeQuery.loading.value" description="暂无通知" /></section>
  </div>
  <p class="data-caption">数据来自当前账户的真实查询结果。通知仅展示最近 100 条中的最新记录。</p>
</template>
<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import PageHeading from '@/components/PageHeading.vue';
import BookingTable from '@/components/BookingTable.vue';
import LoadError from '@/components/LoadError.vue';
import { getRoomListApi } from '@/api/rooms';
import { getMyOrdersApi } from '@/api/orders';
import { getMyNotificationsApi } from '@/api/notifications';
import { useQuery } from '@/composables/useQuery';
import { dashboardSummary, displayTime, isToday } from '@/utils/console';
const roomQuery = useQuery(getRoomListApi), orderQuery = useQuery(getMyOrdersApi), noticeQuery = useQuery(getMyNotificationsApi);
const summary = computed(() => dashboardSummary(roomQuery.data.value, orderQuery.data.value));
const todayOrders = computed(() => (orderQuery.data.value || []).filter(x => isToday(x)).sort((a, b) => a.startTime.localeCompare(b.startTime)));
const busy = computed(() => roomQuery.loading.value || orderQuery.loading.value || noticeQuery.loading.value);
async function refresh() { await Promise.all([roomQuery.refresh(), orderQuery.refresh(), noticeQuery.refresh()]); }
onMounted(refresh);
</script>
