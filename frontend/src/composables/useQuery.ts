import { onScopeDispose, ref, shallowRef } from 'vue';
import { errorMessage } from '@/utils/console';

export function useQuery<T>(fetcher: () => Promise<T>) {
  const data = shallowRef<T | null>(null), loading = ref(false), error = ref('');
  let revision = 0;
  onScopeDispose(() => { revision++; });
  async function refresh() {
    const current = ++revision; loading.value = true; error.value = '';
    try { const result = await fetcher(); if (current === revision) data.value = result; }
    catch (failure) { if (current === revision) { data.value = null; error.value = errorMessage(failure); } }
    finally { if (current === revision) loading.value = false; }
  }
  return { data, loading, error, refresh };
}
