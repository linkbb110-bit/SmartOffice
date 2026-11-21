import { request } from '@/utils/request';
import type { OutboxEvent } from '@/types/console';
export function getOutboxApi() { return request<OutboxEvent[]>({ url: '/admin/messages/outbox', method: 'get' }); }
