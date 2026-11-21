<template>
  <ElTable :data="orders" row-key="id" empty-text="暂无预约" class="data-table">
    <ElTableColumn prop="id" label="订单 ID" width="100" />
    <ElTableColumn prop="topic" label="会议主题" min-width="170" show-overflow-tooltip />
    <ElTableColumn v-if="showUser" prop="userId" label="用户 ID" width="100" />
    <ElTableColumn label="会议室" min-width="130"><template #default="{ row }">{{ rooms.find(x => x.id === row.roomId)?.roomName || `会议室 #${row.roomId}` }}</template></ElTableColumn>
    <ElTableColumn label="时间" min-width="190"><template #default="{ row }"><div>{{ displayTime(row.startTime) }}</div><div class="muted">至 {{ displayTime(row.endTime) }}</div></template></ElTableColumn>
    <ElTableColumn label="状态" width="100"><template #default="{ row }"><ElTag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '已预约' : '已取消' }}</ElTag></template></ElTableColumn>
    <ElTableColumn v-if="allowCancel" label="操作" width="115" fixed="right"><template #default="{ row }"><ElButton v-if="row.status === 1" type="danger" link :loading="pending?.has(row.id)" @click="$emit('cancel', row)">取消预约</ElButton><span v-else class="muted">—</span></template></ElTableColumn>
  </ElTable>
</template>
<script setup lang="ts">
import type { Order, Room } from '@/types/room';
import { displayTime } from '@/utils/console';
withDefaults(defineProps<{ orders: Order[]; rooms: Room[]; showUser?: boolean; allowCancel?: boolean; pending?: Set<number> }>(), { showUser: false, allowCancel: false });
defineEmits<{ cancel: [order: Order] }>();
</script>
