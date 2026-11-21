import { reactive } from 'vue';
import { getCurrentUserApi } from '@/api/auth';
import { getToken, clearToken } from '@/utils/auth';
import { createIdentityLoader, IdentityMismatch } from '@/utils/identitySession';
import { errorMessage } from '@/utils/console';
import type { CurrentUser } from '@/types/console';

export const session = reactive({ user: null as CurrentUser | null, error: '' });
const loader = createIdentityLoader(getToken, getCurrentUserApi);
export function resetSession() { loader.reset(); session.user = null; session.error = ''; }
export function logout() { clearToken(); resetSession(); }
export async function loadSession(force = false) {
  const token = getToken();
  try { const user = await loader.load(force); session.user = user; session.error = ''; return user; }
  catch (error) {
    if (getToken() === token) {
      session.user = null; session.error = errorMessage(error);
      if (error instanceof IdentityMismatch) logout();
    }
    throw error;
  }
}
