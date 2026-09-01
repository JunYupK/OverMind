package com.overmind.application.port;

/**
 * LLM 호출 요청.
 *
 * @param promptVersion 이 요청을 만든 프롬프트의 버전. 픽스처 디렉터리 이름과 같아야 하고,
 *     M0 이후에는 {@code observation.extractor_version}에 그대로 기록된다
 * @param prompt 최종 프롬프트 본문
 */
public record LlmRequest(String promptVersion, String prompt) {}
