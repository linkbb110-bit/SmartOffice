import { createRouter, createWebHistory } from 'vue-router';
import { ElMessage } from 'element-plus';
import { getToken } from '@/utils/auth';
import { loadSession, resetSession } from '@/state/session';
import { IdentityMismatch } from '@/utils/identitySession';
import { safeRedirect } from '@/utils/console';

const router = createRouter({
  history: createWebHistory(), scrollBehavior: () => ({ top: 0 }),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/Login.vue'), meta: { public: true } },
    { path: '/session-error', component: () => import('@/views/SessionError.vue'), meta: { public: true } },
    { path: '/', component: () => import('@/layouts/AdminLayout.vue'), redirect: '/dashboard', children: [
      { path: 'dashboard', component: () => import('@/views/Dashboard.vue'), meta: { title: '工作台' } },
      { path: 'rooms', component: () => import('@/views/RoomList.vue'), meta: { title: '会议室预约' } },
      { path: 'bookings', component: () => import('@/views/MyBookings.vue'), meta: { title: '我的预约' } },
      { path: 'notifications', component: () => import('@/views/Notifications.vue'), meta: { title: '通知中心' } },
      { path: 'profile', component: () => import('@/views/Profile.vue'), meta: { title: '个人中心' } },
      { path: 'forbidden', component: () => import('@/views/RouteError.vue'), meta: { title: '无访问权限', forbidden: true } },
      { path: ':pathMatch(.*)*', component: () => import('@/views/RouteError.vue'), meta: { title: '页面不存在' } }
    ] }
  ]
});
router.beforeEach(async to => {
  if (!getToken()) {
    resetSession();
    if (to.path === '/session-error') return { path: '/login', query: { redirect: safeRedirect(to.query.redirect) } };
    return to.meta.public ? true : { path: '/login', query: { redirect: to.fullPath } };
  }
  if (to.meta.public) return true;
  try {
    const user = await loadSession();
    if ((to.meta.admin || to.path.startsWith('/admin/')) && user.role !== 'ADMIN') return '/forbidden';
    return true;
  } catch (error) {
    if (error instanceof IdentityMismatch) ElMessage.warning(error.message);
    return { path: getToken() ? '/session-error' : '/login', query: { redirect: to.fullPath } };
  }
});
export default router;
