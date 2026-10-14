package com.api.decouper.util;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * api验签工具
 *
 * @author typhoon
 * @since 2025-12-18 15:45 Thursday
 */
public class VerifyUtil {

    /**
     * 验证RSA SHA256签名
     * @param jsonBody 待验签的JSON字符串
     * @param signatureString Base64编码的签名字符串
     * @param publicKeyString 公钥字符串（Base64编码，去除头尾和换行）
     * @return 验签是否通过
     */
    public static boolean verifySignature(String jsonBody, String signatureString, String publicKeyString) {
        try {
            // 清理公钥字符串，移除头尾标识和空白字符
            String cleanKey = publicKeyString
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");

            // 解码公钥
            byte[] keyBytes = Base64.getDecoder().decode(cleanKey);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PublicKey publicKey = keyFactory.generatePublic(keySpec);

            // 解码签名
            byte[] signatureBytes = Base64.getDecoder().decode(signatureString);

            // 验证签名
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update(jsonBody.getBytes("UTF-8"));

            return signature.verify(signatureBytes);
        } catch (Exception e) {
            System.err.println("验签失败: " + e.getMessage());
            return false;
        }
    }

    // 使用示例
    public static void main(String[] args) {
        String jsonBody = "{\"merchantId\":\"M123456\",\"amount\":10000}";
        //String signatureString = "C/EcqFq75L2BDhiACUsbH/ywWBobevHncjo0vOTaBV5wMHUuxbEJU9OXRvg+lmGH3ZE11Xtv5ExEXJgZKqEc4OKdn/6zWXYICy8lLeTP/a1z7OOb+7Y1v1lM95BUKH0FZMu43vkjCcd+wOjwpxnwnV5NO3WXbgBkAKusUK6OvI8fqFM409xBbmrxD8+YP45CHlP7P1AHHDhra7KYUQOiSgBtFt3CuCIgLW4PJ1DF004SGBeWF6k9FSD9QvIjD3nq1uzZgmw8UYg1vnFYREW3QV+gNNpD3q7fmc5RJUW6uy3AkxDXYjfQUUvgHR6xKrDk04rvbnWD+v5d7VZuzoY2Jg==";
        String signatureString = "pyOCCO00PTBhvhbeHZoTxTcs3nCciGfFHGi6HJvjJ6Limk2lfcqOxRIj28Gx3/7DwIaOO9vJaEwT91OGehnF+SO9xnwVyPT/2IG6ictSY3AoH7hz3yRvgjPdcEyc6Cbhit9MJcLZ+A/JovW8FOVdLDzkQ9gwhQMlNuASPbcZec4RcNelkCsy5dQW/2Gcp5smW4EMfTgNCh87Nm+BxhNiz0FJvA51mnqRYt/8UyhWQCIXBnpP7Y1vlc8Qp92bZzvNBdULSM4kpsPnw4jcu1rAMGgBJG4vs3w0eOPO2pTm4ao40QM122+7H9p5OGwm/UIPRLZJHbnHzZsCnaqZYcJF2w==";
        //String publicKeyString = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArPrZ8HOq18EHia2AAqvby4bTUFKDhgP496JVw1DdSvEpXsexCwonTG4C3WBo8NYxsJKmr9H6T8oIY7LPttrv3uMSSAArHJn2Ub/LMWmQx5i6oY4OIqziBjjwCCCLqO3y1+v+p7aPmkLcV2WcYGyf1KPw5eS4bmv/eajazQVfp9fcnmHB238vQLsPGa8JsR7D4JuySRWCbuPaN76dGa6u6KWYjeR4urxp9t8louRBDEzIUek0ckeBLLCMebCiw7d0PbX3Zw5+eN5cE5X45A1hAHuPX94GPHYLu7j4GXwdmzL25KWwJ+Faw8+PWYWD4RA9ywYdoqLb49Z8yvwK83rbpwIDAQAB";
        String publicKeyString = "-----BEGIN PUBLIC KEY-----\n" +
                "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAyr0Es6VsV8FItrje2VTr\n" +
                "/M0OneolYq+Nf2+inGEY29cMdaf2CH1jhqDFypH/p8FQu7p8v6gUDCXN9Ru0vio0\n" +
                "D6a0+V5tOeq5+mUFOYvbtEJUcbz905OLb9Xdpk6jqmhyUfAQNyhBbe9vVIenvAz6\n" +
                "UDtb3HPQbZZozwgZVGFMWbTdIODOMCEoStoc9DTs7kJZIKqGbus55Gx/ayLwfzLc\n" +
                "xUQImP3J7gSq66nFwv/hZgXszRpM55wiE+6tNI2FhHzTaWV/tj59RW5pKG1XH48B\n" +
                "a752KSSBneCipBDUa1TGE34dSLueyOQxOPy/0Zq0jRUfJY8dHdmFYstK8WVLsgZ1\n" +
                "MQIDAQAB\n" +
                "-----END PUBLIC KEY-----\n";
        boolean isValid = verifySignature(jsonBody, signatureString, publicKeyString);
        System.out.println("验签结果: " + (isValid ? "通过" : "失败"));
    }
}

