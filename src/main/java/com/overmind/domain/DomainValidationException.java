package com.overmind.domain;

/** 도메인 생성 규칙 위반. 메시지에 위반된 값 원문을 담지 않는다. */
public class DomainValidationException extends RuntimeException {

    public DomainValidationException(String message) {
        super(message);
    }
}
