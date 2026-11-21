<template>
  <section>
    <PageHeading title="会议室预约" description="选择适合的会议空间，安排下一场讨论"><ElButton :icon="Refresh" :loading="loading" @click="refresh">刷新</ElButton><ElButton @click="$router.push('/bookings')">我的预约</ElButton></PageHeading>
    <div class="filter-bar surface"><ElInput v-model="keyword" clearable placeholder="搜索会议室名称" aria-label="会议室名称" /><ElInputNumber v-model="capacity" :min="1" :max="30" placeholder="最少人数" aria-label="最少容量" /><ElSelect v-model="status" clearable placeholder="全部状态" aria-label="会议室状态"><ElOption label="可发起预约" :value="1" /><ElOption label="维护中" :value="0" /></ElSelect><span class="muted">共 {{ filtered.length }} 间</span></div>
    <LoadError :message="error" @retry="refresh" />
    <div v-loading="loading" class="room-grid-inner">
      <ElCard v-for="room in filtered" :key="room.id" class="room-card" shadow="never">
        <div class="room-card-header"><ElIcon :size="25" class="room-icon"><Monitor /></ElIcon><ElTag :type="room.status === 1 ? 'success' : 'info'">{{ room.status === 1 ? '可发起预约' : '维护中' }}</ElTag></div>
        <h2>{{ room.roomName }}</h2><p class="muted"><ElIcon><UserFilled /></ElIcon> 容纳 {{ room.capacity }} 人</p>
        <div class="equipment"><ElTag v-for="item in normalizeEquipment(room.equipment)" :key="item" type="info" effect="plain">{{ item }}</ElTag><span v-if="!normalizeEquipment(room.equipment).length" class="muted">暂无设备信息</span></div>
        <template #footer><ElButton type="primary" :disabled="room.status !== 1" :icon="Calendar" @click="openBooking(room)">立即预约</ElButton></template>
      </ElCard>
    </div>
    <ElEmpty v-if="!loading && !error && !filtered.length" description="暂无符合条件的会议室" />
    <p class="data-caption">可发起预约不代表指定时段空闲，时间冲突由提交时的后端校验决定。</p>
    <BookModal v-model="bookVisible" :room="selectedRoom" @success="refresh" />
  </section>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Calendar, Monitor, Refresh, UserFilled } from '@element-plus/icons-vue';
import PageHeading from '@/components/PageHeading.vue';
import LoadError from '@/components/LoadError.vue';
import BookModal from '@/components/BookModal.vue';
import { getRoomListApi } from '@/api/rooms';
import { useQuery } from '@/composables/useQuery';
import { filterRooms, normalizeEquipment } from '@/utils/console';
import type { Room } from '@/types/room';
const { data, loading, error, refresh } = useQuery(getRoomListApi);
const keyword = ref(''), capacity = ref<number | null>(null), status = ref<number | null>(null);
const bookVisible = ref(false), selectedRoom = ref<Room | null>(null);
const filtered = computed(() => filterRooms(data.value || [], keyword.value, capacity.value ?? null, status.value ?? null));
function openBooking(room: Room) { selectedRoom.value = room; bookVisible.value = true; }
onMounted(refresh);
</script>
<style scoped>
.room-grid-inner { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 20px; min-height: 120px; }
.room-card { border-radius: 8px; transition: border-color .18s; }
.room-card:hover { border-color: #b6cdf9; }
.room-card-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px; }
.room-icon { color: #2563eb; background: #eff5ff; width: 46px; height: 46px; border-radius: 8px; }
.room-card h2 { font-size: 18px; overflow-wrap: anywhere; }
.equipment { min-height: 52px; display: flex; flex-wrap: wrap; align-content: flex-start; gap: 6px; margin-top: 18px; }
.room-card :deep(.el-card__footer .el-button) { width: 100%; }
</style>
