package com.api.decouper.util;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * api参数加密工具
 *
 * @author typhoon
 * @since 2025-12-18 15:45 Thursday
 */
public class SignatureUtil {

    /**
     * 生成RSA SHA256签名
     * @param jsonBody 待签名的JSON字符串
     * @param privateKeyString 私钥字符串（Base64编码，去除头尾和换行）
     * @return Base64编码的签名字符串
     */
    public static String generateSignature(String jsonBody, String privateKeyString) {
        try {
            // 清理私钥字符串，移除头尾标识和空白字符
            String cleanKey = privateKeyString
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                    .replace("-----END RSA PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");

            // 解码私钥
            byte[] keyBytes = Base64.getDecoder().decode(cleanKey);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

            // 生成签名
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(jsonBody.getBytes("UTF-8"));
            byte[] signBytes = signature.sign();

            return Base64.getEncoder().encodeToString(signBytes);
        } catch (Exception e) {
            throw new RuntimeException("签名生成失败: " + e.getMessage(), e);
        }
    }

    // 使用示例
    public static void main(String[] args) {
        String jsonBody = "{\"merchantId\":\"M123456\",\"amount\":10000}";
        //String privateKeyString = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCs+tnwc6rXwQeJrYACq9vLhtNQUoOGA/j3olXDUN1K8Slex7ELCidMbgLdYGjw1jGwkqav0fpPyghjss+22u/e4xJIACscmfZRv8sxaZDHmLqhjg4irOIGOPAIIIuo7fLX6/6nto+aQtxXZZxgbJ/Uo/Dl5Lhua/95qNrNBV+n19yeYcHbfy9Auw8ZrwmxHsPgm7JJFYJu49o3vp0Zrq7opZiN5Hi6vGn23yWi5EEMTMhR6TRyR4EssIx5sKLDt3Q9tfdnDn543lwTlfjkDWEAe49f3gY8dgu7uPgZfB2bMvbkpbAn4VrDz49ZhYPhED3LBh2iotvj1nzK/ArzetunAgMBAAECggEAIK8ZRNymGDQp13LH8DlGw3MLNG8S2NyhSzEKIWiDF6jvlzAGHL7jZAaZVfjkezUNmH5tLmVvdWoIIcZkhvlhP20KRD575uCUcSfbHdADgiMyiFoOA7FE18YkXHTBzr5/FxtJN6lAAvbFKWJSpMmBfa0oVFfeHUccs4EbLofoZg8M1Guyx7Q/DnktPEIqusTkfLLGRTq3b3gm16tjnPbb5X+hkL1UB7gtFAfoHfBmhoC/Lj0m14gZJAEesHJtE+0e/4W0CDojHGiXZmu5TvGDfMKc3Q4Euoj/pzaVvaQNZ5KD28MnAu5T/VRhEAVg21yKjfu+CiaTebS0hO8/qMUE8QKBgQDKd+XQCqftF0oyFJrVzPeW/Pvenq8vs8HgEnJyDxNOLLJLQyqnXQAheasEqZvx+yVvKlizwVIgCeL29gTjZTyTfaz+ckdG6Hqa3GCEBIGpvIyPzE07sBqeHhdOJPaCDpjivMnp6R9CPSLgHqkZt0FruUezJ7HsR5VRjAtPuBYY+QKBgQDatwW6tmto458i/ZuC0vCXjV56gC9GzGyfadNOE2IoD4sLS15yGgaGoR0jBgxIuOSUUomtxBp5d29cTI2NqaUNrlg+cPXewpIGqF99ylajWeMD91FT+WpfCVswWVSHHGNDrag9cmTBrwrS0kkJKyoDXWHMVIRd0KMysh4DxmNhnwKBgG+rJVNTLT804zUv7B4TiFL8gSZNuy3CJSIiyoBiFDG/sS6Vgwb/4jyUUGCn81zJOeJQJtAtxc/p5AZ1tkh846YtYKaN/QX83vJerdTib2lglt4QvmA3RtcCnbP56wPcbTJREcQzUYxFD+Ewx+9pBWoWrJcBQ76f1Jk/dlWnoHhxAoGBANOtNvpR+vp08lunH48qIHhM9heRYDAhQS+3+C4kTGeBQ40W0L2VuqMp4002WUU4zOFQULfAh+7vTPQDkyx796aaCgXExfFmVnXjFyhjYB6QrEn2XtvrFKwOHhdZHZn2O4wQ0jdGM4XQp6UKqG0bPXosMq7TTZiOiKRUBwCYTS75AoGAJpQu3YVKjPMHcCF8T9Q3BMgllfYi0OJGEOfj5HBNLx9IUc7fp4B0Bw6XK7J3dYlXwLFawRX7N/j6xC1zR/wvLfBpxI4H8h0muPFNWMGFc6qtiFaqfKs6RvEp24XcJVojtDsAm/hGWxQEshxTL6BIirU6dmosWfaMj/+enoA/EBU="; // 示例私钥

        String privateKeyString = "-----BEGIN PRIVATE KEY-----\n" +
                "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDKvQSzpWxXwUi2\n" +
                "uN7ZVOv8zQ6d6iVir41/b6KcYRjb1wx1p/YIfWOGoMXKkf+nwVC7uny/qBQMJc31\n" +
                "G7S+KjQPprT5Xm056rn6ZQU5i9u0QlRxvP3Tk4tv1d2mTqOqaHJR8BA3KEFt729U\n" +
                "h6e8DPpQO1vcc9BtlmjPCBlUYUxZtN0g4M4wIShK2hz0NOzuQlkgqoZu6znkbH9r\n" +
                "IvB/MtzFRAiY/cnuBKrrqcXC/+FmBezNGkznnCIT7q00jYWEfNNpZX+2Pn1Fbmko\n" +
                "bVcfjwFrvnYpJIGd4KKkENRrVMYTfh1Iu57I5DE4/L/RmrSNFR8ljx0d2YViy0rx\n" +
                "ZUuyBnUxAgMBAAECggEBALyYSxhmqTuVJPQ9U6jus7xucMt7th1wW3uRuL4qIPrY\n" +
                "uNF2VowdQIlLY9OGL0G5tkV8PHH5g/Ss8kOx61f/YICyvqU/LvJypWEFum+G5/Fu\n" +
                "xqkrex/noA1/8Hm1q9Bo6vfIDv2QaBVBZ9pFe0aW9//MyC5yWhCnm4YD7bQt8t16\n" +
                "l2AvUx4LlGrtBTCf8zd38qIhx07MD7YLLq5vqK0tWD99wTng99WmUFrSZk50wX+y\n" +
                "IrKeWA0eec1OGb248ofqLAkHqXcDiJMzjVEE/LJPte+2LoUZ3o6FiCdM3d4HCd58\n" +
                "4CuIfJD0BHVesv7Q354uwNBU5T4OgqImC2PNmdP/GAECgYEA5WLKemJdWH09XEnw\n" +
                "2JLNaIJAUdfycbyYhTQQwBld7ORhJHssUzDXgFZyK3YIXAElN6HP1Is7CCF7WCJH\n" +
                "ObAi9+FcFCsZwKjDGJEXYFF3uNatL5apMyxP+Rvl0ppQHZ+tDhRT2wTcpN2CqMbw\n" +
                "V5OIhAnG1LriskyNrX/ezBVDyoECgYEA4kK+Qw1IPwTr4n2XPnS7DWhf4L9acW9H\n" +
                "6HztJeaQf3BkPgaN4Uj9dK8ywJBRBG/Vt4exTdao8/iXDdrY7cUBImL9ODO+KG4T\n" +
                "OoBXD63vvlQOWRBg3JqBmNywIaV2BKebB9Z0lqPZXmNITbdkoFum57I0b/atknBB\n" +
                "LYU9hwz6crECgYAXWWIA6ShVTJJFZaVldjQAclm3uwKLv0XLMUyPMUXVSj31J7CQ\n" +
                "W0VkY+hFMkORkfBG5GqyWOXOYxIaG4sifSJiFwd3ahe5+7O4OXSmskQ8hWWllXFt\n" +
                "qxIM6mEPefOzwEAuUbVjcvA+u+mD4pQuZe8ceZ5KoydWPOKjCYQVRAjdgQKBgEyE\n" +
                "7fuXPluxumS6vK+uoqQCLIrBrEC4tcT2mu06cJvo0r33UXxjRvRzW+Kr6/poKAsN\n" +
                "pPUCJ9l6tPtGjB69hYivmZusRB9AOtiKFueCl5Xyr3eHhnqU9kdQO3DlmGgylser\n" +
                "y3o2p4i4YveFkk55MJUjO4CyGDlyJVjTut81kG3BAoGBAN+rDZd3J8C6fpFtBLHg\n" +
                "raKfqiNSROAWV56SiMBTvx9ZPEb5rgjPzloyciHoB7VYj0KCtsW4z+dKvG4v+IS7\n" +
                "zKLmwnxvYMCfW3zFMC0Qwo6It77wN+uIzWqV81pSk+M3HREpnmhRPy78bZaFbsJS\n" +
                "BNHMLgIPF7v+LkC6+MqRmFk9\n" +
                "-----END PRIVATE KEY-----\n";
        System.out.println();

        String signature = generateSignature(jsonBody, privateKeyString);
        System.out.println("x-sign: " + signature);
    }
}

