<template>
  <div class="console" :class="{ 'is-collapsed': collapsed, 'nav-open': mobileOpen }">
    <button v-if="mobileOpen" class="nav-backdrop" aria-label="关闭导航" @click="mobileOpen = false" />
    <aside class="sidebar" aria-label="主导航">
      <RouterLink class="console-brand" to="/dashboard"><span v-if="!collapsed">SmartOffice<small>云智办</small></span><ElIcon v-else :size="28"><OfficeBuilding /></ElIcon></RouterLink>
      <p class="nav-group">{{ collapsed ? '办公' : '办公空间' }}</p>
      <nav><RouterLink v-for="item in navigation" :key="item.path" :to="item.path" class="nav-item" :title="item.label"><ElIcon :size="20"><component :is="item.icon" /></ElIcon><span>{{ item.label }}</span></RouterLink></nav>
    </aside>
    <header class="console-header">
      <div class="header-location"><ElButton text circle :icon="Fold" aria-label="切换导航" @click="toggleNavigation" /><span class="muted breadcrumb-parent">办公空间 /</span><strong>{{ route.meta.title }}</strong></div>
      <ElDropdown trigger="click" @command="handleCommand"><ElButton text :icon="User">{{ session.user?.username }}<ElTag size="small" class="role-tag">{{ session.user?.role === 'ADMIN' ? '管理员' : '普通用户' }}</ElTag></ElButton><template #dropdown><ElDropdownMenu><ElDropdownItem command="profile">个人中心</ElDropdownItem><ElDropdownItem command="logout" divided>退出登录</ElDropdownItem></ElDropdownMenu></template></ElDropdown>
    </header>
    <main class="console-content"><RouterView v-slot="{ Component }"><component :is="Component" :key="`${session.user?.userId}:${session.user?.role}:${route.fullPath}`" /></RouterView></main>
  </div>
</template>
<script setup lang="ts">
import { ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { House, Calendar, Tickets, Bell, User, Fold, OfficeBuilding } from '@element-plus/icons-vue';
import { logout, session } from '@/state/session';
const router = useRouter(), route = useRoute();
const collapsed = ref(false), mobileOpen = ref(false);
const navigation = [{ path: '/dashboard', label: '工作台', icon: House }, { path: '/rooms', label: '会议室预约', icon: Calendar }, { path: '/bookings', label: '我的预约', icon: Tickets }, { path: '/notifications', label: '通知中心', icon: Bell }, { path: '/profile', label: '个人中心', icon: User }];
function toggleNavigation() { if (window.matchMedia('(max-width: 900px)').matches) { collapsed.value = false; mobileOpen.value = !mobileOpen.value; } else collapsed.value = !collapsed.value; }
function handleCommand(command: string) { if (command === 'logout') { logout(); void router.replace('/login'); } else void router.push('/profile'); }
watch(() => route.fullPath, () => { mobileOpen.value = false; });
</script>
