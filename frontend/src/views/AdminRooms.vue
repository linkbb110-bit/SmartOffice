<template>
  <PageHeading title="会议室管理" description="维护会议空间、容量与设备信息">
    <ElButton :icon="Refresh" :loading="rooms.loading.value" :disabled="busy" @click="rooms.refresh">刷新</ElButton>
    <ElButton type="primary" :icon="Plus" :disabled="busy" @click="openEditor()">新增会议室</ElButton>
  </PageHeading>
  <section class="surface">
    <div class="filter-bar">
      <ElInput v-model="keyword" placeholder="搜索会议室名称" aria-label="会议室名称" clearable />
      <ElSelect v-model="status" placeholder="全部状态" aria-label="会议室状态" clearable>
        <ElOption label="可预约" :value="1" /><ElOption label="维护中" :value="0" />
      </ElSelect>
      <span class="muted">共 {{ filtered.length }} 间</span>
    </div>
    <LoadError :message="rooms.error.value" @retry="rooms.refresh" />
    <ElTable v-if="!rooms.error.value" v-loading="rooms.loading.value" :data="filtered" row-key="id" empty-text="暂无会议室">
      <ElTableColumn prop="id" label="ID" width="80" />
      <ElTableColumn prop="roomName" label="会议室" min-width="170" show-overflow-tooltip />
      <ElTableColumn prop="capacity" label="容量" width="90"><template #default="{ row }">{{ row.capacity }} 人</template></ElTableColumn>
      <ElTableColumn label="设备" min-width="220"><template #default="{ row }">{{ normalizeEquipment(row.equipment).join('、') || '—' }}</template></ElTableColumn>
      <ElTableColumn label="状态" width="110"><template #default="{ row }"><ElTag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '可预约' : '维护中' }}</ElTag></template></ElTableColumn>
      <ElTableColumn label="操作" fixed="right" width="135"><template #default="{ row }">
        <ElButton link type="primary" :disabled="busy || rooms.loading.value" @click="openEditor(row)">编辑</ElButton>
        <ElButton link type="danger" :disabled="busy || rooms.loading.value" @click="remove(row)">删除</ElButton>
      </template></ElTableColumn>
    </ElTable>
    <p class="data-caption">存在未结束预约的会议室不能删除，最终结果以服务端校验为准。</p>
  </section>
  <ElDialog v-model="editorOpen" :title="editingId == null ? '新增会议室' : '编辑会议室'" width="min(520px, calc(100vw - 32px))" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving" destroy-on-close>
    <ElAlert v-if="formError" :title="formError" type="error" :closable="false" show-icon class="load-error" />
    <ElForm :model="form" label-position="top" :disabled="saving" @submit.prevent="save">
      <ElFormItem label="会议室名称" required><ElInput v-model="form.roomName" maxlength="100" placeholder="例如：研发讨论室" /></ElFormItem>
      <ElFormItem label="容量（1～30 人）" required><ElInputNumber v-model="form.capacity" :min="1" :max="30" :precision="0" aria-label="容量" /></ElFormItem>
      <ElFormItem label="设备（每行一项）"><ElInput v-model="equipmentText" type="textarea" :rows="3" placeholder="白板&#10;投影仪" /></ElFormItem>
      <ElFormItem label="状态" required><ElRadioGroup v-model="form.status"><ElRadio :value="1">可预约</ElRadio><ElRadio :value="0">维护中</ElRadio></ElRadioGroup></ElFormItem>
    </ElForm>
    <template #footer><ElButton :disabled="saving" @click="editorOpen = false">取消</ElButton><ElButton type="primary" :loading="saving" @click="save">保存</ElButton></template>
  </ElDialog>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh } from '@element-plus/icons-vue';
import PageHeading from '@/components/PageHeading.vue';
import LoadError from '@/components/LoadError.vue';
import { getRoomListApi, createRoomApi, updateRoomApi, deleteRoomApi } from '@/api/rooms';
import { useQuery } from '@/composables/useQuery';
import { errorMessage, filterRooms, normalizeEquipment } from '@/utils/console';
import { prepareRoom } from '@/utils/admin';
import type { Room } from '@/types/room';
import type { RoomInput } from '@/types/console';

const rooms = useQuery(getRoomListApi);
const keyword = ref(''), status = ref<number | null>(null);
const filtered = computed(() => filterRooms(rooms.data.value || [], keyword.value, null, status.value ?? null));
const editorOpen = ref(false), editingId = ref<number | null>(null), saving = ref(false), deleting = ref(false);
const equipmentText = ref(''), formError = ref('');
const form = reactive<RoomInput>({ roomName: '', capacity: 8, equipment: [], status: 1 });
const busy = computed(() => saving.value || deleting.value);

function openEditor(room?: Room) {
  editingId.value = room?.id ?? null;
  Object.assign(form, { roomName: room?.roomName ?? '', capacity: room?.capacity ?? 8, status: room?.status ?? 1 });
  equipmentText.value = room ? normalizeEquipment(room.equipment).join('\n') : '';
  formError.value = ''; editorOpen.value = true;
}
async function save() {
  if (saving.value) return;
  formError.value = '';
  let input: RoomInput;
  try { input = prepareRoom({ ...form, equipment: equipmentText.value.split(/\r?\n/) }); }
  catch (error) { formError.value = errorMessage(error); return; }
  saving.value = true;
  try {
    if (editingId.value == null) await createRoomApi(input); else await updateRoomApi(editingId.value, input);
    editorOpen.value = false; ElMessage.success('会议室已保存'); await rooms.refresh();
  } catch (error) { formError.value = errorMessage(error); }
  finally { saving.value = false; }
}
async function remove(room: Room) {
  if (busy.value) return;
  deleting.value = true;
  try {
    try { await ElMessageBox.confirm(`确认删除「${room.roomName}」？此操作不可恢复。`, '删除会议室', { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '保留' }); }
    catch { return; }
    await deleteRoomApi(room.id); ElMessage.success('会议室已删除'); await rooms.refresh();
  } catch { /* 请求层显示业务拒绝，保留列表以便再次操作。 */ }
  finally { deleting.value = false; }
}
onMounted(rooms.refresh);
</script>
