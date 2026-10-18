package com.biliadskip.app;

import android.content.Context;
import android.content.SharedPreferences;

/** 设置存储：登录 Cookie、LLM 配置、开关 */
public final class Prefs {

    private static final String NAME = "biliadskip";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // ---------- 登录态 ----------
    public static void saveCookie(Context c, String cookie) {
        sp(c).edit().putString("cookie", cookie).apply();
    }

    public static String cookie(Context c) {
        return sp(c).getString("cookie", "");
    }

    public static boolean isLoggedIn(Context c) {
        return cookie(c).contains("SESSDATA=");
    }

    // ---------- LLM ----------
    public static void setLlm(Context c, String baseUrl, String apiKey, String model) {
        sp(c).edit()
                .putString("llm_base", baseUrl.trim())
                .putString("llm_key", apiKey.trim())
                .putString("llm_model", model.trim())
                .apply();
    }

    public static String llmBase(Context c) {
        return sp(c).getString("llm_base", "");
    }

    public static String llmKey(Context c) {
        return sp(c).getString("llm_key", "");
    }

    public static String llmModel(Context c) {
        return sp(c).getString("llm_model", "");
    }

    public static boolean llmReady(Context c) {
        return !llmBase(c).isEmpty() && !llmKey(c).isEmpty() && !llmModel(c).isEmpty();
    }

    // ---------- 语音识别（可选） ----------
    public static void setGroqKey(Context c, String key) {
        sp(c).edit().putString("groq_key", key.trim()).apply();
    }

    public static String groqKey(Context c) {
        return sp(c).getString("groq_key", "");
    }

    // ---------- 开关 ----------
    public static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("enabled", v).apply();
    }

    public static boolean enabled(Context c) {
        return sp(c).getBoolean("enabled", false);
    }

    public static void setUseCloudCache(Context c, boolean v) {
        sp(c).edit().putBoolean("cloud_cache", v).apply();
    }

    public static boolean useCloudCache(Context c) {
        return sp(c).getBoolean("cloud_cache", true);
    }

    public static void setWorkerUrl(Context c, String url) {
        sp(c).edit().putString("worker_url", url.trim()).apply();
    }

    public static String workerUrl(Context c) {
        String v = sp(c).getString("worker_url", "");
        return v.isEmpty() ? "https://videoadguard-api.0100320.xyz" : v;
    }

    /** 用户是否手动暂停过：手动暂停后，即使 B站 再播放也不要自动唤起 */
    public static void setUserPaused(Context c, boolean v) {
        sp(c).edit().putBoolean("user_paused", v).apply();
    }

    public static boolean userPaused(Context c) {
        return sp(c).getBoolean("user_paused", false);
    }

    // ---------- 自动模式 ----------
    /** 用户完成过一次配置后，之后跟随 B站 播放自动启停 */
    public static void setConfigured(Context c, boolean v) {
        sp(c).edit().putBoolean("configured", v).apply();
    }

    public static boolean configured(Context c) {
        return sp(c).getBoolean("configured", false);
    }

    /** 自动模式：配置过就自动跟随；首次运行仍是手动（还要配权限与大模型） */
    public static boolean autoMode(Context c) {
        return configured(c);
    }

    // ---------- 悬浮球 ----------
    // 悬浮球不给用户开关：只要启用就必须有它，否则进程会被后台冻结、跳过失效
    public static int ballX(Context c) {
        return sp(c).getInt("ball_x", 0);
    }

    public static int ballY(Context c) {
        return sp(c).getInt("ball_y", 300);
    }

    public static void setBallPos(Context c, int x, int y) {
        sp(c).edit().putInt("ball_x", x).putInt("ball_y", y).apply();
    }
}
