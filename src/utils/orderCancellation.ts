interface CancelableOrder {
  id: number;
  status: number;
}

export function createOrderCancellation(
  send: (id: number) => Promise<void>,
  onPending?: (id: number, pending: boolean) => void
) {
  const pending = new Set<number>();
  return async (order: CancelableOrder) => {
    if (order.status !== 1 || pending.has(order.id)) return false;
    pending.add(order.id);
    onPending?.(order.id, true);
    try {
      await send(order.id);
      order.status = 2;
      return true;
    } finally {
      pending.delete(order.id);
      onPending?.(order.id, false);
    }
  };
}
