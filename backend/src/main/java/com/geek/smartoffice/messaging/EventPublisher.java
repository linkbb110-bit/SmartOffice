package com.geek.smartoffice.messaging;

@FunctionalInterface
public interface EventPublisher {
    String publish(BookingCreatedEvent event) throws Exception;
}
