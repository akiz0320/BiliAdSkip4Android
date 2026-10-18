package com.biliadskip.app;

import android.webkit.CookieManager;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** 登录 Cookie 的采集、持久化与校验 */
public final class CookieStore {

    private CookieStore() {
    }

    /**
     * 从 WebView 的 CookieManager 汇总 B站 各域下的 Cookie。
     * B站 的 SESSDATA / bili_jct / DedeUserID 通常挂在 .bilibili.com，这里多取几个域并去重。
     */
    public static String harvest() {
        CookieManager cm = CookieManager.getInstance();
        Map<String, String> merged = new LinkedHashMap<>();
        String[] urls = {
                "https://www.bilibili.com",
                "https://api.bilibili.com",
                "https://passport.bilibili.com",
        };
        for (String url : urls) {
            String raw = cm.getCookie(url);
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            for (String part : raw.split(";")) {
                String kv = part.trim();
                int eq = kv.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String name = kv.substring(0, eq).trim();
                if (!merged.containsKey(name)) {
                    merged.put(name, kv);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String v : merged.values()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(v);
        }
        return sb.toString();
    }

    /** 把 WebView 的 Cookie 刷到磁盘，保证下次打开登录页仍是已登录状态 */
    public static void flush() {
        try {
            CookieManager.getInstance().flush();
        } catch (Exception e) {
            Logx.i("[登录] flush Cookie 失败：" + e.getMessage());
        }
    }

    /** 校验 Cookie 是否真的登录：返回 mid，失败返回 -1 */
    public static long verify(String cookie) {
        if (cookie == null || !cookie.contains("SESSDATA=")) {
            return -1;
        }
        try {
            JSONObject data = BiliApi.nav(cookie);
            if (data != null && data.optBoolean("isLogin", false)) {
                return data.optLong("mid", -1);
            }
        } catch (Exception e) {
            Logx.i("[登录] 校验登录态失败：" + e.getMessage());
        }
        return -1;
    }
}
