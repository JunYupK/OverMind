package com.overmind.config;

import com.overmind.adapter.out.security.HmacCursorCodec;
import com.overmind.application.memory.RecallMemory;
import com.overmind.application.memory.RememberMemory;
import com.overmind.application.port.ObservationRepository;
import com.overmind.application.port.SubjectRepository;
import com.overmind.application.port.TransactionBoundary;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires memory use cases, their shared time source, and the externally configured cursor key. */
@Configuration(proxyBeanMethods = false)
public class MemoryConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    HmacCursorCodec hmacCursorCodec(@Value("${overmind.security.cursor-secret}") String cursorSecret) {
        return new HmacCursorCodec(cursorSecret.getBytes(StandardCharsets.UTF_8));
    }

    @Bean
    RememberMemory rememberMemory(
            SubjectRepository subjects,
            ObservationRepository observations,
            TransactionBoundary transactions,
            Clock clock) {
        return new RememberMemory(subjects, observations, transactions, clock);
    }

    @Bean
    RecallMemory recallMemory(
            SubjectRepository subjects, ObservationRepository observations, HmacCursorCodec cursorCodec) {
        return new RecallMemory(subjects, observations, cursorCodec);
    }
}
