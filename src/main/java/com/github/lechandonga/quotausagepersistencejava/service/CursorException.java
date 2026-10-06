package com.github.lechandonga.quotausagepersistencejava.service;

/**
 * 游标非法或指向当前查询条件下已不可见的记录。
 * 调用方必须放弃该游标重新发起第一页查询，服务端不会静默地从头或从中间继续。
 */
public class CursorException extends RuntimeException {

    public CursorException(String message) {
        super(message);
    }

    public CursorException(String message, Throwable cause) {
        super(message, cause);
    }
}
