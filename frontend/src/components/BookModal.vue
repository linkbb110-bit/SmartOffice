<template>
  <ElDialog
    v-model="visible"
    class="book-dialog"
    width="min(480px, calc(100vw - 32px))"
    :title="room ? `预定 ${room.roomName}` : '预定会议室'"
    destroy-on-close
    :close-on-click-modal="!submitting"
    :close-on-press-escape="!submitting"
    :show-close="!submitting"
  >
    <ElForm
      ref="formRef"
      :model="form"
      :rules="rules"
      :disabled="submitting"
      label-position="top"
      @keyup.enter="handleSubmit"
    >
      <ElFormItem label="会议主题" prop="topic">
        <ElInput v-model.trim="form.topic" maxlength="32" placeholder="请输入会议主题" />
      </ElFormItem>

      <ElFormItem label="预定日期" prop="date">
        <ElDatePicker
          v-model="form.date"
          class="field"
          type="date"
          placeholder="选择日期"
          value-format="YYYY-MM-DD"
          :disabled-date="disabledPastDate"
        />
      </ElFormItem>

      <div class="time-row">
        <ElFormItem label="开始时间" prop="startTime">
          <ElTimePicker
            v-model="form.startTime"
            class="field"
            format="HH:mm"
            value-format="HH:mm:ss"
            placeholder="开始时间"
          />
        </ElFormItem>
        <ElFormItem label="结束时间" prop="endTime">
          <ElTimePicker
            v-model="form.endTime"
            class="field"
            format="HH:mm"
            value-format="HH:mm:ss"
            placeholder="结束时间"
          />
        </ElFormItem>
      </div>
    </ElForm>

    <template #footer>
      <ElButton :disabled="submitting" @click="visible = false">取消</ElButton>
      <ElButton type="primary" :loading="submitting" @click="handleSubmit">
        提交预定
      </ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { ElMessage, type FormInstance, type FormRules } from "element-plus";

import { bookRoomApi } from "@/api/orders";
import type { Room } from "@/types/room";
import { createBookingSubmission } from "@/utils/bookingSubmission";

interface BookForm {
  topic: string;
  date: string;
  startTime: string;
  endTime: string;
}

const props = defineProps<{
  modelValue: boolean;
  room: Room | null;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  success: [];
}>();

const formRef = ref<FormInstance>();
const submitting = ref(false);

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit("update:modelValue", value)
});

const form = reactive<BookForm>({
  topic: "内部会议",
  date: "",
  startTime: "",
  endTime: ""
});

const rules: FormRules<BookForm> = {
  topic: [{ required: true, message: "请输入会议主题", trigger: "blur" }],
  date: [{ required: true, message: "请选择预定日期", trigger: "change" }],
  startTime: [{ required: true, message: "请选择开始时间", trigger: "change" }],
  endTime: [{ required: true, message: "请选择结束时间", trigger: "change" }]
};

function disabledPastDate(date: Date) {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return date.getTime() < today.getTime();
}

function buildDateTime(date: string, time: string) {
  return `${date}T${time}`;
}

async function validateBooking() {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid || !props.room) {
    return null;
  }

  const startTime = buildDateTime(form.date, form.startTime);
  const endTime = buildDateTime(form.date, form.endTime);

  if (new Date(startTime).getTime() >= new Date(endTime).getTime()) {
    ElMessage.warning("结束时间需晚于开始时间");
    return null;
  }
  return { roomId: props.room.id, topic: form.topic, startTime, endTime };
}

const submission = createBookingSubmission({
  validate: validateBooking,
  send: bookRoomApi,
  // getRandomValues also works for local HTTP demos opened through a LAN address.
  newRequestId: () => Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, "0")).join(""),
  onPending: (value) => { submitting.value = value; }
});

watch(form, () => submission.resetIntent(), { flush: "sync" });
watch(() => props.room?.id, () => submission.resetIntent(), { flush: "sync" });
watch(() => props.modelValue, (open) => {
  if (!open) return;
  submission.resetIntent();
  Object.assign(form, { topic: "内部会议", date: "", startTime: "", endTime: "" });
  formRef.value?.clearValidate();
});

async function handleSubmit() {
  try {
    const result = await submission.submit();
    if (result.status !== "success") return;
    ElMessage.success("预定成功");
    visible.value = false;
    emit("success");
  } catch {
    // The request interceptor displays the error. Keep this intent for a safe retry.
  }
}
</script>

<style scoped>
.field {
  width: 100%;
}

.time-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

@media (max-width: 560px) {
  .time-row {
    grid-template-columns: 1fr;
    gap: 0;
  }
}
</style>
