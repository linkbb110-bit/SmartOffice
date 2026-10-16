import type { BookRoomParams } from '../types/room';

interface BookingDependencies {
  validate: () => Promise<BookRoomParams | null>;
  send: (payload: BookRoomParams) => Promise<number>;
  newRequestId: () => string;
  onPending?: (pending: boolean) => void;
}

type SubmissionResult = { status: 'busy' | 'invalid' } | { status: 'success'; orderId: number };

export function createBookingSubmission(dependencies: BookingDependencies) {
  let pending = false;
  let fingerprint = '';
  let requestId = '';
  function resetIntent() {
    fingerprint = '';
    requestId = '';
  }
  return {
    resetIntent,
    async submit(): Promise<SubmissionResult> {
      if (pending) return { status: 'busy' };
      pending = true;
      dependencies.onPending?.(true);
      try {
        const payload = await dependencies.validate();
        if (!payload) return { status: 'invalid' };
        const nextFingerprint = JSON.stringify([payload.roomId, payload.topic, payload.startTime, payload.endTime]);
        if (nextFingerprint !== fingerprint || !requestId) {
          fingerprint = nextFingerprint;
          requestId = dependencies.newRequestId();
        }
        const orderId = await dependencies.send({ ...payload, requestId });
        resetIntent();
        return { status: 'success', orderId };
      } finally {
        pending = false;
        dependencies.onPending?.(false);
      }
    }
  };
}
