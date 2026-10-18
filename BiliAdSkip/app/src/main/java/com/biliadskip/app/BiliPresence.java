package com.biliadskip.app;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Process;

/**
 * 「B站 是否在前台」的判定，基于系统「使用情况访问」权限（PACKAGE_USAGE_STATS）。
 *
 * 为什么不用 MediaSession 反推：用户刚打开 B站、还没点视频时，
 * B站 根本不会创建播放会话（或者只挂一个位置恒为 0 的代理会话），
 * 想靠"有没有会话/有没有在播"来判断"人在不在 B站"是判不出来的，
 * 表现就是"进了 B站 球也不出现、开关也不启动"。
 *
 * 判据：最近一次被拉到前台（ACTIVITY_RESUMED）的包名是不是 B站。
 * 退出 B站 打开别的 App / 回桌面，都会产生一次别的包名的 RESUMED 事件，立刻就能发现。
 */
public final class BiliPresence {

    public static final String TARGET_PKG = "tv.danmaku.bili";

    /**
     * 只回看最近 60 秒的事件：
     * 正常浏览、切页、进出 App 都会不断产生 activity 事件，60 秒窗口足够；
     * 长时间停在同一个页面（例如盯着视频看十分钟）窗口内会为空，此时沿用上一次的结论。
     * 窗口开得太大会每次都扫一大堆事件，而我们每秒钟都要问一次。
     */
    private static final long WINDOW_MS = 60000;

    /** 上一次判定的前台包名：窗口里没有事件时兜底用 */
    private static volatile String cachedFg;

    private BiliPresence() {
    }

    /** 「使用情况访问」是否已授予 */
    public static boolean isGranted(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) {
                return false;
            }
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 当前前台包名；窗口内没有任何 activity 事件时返回上次的结论（可能为 null）。
     */
    public static String foregroundPackage(Context c) {
        if (!isGranted(c)) {
            return null;
        }
        String last = null;
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                return cachedFg;
            }
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - WINDOW_MS, now);
            if (events != null) {
                UsageEvents.Event e = new UsageEvents.Event();
                while (events.hasNextEvent()) {
                    events.getNextEvent(e);
                    // ACTIVITY_RESUMED（= 旧的 MOVE_TO_FOREGROUND，取值都是 1）表示某个界面被拉到前台
                    if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                        String p = e.getPackageName();
                        if (p != null && !p.isEmpty()) {
                            last = p;
                        }
                    }
                }
            }
        } catch (Exception e) {
            return cachedFg;
        }
        if (last != null) {
            cachedFg = last;
            return last;
        }
        return cachedFg;
    }

    /**
     * B站 是否在前台。
     * 没授予权限时返回 true —— 退化回"当作一直在 B站"的旧行为，
     * 宁可多监听一会，也不要在用户没给权限时把跳过开关自动关掉。
     */
    public static boolean isBiliForeground(Context c) {
        if (!isGranted(c)) {
            return true;
        }
        return TARGET_PKG.equals(foregroundPackage(c));
    }
}
