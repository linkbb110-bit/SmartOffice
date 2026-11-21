<template>
  <PageHeading title="通知中心" description="查看当前账户最近 100 条预约通知"><ElButton :icon="Refresh" :loading="loading" @click="refresh">刷新</ElButton></PageHeading>
  <section class="surface" v-loading="loading"><LoadError :message="error" @retry="refresh" /><ElTable v-if="!error" :data="data || []" empty-text="暂无通知" row-key="id"><ElTableColumn prop="content" label="通知内容" min-width="260" /><ElTableColumn prop="booking_id" label="订单 ID" width="120" /><ElTableColumn label="通知时间" min-width="190"><template #default="{ row }">{{ displayTime(row.created_at) }}</template></ElTableColumn></ElTable><p class="data-caption">通知在消息消费成功后生成；此列表不提供已读状态。</p></section>
</template>
<script setup lang="ts">
import { onMounted } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import PageHeading from '@/components/PageHeading.vue';
import LoadError from '@/components/LoadError.vue';
import { getMyNotificationsApi } from '@/api/notifications';
import { useQuery } from '@/composables/useQuery';
import { displayTime } from '@/utils/console';
const { data, loading, error, refresh } = useQuery(getMyNotificationsApi);
onMounted(refresh);
</script>
