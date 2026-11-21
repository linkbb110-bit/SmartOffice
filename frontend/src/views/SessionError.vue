<template><main class="session-error"><section class="surface"><ElResult icon="warning" title="暂时无法确认身份" :sub-title="error || session.error || '用户服务暂不可用，请重试'"><template #extra><ElButton type="primary" :loading="loading" @click="retry">重新连接</ElButton><ElButton @click="login">返回登录</ElButton></template></ElResult></section></main></template>
<script setup lang="ts">
import { ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { loadSession, logout, session } from '@/state/session';
import { safeRedirect, errorMessage } from '@/utils/console';
import { getToken } from '@/utils/auth';
const route = useRoute(), router = useRouter(), loading = ref(false), error = ref('');
async function retry() { if (loading.value) return; loading.value = true; try { await loadSession(true); await router.replace(safeRedirect(route.query.redirect)); } catch (failure) { error.value = errorMessage(failure); if (!getToken()) await router.replace('/login'); } finally { loading.value = false; } }
function login() { logout(); void router.replace({ path: '/login', query: { redirect: safeRedirect(route.query.redirect) } }); }
</script>
