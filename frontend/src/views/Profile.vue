<template>
  <PageHeading title="个人中心" description="当前账户的真实身份与访问角色"><ElButton :loading="loading" @click="refresh">刷新身份</ElButton></PageHeading>
  <section class="surface profile-panel"><LoadError :message="error" @retry="refresh" /><ElDescriptions v-if="session.user" :column="1" border><ElDescriptionsItem label="用户 ID">{{ session.user.userId }}</ElDescriptionsItem><ElDescriptionsItem label="用户名">{{ session.user.username }}</ElDescriptionsItem><ElDescriptionsItem label="角色"><ElTag>{{ session.user.role === 'ADMIN' ? '管理员' : '普通用户' }}</ElTag></ElDescriptionsItem></ElDescriptions><p class="data-caption">身份由用户服务实时查询。角色变化后需要重新登录以获取新的身份令牌。</p><ElButton type="danger" plain @click="exit">退出登录</ElButton></section>
</template>
<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import PageHeading from '@/components/PageHeading.vue';
import LoadError from '@/components/LoadError.vue';
import { loadSession, logout, session } from '@/state/session';
import { getToken } from '@/utils/auth';
import { errorMessage } from '@/utils/console';
const router = useRouter(), loading = ref(false), error = ref('');
async function refresh() { if (loading.value) return; loading.value = true; error.value = ''; try { await loadSession(true); } catch (failure) { error.value = errorMessage(failure); if (!getToken()) await router.replace('/login'); } finally { loading.value = false; } }
function exit() { logout(); void router.replace('/login'); }
</script>
