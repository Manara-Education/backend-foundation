package com.manara.backend.common.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final String messageCode;
    private final Object[] args;

    public BusinessException(String messageCode, Object... args) {
        super(messageCode);
        this.messageCode = messageCode;
        this.args = args;
    }
}
