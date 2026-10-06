package com.github.lechandonga.quotausagepersistencejava.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 不透明游标：v1|<i>accountId</i>|<i>occurredAt</i>|<i>id</i> 的 URL-safe Base64。
 * <p>绑定账户、携带版本号，任何伪造、截断、类型错误、跨账户使用都会被拒绝。</p>
 */
final class CursorCodec {

    private static final String PREFIX = "v1|";

    private CursorCodec() {
    }

    record Cursor(long accountId, long occurredAt, long id) {
    }

    static String encode(long accountId, long occurredAt, long id) {
        String raw = PREFIX + accountId + "|" + occurredAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decode(String token) {
        String raw;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            raw = new String(bytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new QuotaServiceException(QuotaErrorCode.INVALID_CURSOR,
                    "非法游标：Base64 解码失败");
        }
        if (!raw.startsWith(PREFIX)) {
            throw new QuotaServiceException(QuotaErrorCode.INVALID_CURSOR,
                    "非法游标：版本或格式不被支持");
        }
        String[] parts = raw.substring(PREFIX.length()).split("\\|", -1);
        if (parts.length != 3) {
            throw new QuotaServiceException(QuotaErrorCode.INVALID_CURSOR,
                    "非法游标：字段数量不正确");
        }
        try {
            return new Cursor(
                    Long.parseLong(parts[0]),
                    Long.parseLong(parts[1]),
                    Long.parseLong(parts[2]));
        } catch (NumberFormatException e) {
            throw new QuotaServiceException(QuotaErrorCode.INVALID_CURSOR,
                    "非法游标：字段不是合法整数");
        }
    }
}
