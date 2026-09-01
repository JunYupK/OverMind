package com.overmind.application.port;

/**
 * LLM 호출 응답.
 *
 * @param content 모델이 돌려준 본문
 */
public record LlmResponse(String content) {}
