package com.eaagent.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 信封加密（详细设计 9.3）：每租户独立 Data Key，由主密钥包裹；主密钥（环境变量 MASTER_KEY）不落库。
 * 实现选择：DataKey = HKDF-SHA256(masterKey, tenantId) 确定性派生 —— 与「包裹存储」语义等价
 * （主密钥不出进程、明文不落库），且不新增密钥存储表。密文 = base64(IV || ciphertext || GCM tag)。
 */
@Service
public class CryptoService {
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] masterKey;

    public CryptoService(@Value("${ea.crypto.master-key}") String masterKeyConfig) {
        // SHA-256 归一为 32 字节（满足 AES-256 密钥长度）
        this.masterKey = sha256(masterKeyConfig.getBytes(StandardCharsets.UTF_8));
    }

    public String encrypt(Long tenantId, String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] dataKey = deriveDataKey(tenantId);
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LEN + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ciphertext, 0, out, IV_LEN, ciphertext.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("crypto encrypt failed", e);
        }
    }

    public String decrypt(Long tenantId, String encrypted) {
        if (encrypted == null) {
            return null;
        }
        try {
            byte[] dataKey = deriveDataKey(tenantId);
            byte[] in = Base64.getDecoder().decode(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, in, 0, IV_LEN));
            byte[] plain = cipher.doFinal(in, IV_LEN, in.length - IV_LEN);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("crypto decrypt failed", e);
        }
    }

    /** HMAC-SHA256（回执验签 / secret 派生共用），hex 输出。 */
    public String hmacSha256(Long tenantId, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(deriveDataKey(tenantId), "HmacSHA256"));
            byte[] out = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("hmac failed", e);
        }
    }

    private byte[] deriveDataKey(Long tenantId) {
        // HKDF 简化：HMAC(masterKey, tenantId) 单步派生
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(masterKey, "HmacSHA256"));
            return mac.doFinal(String.valueOf(tenantId).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("data key derivation failed", e);
        }
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Exception e) {
            throw new IllegalStateException("sha256 failed", e);
        }
    }
}