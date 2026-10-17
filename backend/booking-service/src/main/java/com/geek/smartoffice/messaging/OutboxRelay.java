package com.geek.smartoffice.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxRelay {
    private final OutboxStore store;
    private final ObjectMapper json;
    private final Clock clock;
    public void dispatchOnce(EventPublisher publisher) {
        for(String id:store.dueIds(LocalDateTime.now(clock))) {
            String owner=UUID.randomUUID().toString();
            var message=store.claim(id,owner,LocalDateTime.now(clock));
            if(message==null) continue;
            try {
                var event=json.readValue(message.payload(),BookingCreatedEvent.class);
                String brokerId=publisher.publish(event);
                store.sent(id,owner,brokerId,LocalDateTime.now(clock));
            } catch(Exception failure) {
                log.warn("outbox delivery failed eventId={} attempts={} type={}",id,message.attempts()+1,failure.getClass().getSimpleName());
                store.retry(message,owner,failure.getClass().getSimpleName(),LocalDateTime.now(clock));
            }
        }
    }
}
