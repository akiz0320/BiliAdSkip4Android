package com.biliadskip.app;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * WBI 签名，算法逐字对齐 VideoAdGuard 的 content.js：
 * mixinKeyEncTab 重排 + wts + MD5(query + mixinKey)
 */
public final class Wbi {

    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
    };

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static volatile String imgKey;
    private static volatile String subKey;
    private static volatile long dayStart;

    private Wbi() {
    }

    private static String mixinKey(String orig) {
        StringBuilder sb = new StringBuilder();
        for (int idx : MIXIN_KEY_ENC_TAB) {
            if (idx < orig.length()) {
                sb.append(orig.charAt(idx));
            }
        }
        String s = sb.toString();
        return s.length() > 32 ? s.substring(0, 32) : s;
    }

    private static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5 不可用: " + e.getMessage(), e);
        }
    }

    /** 与 JS 的 encodeURIComponent 对齐（空格编码为 %20 而非 +） */
    private static String enc(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            boolean safe = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '!' || c == '~' || c == '*' || c == '\'';
            if (safe) {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    private static void ensureKeys(String cookie) {
        long today = System.currentTimeMillis() / 86400000L;
        if (imgKey != null && dayStart == today) {
            return;
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "https://api.bilibili.com/x/web-interface/nav").openConnection();
            conn.setRequestProperty("User-Agent", UA);
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            String body = readAll(conn.getInputStream());
            JSONObject json = new JSONObject(body);
            JSONObject img = json.optJSONObject("data") == null
                    ? null : json.getJSONObject("data").optJSONObject("wbi_img");
            if (img == null) {
                throw new IllegalStateException("nav 未返回 wbi_img");
            }
            imgKey = baseName(img.optString("img_url"));
            subKey = baseName(img.optString("sub_url"));
            dayStart = today;
        } catch (Exception e) {
            throw new RuntimeException("获取 WBI 密钥失败: " + e.getMessage(), e);
        }
    }

    private static String baseName(String url) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 对参数做 WBI 签名，返回可直接拼进 query 的有序参数 */
    public static Map<String, String> sign(Map<String, String> params, String cookie) {
        ensureKeys(cookie);
        String mixin = mixinKey(imgKey + subKey);
        TreeMap<String, String> signed = new TreeMap<>();
        signed.putAll(params);
        signed.put("wts", String.valueOf(System.currentTimeMillis() / 1000));

        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : signed.entrySet()) {
            if (q.length() > 0) {
                q.append('&');
            }
            q.append(enc(e.getKey())).append('=').append(enc(e.getValue().replaceAll("[!'()*]", "")));
        }
        String wRid = md5Hex(q + mixin);

        Map<String, String> out = new LinkedHashMap<>(signed);
        out.put("w_rid", wRid);
        return out;
    }

    public static String toQuery(Map<String, String> params, String cookie) {
        Map<String, String> signed = sign(params, cookie);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : signed.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return sb.toString();
    }
}
