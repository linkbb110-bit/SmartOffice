// Only browser-facing effects are substituted; Axios and token helpers remain real.
export const messages = [];
export const redirects = [];
export const ElMessage = { error: message => messages.push(message) };

const router = {
  currentRoute: { value: { path: '/login', fullPath: '/login' } },
  replace: location => { redirects.push(location); return Promise.resolve(); }
};

export default router;
