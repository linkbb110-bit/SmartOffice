<template>
  <PageHeading title="Outbox 监控" description="查看最近 100 条事务消息的投递状态"><ElButton :icon="Refresh" :loading="query.loading.value" @click="query.refresh">刷新</ElButton></PageHeading>
  <section class="summary-band" aria-label="最近记录状态统计">
    <div v-for="state in ['PENDING', 'SENDING', 'SENT']" :key="state"><span>{{ labels[state] }}</span><strong>{{ query.data.value ? query.data.value.filter(row => row.status === state).length : '—' }}</strong></div>
  </section>
  <section class="surface">
    <div class="filter-bar"><ElSelect v-model="status" clearable placeholder="全部状态" aria-label="消息状态"><ElOption v-for="(label, key) in labels" :key="key" :label="label" :value="key" /></ElSelect><span class="muted">当前显示 {{ filtered.length }} 条</span></div>
    <LoadError :message="query.error.value" @retry="query.refresh" />
    <ElTable v-if="!query.error.value" v-loading="query.loading.value" :data="filtered" row-key="event_id" empty-text="暂无 Outbox 消息">
      <ElTableColumn label="事件 ID" min-width="200"><template #default="{ row }"><ElTooltip :content="row.event_id"><span tabindex="0">{{ shortEventId(row.event_id) }}</span></ElTooltip></template></ElTableColumn>
      <ElTableColumn prop="aggregate_id" label="预约 ID" width="100" />
      <ElTableColumn prop="event_type" label="类型" min-width="170" />
      <ElTableColumn label="状态" width="110"><template #default="{ row }"><ElTag :type="row.status === 'SENT' ? 'success' : 'warning'">{{ row.status }}</ElTag></template></ElTableColumn>
      <ElTableColumn prop="attempts" label="尝试次数" width="100" />
      <ElTableColumn label="下次可投递时间" min-width="180"><template #default="{ row }">{{ displayTime(row.available_at) }}</template></ElTableColumn>
      <ElTableColumn label="操作" fixed="right" width="80"><template #default="{ row }"><ElButton link type="primary" @click="selectedId = row.event_id">详情</ElButton></template></ElTableColumn>
    </ElTable>
    <p class="data-caption">统计范围仅为本次查询的最近 100 条，并非历史总量。SENT 表示消息已投递至 Broker，不代表消费完成；本页仅提供查询。</p>
  </section>
  <ElDrawer :model-value="!!selectedId" title="消息详情" size="min(560px, 100vw)" @close="selectedId = null">
    <LoadError :message="query.error.value" @retry="query.refresh" />
    <ElDescriptions v-if="selected" :column="1" border class="outbox-detail">
      <ElDescriptionsItem label="事件 ID">{{ selected.event_id }}</ElDescriptionsItem>
      <ElDescriptionsItem label="预约 ID">{{ selected.aggregate_id }}</ElDescriptionsItem>
      <ElDescriptionsItem label="事件类型">{{ selected.event_type }}</ElDescriptionsItem>
      <ElDescriptionsItem label="状态">{{ selected.status }}</ElDescriptionsItem>
      <ElDescriptionsItem label="尝试次数">{{ selected.attempts }}</ElDescriptionsItem>
      <ElDescriptionsItem label="下次可投递">{{ displayTime(selected.available_at) }}</ElDescriptionsItem>
      <ElDescriptionsItem label="租约截止">{{ displayTime(selected.lease_until) }}</ElDescriptionsItem>
      <ElDescriptionsItem label="Broker 消息 ID">{{ selected.broker_message_id || '—' }}</ElDescriptionsItem>
      <ElDescriptionsItem label="最近错误"><span class="outbox-error">{{ selected.last_error || '—' }}</span></ElDescriptionsItem>
    </ElDescriptions>
    <ElEmpty v-else-if="!query.error.value" description="该记录已不在最近查询结果中" />
  </ElDrawer>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import PageHeading from '@/components/PageHeading.vue';
import LoadError from '@/components/LoadError.vue';
import { getOutboxApi } from '@/api/outbox';
import { useQuery } from '@/composables/useQuery';
import { displayTime } from '@/utils/console';
import { shortEventId } from '@/utils/display';
const query = useQuery(getOutboxApi), status = ref<string | null>(null), selectedId = ref<string | null>(null);
const labels: Record<string, string> = { PENDING: '待投递', SENDING: '投递中', SENT: '已投递' };
const filtered = computed(() => (query.data.value || []).filter(row => !status.value || row.status === status.value));
const selected = computed(() => query.data.value?.find(row => row.event_id === selectedId.value));
onMounted(query.refresh);
</script>
<style scoped>
.outbox-detail { overflow-wrap: anywhere; }
.outbox-error { white-space: pre-wrap; }
</style>
