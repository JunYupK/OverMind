package com.overmind.application.port;

/**
 * 의미 추론용 LLM 호출 포트.
 *
 * <p>구현체는 {@code adapter.out} 안에만 존재한다 (AR-3).
 * application과 domain은 이 인터페이스만 안다.
 */
public interface LlmPort {

    LlmResponse complete(LlmRequest request);
}
