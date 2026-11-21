import { onScopeDispose, ref, type Ref } from 'vue';
import { errorMessage } from '@/utils/console';

export function useQuery<T>(fetcher: () => Promise<T>) {
  // API 返回普通 JSON 对象，行状态的修改也需要触发筛选、计数与分页更新。
  const data = ref<T | null>(null) as Ref<T | null>, loading = ref(false), error = ref('');
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
