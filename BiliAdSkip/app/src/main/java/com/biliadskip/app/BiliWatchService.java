package com.biliadskip.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 常驻前台服务 + 悬浮球，两者共同保证跳过循环在 B站 处于前台时仍能运行：
 *  - 前台服务：进程不被杀、有常驻通知；
 *  - 悬浮球：进程被系统视为"可见"，不会被 cached 冻结（国内 ROM 保活的关键）。
 */
public class BiliWatchService extends Service {

    private static final String CHANNEL_ID = "biliadskip_watch";
    private static final int NOTI_ID = 1001;

    private static volatile boolean running;
    private static volatile boolean ballShown;
    /** 应用自己的界面在前台时置 true：此时进程本就是前台进程，不需要靠悬浮球保活，球反而挡住界面 */
    private static volatile boolean ballSuppressed;
    private static volatile BiliWatchService instance;

    private FloatingBall ball;

    public static boolean isRunning() {
        return running;
    }

    public static boolean isBallShown() {
        return ballShown;
    }

    public static boolean isBallSuppressed() {
        return ballSuppressed;
    }

    /** 供界面前后台切换调用；服务没在跑时只记状态，等它启动后 syncState 会按这个状态决定显隐 */
    public static void setBallSuppressed(boolean suppressed) {
        if (ballSuppressed == suppressed) {
            return;
        }
        ballSuppressed = suppressed;
        BiliWatchService s = instance;
        if (s == null || s.ball == null) {
            return;
        }
        if (suppressed) {
            s.ball.hide();
        } else if (FloatingBall.canDraw(s)) {
            s.ball.show();
        }
        ballShown = s.ball.isShown();
    }

    /** 供 MediaWatcher 弹出跳过提示用 */
    public static FloatingBall ball() {
        return ballRef;
    }

    /** 自动模式切换启停后，只刷新通知文案，不重启服务 */
    public static void refreshNotification() {
        BiliWatchService s = instance;
        if (s != null) {
            s.notifyNow();
        }
    }

    private static volatile FloatingBall ballRef;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        instance = this;
        ball = new FloatingBall(this);
        ballRef = ball;
        createChannel();
        startForeground(NOTI_ID, buildNotification());
        syncState();
        Logx.i("[服务] 前台服务已启动");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        syncState();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        ballShown = false;
        ballRef = null;
        if (instance == this) {
            instance = null;
        }
        if (ball != null) {
            ball.hide();
        }
        Logx.i("[服务] 前台服务已停止");
        if (MediaWatcher.isRunning()) {
            MediaWatcher.get(this).stop();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 把界面上的开关状态同步到循环与悬浮球上（悬浮球轻点也会走这里） */
    private void syncState() {
        boolean on = Prefs.enabled(this);
        // 配置过就一直跑：自动模式需要持续监测 B站 何时开始播放，不能因为暂停跳过就停掉循环
        if (on || Prefs.autoMode(this)) {
            MediaWatcher.get(this).start();
        } else if (MediaWatcher.isRunning()) {
            MediaWatcher.get(this).stop();
        }

        if (ball != null) {
            // 没有开关：启用即显示。缺悬浮窗权限 / 界面在前台时不显示，但都给了明确原因
            if (FloatingBall.canDraw(this) && !ballSuppressed) {
                ball.show();
            } else {
                ball.hide();
                if (on && !ballSuppressed) {
                    Logx.i("[悬浮球] 缺少「显示在其他应用上层」权限，无法常驻 → 后台会被冻结");
                }
            }
            ball.refresh();
            ballShown = ball.isShown();
        }

        // 首次配置完成：权限齐备且用户已启用过，即视为配置完成，之后跟随 B站 播放自动启停。
        // 这样不依赖某一次点击，避免"忘了点一下就永远停在手动模式"。
        if (!Prefs.configured(this) && Prefs.enabled(this)
                && BiliSessionListener.isGranted(this) && FloatingBall.canDraw(this)) {
            Prefs.setConfigured(this, true);
            Logx.i("[配置] 权限已齐备且已启用 → 首次配置完成，后续跟随 B站 播放自动启停");
        }

        notifyNow();
    }

    private void notifyNow() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTI_ID, buildNotification());
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "B站 广告跳过监听", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                ch.setDescription("保持 BiliAdSkip 在后台监听 B站 播放");
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification() {
        boolean on = Prefs.enabled(this);
        String title;
        String text;
        if (on) {
            title = "BADS 运行中";
            text = "正在监听 B站 播放，命中广告会自动跳过";
        } else if (Prefs.userPaused(this)) {
            // 手动暂停是"粘性"的：不点恢复就一直不自动启用，这里必须说清楚
            title = "BADS 已暂停";
            text = "已手动暂停，B站 再播放也不会自动启用（点悬浮球或进软件恢复）";
        } else if (Prefs.configured(this)) {
            // 配置过却不在监听，只可能是"B站 不在前台"，这是待命而不是故障
            title = "BADS 待命中";
            text = MediaWatcher.isBiliForeground()
                    ? "跳过已停止（点悬浮球可恢复），仍在定位广告"
                    : "B站 未在前台 · 进入 B站 会自动开始监测";
        } else {
            title = "BADS 待配置";
            text = "轻点悬浮球可重新启用";
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .build();
    }
}
