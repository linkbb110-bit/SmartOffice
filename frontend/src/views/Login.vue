<template>
  <main class="login-page">
    <section class="login-card">
      <div class="brand">
        <div class="brand-mark">
          <OfficeBuilding :size="30" />
        </div>
        <div>
          <h1>云智办</h1>
          <p>SmartOffice 会议室管理系统</p>
        </div>
      </div>

      <p v-if="registering" class="registration-hint">创建账户后即可登录并预约会议室</p>
      <ElForm
        ref="formRef"
        :model="form"
        :rules="rules"
        :disabled="loading"
        size="large"
        @keyup.enter="handleLogin"
      >
        <ElFormItem prop="username">
          <ElInput
            v-model.trim="form.username"
            :prefix-icon="User"
            clearable
            placeholder="用户名"
          />
        </ElFormItem>
        <ElFormItem prop="password">
          <ElInput
            v-model="form.password"
            :prefix-icon="Lock"
            clearable
            placeholder="密码"
            show-password
            type="password"
          />
        </ElFormItem>
        <ElButton
          class="login-button"
          type="primary"
          size="large"
          :loading="loading"
          @click="handleLogin"
        >
          {{ registering ? '注册' : '登录' }}
        </ElButton>
        <ElButton class="mode-button" link type="primary" :disabled="loading" @click="toggleMode">
          {{ registering ? '已有账户，返回登录' : '创建账户' }}
        </ElButton>
      </ElForm>
    </section>
  </main>
</template>

<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, type FormInstance, type FormRules } from "element-plus";
import { Lock, OfficeBuilding, User } from "@element-plus/icons-vue";

import { loginApi, registerApi } from "@/api/auth";
import { setToken } from "@/utils/auth";
import type { LoginParams } from "@/types/room";

const router = useRouter();
const route = useRoute();
const formRef = ref<FormInstance>();
const loading = ref(false);
const registering = ref(false);

const form = reactive<LoginParams>({
  username: "admin",
  password: "123456"
});

const rules: FormRules<LoginParams> = {
  username: [{ required: true, message: "请输入用户名", trigger: "blur" }],
  password: [{ required: true, message: "请输入密码", trigger: "blur" }]
};

async function handleLogin() {
  if (loading.value) return;
  loading.value = true;
  try {
    const valid = await formRef.value?.validate().catch(() => false);
    if (!valid) return;
    if (registering.value) {
      await registerApi({ username: form.username, password: form.password });
      registering.value = false;
      ElMessage.success("注册成功，请登录");
      return;
    }
    const token = await loginApi(form);
    setToken(token);
    ElMessage.success("登录成功");
    const redirect = typeof route.query.redirect === "string" ? route.query.redirect : "/dashboard";
    await router.replace(redirect);
  } catch {
    // Central request handling displays failures and keeps this form usable.
  } finally {
    loading.value = false;
  }
}

function toggleMode() {
  registering.value = !registering.value;
  Object.assign(form, { username: "", password: "" });
  formRef.value?.clearValidate();
}
</script>

<style scoped>
.login-page {
  display: grid;
  min-height: 100vh;
  place-items: center;
  padding: 32px 16px;
  background:
    radial-gradient(circle at 18% 20%, rgba(30, 136, 229, 0.14), transparent 30%),
    radial-gradient(circle at 86% 78%, rgba(44, 182, 125, 0.14), transparent 28%),
    linear-gradient(135deg, #eef5ff 0%, #f7fbf9 52%, #f6f7fb 100%);
}

.login-card {
  width: min(100%, 420px);
  padding: 36px;
  border: 1px solid rgba(148, 163, 184, 0.22);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.88);
  box-shadow: 0 24px 60px rgba(35, 48, 75, 0.16);
  backdrop-filter: blur(18px);
  animation: card-in 360ms ease both;
}

.brand {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 30px;
}

.brand-mark {
  display: grid;
  width: 58px;
  height: 58px;
  place-items: center;
  border-radius: 8px;
  color: #fff;
  background: linear-gradient(135deg, #1666d8, #22a06b);
  box-shadow: 0 12px 26px rgba(22, 102, 216, 0.28);
}

.brand h1 {
  margin: 0;
  color: #111827;
  font-size: 28px;
  line-height: 1.2;
  letter-spacing: 0;
}

.brand p {
  margin: 6px 0 0;
  color: #667085;
  font-size: 14px;
}

.login-button {
  width: 100%;
  margin-top: 6px;
}

.mode-button { display: block; margin: 18px auto 0; }
.registration-hint { margin: 0 0 18px; color: #667085; font-size: 14px; }

@keyframes card-in {
  from {
    opacity: 0;
    transform: translateY(14px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (max-width: 480px) {
  .login-card {
    padding: 28px 22px;
  }

  .brand h1 {
    font-size: 24px;
  }
}
</style>
