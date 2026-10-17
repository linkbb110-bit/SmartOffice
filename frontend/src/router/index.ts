import { createRouter, createWebHistory } from "vue-router";

import { getToken } from "@/utils/auth";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/",
      redirect: "/dashboard"
    },
    {
      path: "/login",
      name: "login",
      component: () => import("@/views/Login.vue"),
      meta: { public: true }
    },
    {
      path: "/dashboard",
      name: "dashboard",
      component: () => import("@/views/RoomList.vue")
    },
    {
      path: "/:pathMatch(.*)*",
      redirect: "/dashboard"
    }
  ]
});

router.beforeEach((to) => {
  if (to.meta.public || getToken()) {
    return true;
  }

  return {
    path: "/login",
    query: { redirect: to.fullPath }
  };
});

export default router;
