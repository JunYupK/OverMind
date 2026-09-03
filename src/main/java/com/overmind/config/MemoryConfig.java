package com.overmind.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shared time source for memory adapters and use cases. */
@Configuration(proxyBeanMethods = false)
public class MemoryConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
