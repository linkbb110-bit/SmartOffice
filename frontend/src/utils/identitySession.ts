import type { CurrentUser } from '../types/console';
import { validateSessionIdentity } from './console.ts';

export class IdentityMismatch extends Error { constructor() { super('用户身份或角色已变化，请重新登录'); } }
export function createIdentityLoader(getToken: () => string | null, fetchUser: () => Promise<CurrentUser>) {
  let cached: { token: string; user: CurrentUser } | null = null;
  let pending: { token: string; promise: Promise<CurrentUser> } | null = null;
  let generation = 0;
  return {
    reset() { generation++; cached = null; pending = null; },
    load(force = false): Promise<CurrentUser> {
      const token = getToken(), currentGeneration = generation;
      if (!token) return Promise.reject(new IdentityMismatch());
      if (!force && cached?.token === token) return Promise.resolve(cached.user);
      if (pending?.token === token) return pending.promise;
      const promise = fetchUser().then(user => {
        if (getToken() !== token || generation !== currentGeneration) throw new Error('会话已切换，请重试');
        if (!validateSessionIdentity(user, token)) throw new IdentityMismatch();
        cached = { token, user }; return user;
      }).finally(() => { if (pending?.promise === promise) pending = null; });
      pending = { token, promise }; return promise;
    }
  };
}
