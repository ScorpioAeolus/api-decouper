package com.api.decouper.util;

import lombok.SneakyThrows;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES对称加解密工具
 *
 * @author typhoon
 * @since 2025-12-18 15:45 Thursday
 */
public class AesEncryptUtil {

    private static final String ALGO = "AES/CBC/PKCS5Padding";

    private static final String AES = "AES";

    @SneakyThrows
    public static String encrypt(String content, String encryptKey) {
        try {
            byte[] iv = generateRandomIV();
            IvParameterSpec ivParameterSpec = new IvParameterSpec(iv);
            SecretKeySpec secretKeySpec = new SecretKeySpec(encryptKey.getBytes(StandardCharsets.UTF_8), AES);

            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec);

            byte[] encryptedBytes = cipher.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(appendBytes(iv, encryptedBytes));
        } catch (Exception e) {
            return null;
        }
    }

    @SneakyThrows
    public static String decrypt(String encryptedText, String decryptKey) {
        try {
            byte[] encryptedData = Base64.getDecoder().decode(encryptedText);
            Cipher cipher = Cipher.getInstance(ALGO);
            int ivSize = cipher.getBlockSize();
            byte[] iv = new byte[ivSize];
            System.arraycopy(encryptedData, 0, iv, 0, ivSize);
            IvParameterSpec ivParameterSpec = new IvParameterSpec(iv);
            SecretKeySpec secretKeySpec = new SecretKeySpec(decryptKey.getBytes(StandardCharsets.UTF_8), AES);

            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec);

            byte[] decryptedBytes = cipher.doFinal(encryptedData, ivSize, encryptedData.length - ivSize);
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] generateRandomIV() {
        SecureRandom secureRandom = new SecureRandom();
        byte[] iv = new byte[16]; // 16 bytes = 128 bits
        secureRandom.nextBytes(iv);
        return iv;
    }

    /**
     * 字节数组合并
     */
    private static byte[] appendBytes(byte[] b1, byte[] b2) {
        int length1 = b1.length;
        int length2 = b2.length;
        byte[] result = new byte[length1 + length2];
        System.arraycopy(b1, 0, result, 0, length1);
        System.arraycopy(b2, 0, result, length1, length2);
        return result;
    }

    public static void main(String[] args) {
        //System.out.println(encrypt("8035","N@HzUm7WBt1%XYfI"));
        System.out.println(decrypt("ZHoXRhftAqTpVT26sIirsyTJhOiN4dH9p2uN3lJltEE=","N@HzUm7WBt1%XYfI"));
        //
        System.out.println(decrypt("pVwCL7EGTS1VPnHKm758fNTs5S9L4PelKH2LD15dXOk=","N@HzUm7WBt1%XYfI"));

        System.out.println("lTYykzjAtTYapFhBpulaUnqQf2cdPB8/0hZXKBv1w6KkdoLA3/ehPU+A9LBg5zG63L8+w0I+FuWhHsH9U/Ev8g==".length());

        System.out.println(encrypt("Bhx@zdf131411hsuidwighahiaayohagxyay","N@HzUm7WBt1%XYfI"));
    }

}