<template>
  <main class="room-page">
    <header class="topbar">
      <div class="title-block">
        <p>SmartOffice</p>
        <h1>会议室预定</h1>
      </div>
      <div class="topbar-actions">
        <ElButton @click="ordersVisible = true">我的预约</ElButton>
        <ElButton :icon="Refresh" :loading="loading" circle aria-label="刷新会议室" @click="fetchRooms" />
      </div>
    </header>

    <section v-loading="loading" class="room-grid" element-loading-text="加载会议室中">
      <ElEmpty v-if="!loading && rooms.length === 0" description="暂无会议室数据" />

      <TransitionGroup v-else name="room-card" tag="div" class="room-grid-inner">
        <ElCard
          v-for="room in rooms"
          :key="room.id"
          class="room-card"
          shadow="never"
          body-class="room-card-body"
        >
          <div class="room-card-header">
            <div class="room-icon">
              <Monitor :size="24" />
            </div>
            <ElTag :type="room.status === 1 ? 'success' : 'info'" effect="light">
              {{ room.status === 1 ? "可预定" : "维护中" }}
            </ElTag>
          </div>

          <h2>{{ room.roomName }}</h2>
          <div class="capacity">
            <ElIcon :size="18" aria-hidden="true"><UserFilled /></ElIcon>
            <span>容纳 {{ room.capacity }} 人</span>
          </div>

          <div class="equipment">
            <ElTag
              v-for="item in normalizeEquipment(room.equipment)"
              :key="`${room.id}-${item}`"
              round
            >
              {{ item }}
            </ElTag>
            <ElTag v-if="normalizeEquipment(room.equipment).length === 0" type="info" round>
              暂无设备
            </ElTag>
          </div>

          <template #footer>
            <ElButton
              type="primary"
              :icon="Calendar"
              :disabled="room.status !== 1"
              @click="openBookModal(room)"
            >
              立即预定
            </ElButton>
          </template>
        </ElCard>
      </TransitionGroup>
    </section>

    <BookModal v-model="bookVisible" :room="selectedRoom" @success="fetchRooms" />
    <MyOrders v-model="ordersVisible" :rooms="rooms" @canceled="fetchRooms" />
  </main>
</template>

<script setup lang="ts">
import { onMounted, ref } from "vue";
import { Calendar, Monitor, Refresh, UserFilled } from "@element-plus/icons-vue";

import { getRoomListApi } from "@/api/rooms";
import BookModal from "@/components/BookModal.vue";
import MyOrders from "@/components/MyOrders.vue";
import type { Room } from "@/types/room";

const loading = ref(false);
const rooms = ref<Room[]>([]);
const bookVisible = ref(false);
const ordersVisible = ref(false);
const selectedRoom = ref<Room | null>(null);

function normalizeEquipment(equipment: Room["equipment"]) {
  if (Array.isArray(equipment)) {
    return equipment.filter(Boolean);
  }

  if (!equipment) {
    return [];
  }

  try {
    const parsed = JSON.parse(equipment);
    if (Array.isArray(parsed)) {
      return parsed.filter(Boolean);
    }
  } catch {
    // The backend may return comma-separated text for legacy records.
  }

  return equipment
    .split(/[,，、]/)
    .map((item) => item.trim())
    .filter(Boolean);
}

async function fetchRooms() {
  loading.value = true;
  try {
    rooms.value = await getRoomListApi();
  } catch {
    // The request interceptor displays the error; users can refresh to retry.
  } finally {
    loading.value = false;
  }
}

function openBookModal(room: Room) {
  selectedRoom.value = room;
  bookVisible.value = true;
}

onMounted(fetchRooms);
</script>

<style scoped>
.room-page {
  min-height: 100vh;
  padding: 32px;
  background:
    linear-gradient(180deg, rgba(232, 241, 255, 0.9), rgba(245, 247, 251, 0.86) 280px),
    #f5f7fb;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  max-width: 1180px;
  margin: 0 auto 24px;
}

.topbar-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.topbar-actions :deep(.el-button + .el-button) { margin-left: 0; }

.title-block p {
  margin: 0 0 6px;
  color: #2563eb;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0;
}

.title-block h1 {
  margin: 0;
  color: #111827;
  font-size: 30px;
  line-height: 1.25;
  letter-spacing: 0;
}

.room-grid {
  max-width: 1180px;
  min-height: 360px;
  margin: 0 auto;
}

.room-grid-inner {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 20px;
}

.room-card {
  overflow: hidden;
  border: 1px solid rgba(148, 163, 184, 0.18);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 16px 38px rgba(15, 23, 42, 0.08);
  transition:
    transform 180ms ease,
    box-shadow 180ms ease,
    border-color 180ms ease;
}

.room-card:hover {
  border-color: rgba(37, 99, 235, 0.28);
  box-shadow: 0 20px 48px rgba(15, 23, 42, 0.12);
  transform: translateY(-3px);
}

:deep(.room-card-body) {
  min-height: 220px;
  padding: 22px;
}

.room-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
}

.room-icon {
  display: grid;
  width: 48px;
  height: 48px;
  place-items: center;
  border-radius: 8px;
  color: #1666d8;
  background: #eaf2ff;
}

.room-card h2 {
  overflow-wrap: anywhere;
  min-height: 54px;
  margin: 0 0 12px;
  color: #111827;
  font-size: 20px;
  line-height: 1.35;
  letter-spacing: 0;
}

.capacity {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 18px;
  color: #4b5563;
  font-size: 14px;
}

.equipment {
  display: flex;
  min-height: 64px;
  flex-wrap: wrap;
  align-content: flex-start;
  gap: 8px;
}

:deep(.el-card__footer) {
  padding: 16px 22px 20px;
  border-top: 0;
}

:deep(.el-card__footer .el-button) {
  width: 100%;
}

.room-card-enter-active,
.room-card-leave-active {
  transition:
    opacity 220ms ease,
    transform 220ms ease;
}

.room-card-enter-from,
.room-card-leave-to {
  opacity: 0;
  transform: translateY(10px);
}

@media (max-width: 640px) {
  .room-page {
    padding: 24px 16px;
  }

  .topbar {
    align-items: flex-start;
  }

  .title-block h1 {
    font-size: 26px;
  }
}
</style>
