import { request } from '@/utils/request';
import type { Notice } from '@/types/console';
export function getMyNotificationsApi() { return request<Notice[]>({ url: '/notifications/my', method: 'get' }); }
