package com.geek.smartoffice.messaging;

import java.time.LocalDateTime;

public record BookingCreatedEvent(int schemaVersion, String eventId, Long bookingId, Long userId,
                                  Long roomId, String topic, LocalDateTime startTime,
                                  LocalDateTime endTime, LocalDateTime occurredAt) {
}
