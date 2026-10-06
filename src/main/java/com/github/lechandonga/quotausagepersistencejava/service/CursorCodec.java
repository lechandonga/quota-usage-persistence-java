package com.github.lechandonga.quotausagepersistencejava.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/**
 * 不透明游标：base64url(accountNo|epochMicros|id).hmac。
 * 签名保证游标不可伪造；解析后还需由仓储校验锚点记录真实存在且在当前查询窗口内可见。
 */
@Component
public class CursorCodec {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SEP = "|";

    private final byte[] secret;
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder decoder = Base64.getUrlDecoder();

    public CursorCodec(@Value("${quota.cursor.secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String encode(String accountNo, Instant occurredAt, long recordId) {
        String payload = accountNo + SEP + toEpochMicros(occurredAt) + SEP + recordId;
        String token = encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return token + "." + hmac(token);
    }

    public CursorAnchor decode(String cursor) {
        int dot = cursor.lastIndexOf('.');
        if (dot <= 0 || dot == cursor.length() - 1) {
            throw new CursorException("游标格式非法：缺少签名段");
        }
        String token = cursor.substring(0, dot);
        String signature = cursor.substring(dot + 1);
        if (!MessageDigest.isEqual(
                hmac(token).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            throw new CursorException("游标签名校验失败：游标可能已被篡改");
        }
        String payload;
        try {
            payload = new String(decoder.decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new CursorException("游标格式非法：payload 不是合法的 base64url", e);
        }
        String[] parts = payload.split(java.util.regex.Pattern.quote(SEP), -1);
        if (parts.length != 3) {
            throw new CursorException("游标格式非法：字段数量不正确");
        }
        long epochMicros;
        long recordId;
        try {
            epochMicros = Long.parseLong(parts[1]);
            recordId = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            throw new CursorException("游标格式非法：时间戳或记录ID不是数字", e);
        }
        if (recordId <= 0 || epochMicros < 0) {
            throw new CursorException("游标格式非法：时间戳或记录ID超出合法范围");
        }
        return new CursorAnchor(parts[0], fromEpochMicros(epochMicros), recordId);
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return encoder.encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算游标签名", e);
        }
    }

    private static long toEpochMicros(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1000);
    }

    private static Instant fromEpochMicros(long epochMicros) {
        long seconds = Math.floorDiv(epochMicros, 1_000_000L);
        long micros = Math.floorMod(epochMicros, 1_000_000L);
        return Instant.ofEpochSecond(seconds, Math.multiplyExact(micros, 1000L));
    }

    /**
     * 游标锚点：上一页最后一条记录的键集 (accountNo, occurredAt, id)。
     */
    public record CursorAnchor(String accountNo, Instant occurredAt, long recordId) {
    }
}
