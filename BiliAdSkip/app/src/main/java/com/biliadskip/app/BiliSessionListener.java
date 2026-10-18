package com.biliadskip.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * 承担两件事：
 *  1. 获取「通知使用权」——第三方 App 调用 MediaSessionManager.getActiveSessions() 的必要条件；
 *  2. 完全退出后的唤醒源。B站 离开前台超过 3 分钟时，媒体监听会停掉前台服务、撤掉通知与悬浮球；
 *     之后只能靠这里把服务重新拉起来：本服务由系统绑定、不受冻结影响，
 *     所以 B站 一有通知或一出现播放会话，就能立刻把监听链路唤醒。
 */
public class BiliSessionListener extends NotificationListenerService {

    private static volatile BiliSessionListener instance;

    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;

    public static boolean isConnected() {
        return instance != null;
    }

    /**
     * 运行时检测通知使用权是否已授予。
     * 不能只看 Service 有没有被绑定 —— 授权后系统重新绑定的时机不确定，
     * 会造成"明明给了权限，界面却显示未授予"。
     */
    public static boolean isGranted(Context c) {
        try {
            String enabled = android.provider.Settings.Secure.getString(
                    c.getContentResolver(), "enabled_notification_listeners");
            return enabled != null && enabled.contains(c.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Logx.i("[权限] 通知使用权已连接");
        wakeUp("通知使用权已连接");
        watchBiliSessions();
    }

    /**
     * B站 发通知（例如开始播放时挂出的媒体通知）→ 唤醒。
     * 这是我们唯一能在进程被完全停掉之后还收到的信号，所以不能漏。
     */
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn != null && BiliPresence.TARGET_PKG.equals(sbn.getPackageName())) {
            wakeUp("B站 发来通知");
        }
    }

    /**
     * B站 一出现播放会话（用户点开视频）→ 唤醒。
     * 已连接时注册一次，之后一直保留：服务被完全停掉后，这条监听仍然在系统侧生效。
     */
    private void watchBiliSessions() {
        if (sessionsListener != null) {
            return;
        }
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            if (msm == null) {
                return;
            }
            sessionsListener = list -> {
                if (list == null) {
                    return;
                }
                for (MediaController c : list) {
                    if (BiliPresence.TARGET_PKG.equals(c.getPackageName())) {
                        wakeUp("B站 播放会话出现");
                        return;
                    }
                }
            };
            msm.addOnActiveSessionsChangedListener(sessionsListener,
                    new ComponentName(this, BiliSessionListener.class),
                    new Handler(Looper.getMainLooper()));
            Logx.i("[唤醒] 已注册 B站 会话监听");
        } catch (Exception e) {
            Logx.i("[唤醒] 注册会话监听失败：" + e.getMessage());
        }
    }

    /**
     * 把前台服务（连同悬浮球、监听循环）重新拉起来。
     * 只要用户配置过就拉，不看跳过开关当时是开是关：
     * 开关由"B站 在不在前台"自动决定，手动暂停时也应该能看到球（灰色的已暂停形态）。
     */
    private void wakeUp(String why) {
        if (!Prefs.configured(this) || BiliWatchService.isRunning()) {
            return;
        }
        // Android 12+ 从后台启动前台服务受限，本应用持有悬浮窗权限，属于豁免情形
        try {
            startForegroundService(new Intent(this, BiliWatchService.class));
            Logx.i("[唤醒] " + why + " → 已拉起前台服务");
        } catch (Exception e) {
            Logx.i("[唤醒] " + why + " → 后台无法拉起前台服务，请手动打开 App 一次：" + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }
}
