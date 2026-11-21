<template>
  <div class="console" :class="{ 'is-collapsed': collapsed, 'nav-open': mobileOpen }">
    <button v-if="mobileOpen" class="nav-backdrop" aria-label="关闭导航" @click="mobileOpen = false" />
    <aside ref="sidebarRef" id="console-navigation" class="sidebar" aria-label="主导航" :inert="isMobile && !mobileOpen" :role="isMobile && mobileOpen ? 'dialog' : undefined" :aria-modal="isMobile && mobileOpen ? true : undefined" @keydown="handleNavigationKey">
      <RouterLink class="console-brand" to="/dashboard"><span v-if="!collapsed">SmartOffice<small>云智办</small></span><ElIcon v-else :size="28"><OfficeBuilding /></ElIcon></RouterLink>
      <p class="nav-group">{{ collapsed ? '办公' : '办公空间' }}</p>
      <nav><RouterLink v-for="item in navigation" :key="item.path" :to="item.path" class="nav-item" :title="item.label"><ElIcon :size="20"><component :is="item.icon" /></ElIcon><span>{{ item.label }}</span></RouterLink></nav>
      <template v-if="session.user?.role === 'ADMIN'">
        <p class="nav-group">管理</p>
        <nav aria-label="管理员功能"><RouterLink v-for="item in administration" :key="item.path" :to="item.path" class="nav-item" :title="item.label"><ElIcon :size="20"><component :is="item.icon" /></ElIcon><span>{{ item.label }}</span></RouterLink></nav>
      </template>
      <ElButton v-if="isMobile" text :icon="Close" class="sidebar-close" @click="mobileOpen = false">关闭导航</ElButton>
    </aside>
    <header class="console-header" :inert="isMobile && mobileOpen">
      <div class="header-location"><ElButton ref="toggleRef" text circle :icon="Fold" aria-label="切换导航" aria-controls="console-navigation" :aria-expanded="isMobile ? mobileOpen : !collapsed" @click="toggleNavigation" /><span class="muted breadcrumb-parent">办公空间 /</span><strong>{{ route.meta.title }}</strong></div>
      <ElDropdown trigger="click" @command="handleCommand"><ElButton text :icon="User">{{ session.user?.username }}<ElTag size="small" class="role-tag">{{ session.user?.role === 'ADMIN' ? '管理员' : '普通用户' }}</ElTag></ElButton><template #dropdown><ElDropdownMenu><ElDropdownItem command="profile">个人中心</ElDropdownItem><ElDropdownItem command="logout" divided>退出登录</ElDropdownItem></ElDropdownMenu></template></ElDropdown>
    </header>
    <main class="console-content" :inert="isMobile && mobileOpen"><RouterView v-slot="{ Component }"><component :is="Component" :key="`${session.user?.userId}:${session.user?.role}:${route.fullPath}`" /></RouterView></main>
  </div>
</template>
<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import type { ButtonInstance } from 'element-plus';
import { House, Calendar, Tickets, Bell, User, Fold, OfficeBuilding, Setting, List, Connection, Close } from '@element-plus/icons-vue';
import { logout, session } from '@/state/session';
const router = useRouter(), route = useRoute();
const collapsed = ref(false), mobileOpen = ref(false);
const media = window.matchMedia('(max-width: 900px)'), isMobile = ref(media.matches);
const sidebarRef = ref<HTMLElement>(), toggleRef = ref<ButtonInstance>();
function mediaChanged(event: MediaQueryListEvent) { isMobile.value = event.matches; mobileOpen.value = false; if (event.matches) collapsed.value = false; }
media.addEventListener('change', mediaChanged);
onBeforeUnmount(() => media.removeEventListener('change', mediaChanged));
const navigation = [{ path: '/dashboard', label: '工作台', icon: House }, { path: '/rooms', label: '会议室预约', icon: Calendar }, { path: '/bookings', label: '我的预约', icon: Tickets }, { path: '/notifications', label: '通知中心', icon: Bell }, { path: '/profile', label: '个人中心', icon: User }];
const administration = [{ path: '/admin/rooms', label: '会议室管理', icon: Setting }, { path: '/admin/bookings', label: '全部预约', icon: List }, { path: '/admin/outbox', label: 'Outbox 监控', icon: Connection }];
function toggleNavigation() { if (isMobile.value) { collapsed.value = false; mobileOpen.value = !mobileOpen.value; } else collapsed.value = !collapsed.value; }
function focusableLinks() { return Array.from(sidebarRef.value?.querySelectorAll<HTMLElement>('a[href], button:not([disabled])') || []); }
function handleNavigationKey(event: KeyboardEvent) {
  if (!isMobile.value || !mobileOpen.value) return;
  if (event.key === 'Escape') { event.preventDefault(); mobileOpen.value = false; return; }
  if (event.key !== 'Tab') return;
  const links = focusableLinks(), first = links[0], last = links[links.length - 1];
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
}
watch(mobileOpen, async (open, wasOpen) => {
  if (!isMobile.value) return;
  await nextTick();
  if (open) focusableLinks()[0]?.focus(); else if (wasOpen) toggleRef.value?.$el.focus();
});
function handleCommand(command: string) { if (command === 'logout') { logout(); void router.replace('/login'); } else void router.push('/profile'); }
watch(() => route.fullPath, () => { mobileOpen.value = false; });
</script>
