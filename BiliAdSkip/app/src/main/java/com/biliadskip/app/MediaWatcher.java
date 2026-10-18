package com.biliadskip.app;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 核心循环：
 *   轮询 B站 的 MediaSession → 由 MEDIA_ID 反推 aid → 解析 bvid → 后台检测广告区间
 *   → 播放位置进入广告区间时调用 seekTo 跳过
 */
public final class MediaWatcher {

    /** B站 App 的 MediaSession 把 MEDIA_ID 设成 aid × 10（实测归纳） */
    private static final long MEDIA_ID_TO_AID = 10L;

    private static final String TARGET_PKG = "tv.danmaku.bili";
    /**
     * 轮询节拍按需自适应，避免空闲时也在持续唤醒 CPU：
     * B站 在前台且在播 300ms（位置跟得紧，跳转才准）；B站 在前台但没播 1.5s；
     * B站 不在前台 5s（此时只要低频探活，等它回到前台）。
     * 会话什么时候出现由 OnActiveSessionsChangedListener 实时通知，不需要高频扫描。
     */
    private static final long POLL_PLAYING_MS = 300;
    private static final long POLL_FG_IDLE_MS = 1500;
    private static final long POLL_BG_MS = 5000;
    /** 前台判定不必每个 tick 都查系统使用记录，1 秒粒度足够 */
    private static final long PRESENCE_CACHE_MS = 1000;
    /** B站 离开前台超过这么久 → 认定"真的走了"：停前台服务、撤通知、停轮询 */
    private static final long EXIT_AFTER_BG_MS = 3 * 60 * 1000;

    private static volatile MediaWatcher instance;

    private final Context ctx;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();

    private volatile MediaController controller;
    private volatile boolean running;

    private long currentAid = -1;
    private String currentBvid;
    private long currentCid = -1;
    private String currentTitle = "";
    private volatile List<double[]> ranges = new ArrayList<>();
    private final Set<String> skipped = new HashSet<>();
    /** 已经预告过"即将跳过"的区间 */
    private final Set<String> warned = new HashSet<>();
    private volatile boolean detecting;

    // ---- seek 结果验证（区分"调了没跳"与"没调到"）----
    private volatile double[] pendingRange;
    private volatile long pendingTarget = -1;
    private volatile long pendingAt;
    private volatile int pendingRetries;
    private volatile String pendingKey;
    private volatile boolean actionsLogged;
    private volatile long lastNoSessionLog;
    /** 跳过开关关闭时说明原因的限频时间戳 */
    private volatile long lastDisabledLog;
    /** 重新扫描 B站 会话（挑更像播放器的那个）的限频时间戳 */
    private volatile long lastRescan;
    private volatile long lastTickUptime;
    private volatile long lastHealthLog;
    /** 当前节拍下多久算"被冻结"：空闲节拍变长，阈值要跟着变，否则会误报 */
    private volatile long freezeThresholdMs = POLL_PLAYING_MS * 3 + 1500;
    /** B站 此刻是否在前台（在岗）；待命时只低频探活 */
    private volatile boolean biliFg = true;
    /** B站 最近一次在前台的时间：离开超过 3 分钟就完全退出 */
    private volatile long lastBiliFgAt = System.currentTimeMillis();
    private volatile long presenceCheckedAt;
    /** 已决定完全退出：本轮 tick 结束后不再排下一轮 */
    private volatile boolean exiting;

    private MediaSessionManager msm;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private MediaController.Callback stateCallback;

    private MediaWatcher(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public static synchronized MediaWatcher get(Context ctx) {
        if (instance == null) {
            instance = new MediaWatcher(ctx);
        }
        return instance;
    }

    public static boolean isRunning() {
        MediaWatcher w = instance;
        return w != null && w.running;
    }

    /** 供通知文案分档用：B站 是否在前台（循环没在跑时按"不在岗"算） */
    public static boolean isBiliForeground() {
        MediaWatcher w = instance;
        return w != null && w.running && w.biliFg;
    }

    // ------------------------------------------------------------------ 生命周期

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        // 起手就把在岗状态定下来：否则第一次 tick 会把"本来就不在 B站"误报成"刚离开 B站"
        biliFg = BiliPresence.isBiliForeground(ctx);
        lastBiliFgAt = System.currentTimeMillis();
        FloatingBall ball = BiliWatchService.ball();
        if (ball != null) {
            ball.setPresence(biliFg);
        }
        msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (msm != null) {
            sessionsListener = list -> refreshSession();
            try {
                msm.addOnActiveSessionsChangedListener(sessionsListener,
                        new ComponentName(ctx, BiliSessionListener.class), new Handler(Looper.getMainLooper()));
            } catch (Exception e) {
                Logx.i("[监听] 注册会话变化监听失败：" + e.getMessage());
            }
        }
        scheduler.schedule(this::tick, 0, TimeUnit.MILLISECONDS);
        Logx.i("[监听] 核心循环已启动（B站 在播 300ms / 在前台未播 1.5s / 不在前台 5s）");
    }

    public synchronized void stop() {
        running = false;
        unregisterStateCallback();
        if (msm != null && sessionsListener != null) {
            try {
                msm.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (Exception ignored) {
            }
            sessionsListener = null;
        }
        scheduler.shutdownNow();
        worker.shutdownNow();
        instance = null;
        Logx.i("[监听] 核心循环已停止");
    }

    // ------------------------------------------------------------------ 会话

    private void refreshSession() {
        if (msm == null) {
            return;
        }
        try {
            List<MediaController> list = msm.getActiveSessions(new ComponentName(ctx, BiliSessionListener.class));
            MediaController found = null;
            int bestScore = Integer.MIN_VALUE;
            for (MediaController c : list) {
                if (!TARGET_PKG.equals(c.getPackageName())) {
                    continue;
                }
                int score = scoreOf(c);
                if (score > bestScore) {
                    bestScore = score;
                    found = c;
                }
            }
            if (found == null) {
                if (controller != null) {
                    Logx.i("[监听] B站 会话已消失");
                }
                controller = null;
                return;
            }
            // 只有"会话真的变了"才打日志；同一会话只是刷新实例引用，避免日志刷屏与状态竞态
            boolean changed;
            try {
                changed = controller == null
                        || !found.getSessionToken().equals(controller.getSessionToken());
            } catch (Exception e) {
                changed = controller == null;
            }
            if (changed) {
                unregisterStateCallback();
                controller = found;
                registerStateCallback();
                Logx.i("[监听] 已锁定 B站 会话");
            } else if (found.getPlaybackState() != null) {
                // 同会话：用新实例刷新，但绝不换成一个拿不到状态的实例
                controller = found;
            }
        } catch (SecurityException e) {
            Logx.i("[监听] 未获得通知使用权：" + e.getMessage());
        } catch (Exception e) {
            Logx.i("[监听] 获取会话失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 主循环

    /**
     * B站 会同时挂不止一个 MediaSession，其中"耳机控制/自动连播"那类是代理会话：
     * 位置恒为 0、metadata 里也不一定有视频 id。以前按"列表里第一个"取，很容易锁到错的会话，
     * 表现就是"会话明明在、却永远不识别视频也不跳过"。这里给会话打分，挑最像真播放器的那个。
     */
    private int scoreOf(MediaController c) {
        int score = 0;
        try {
            MediaMetadata md = c.getMetadata();
            if (md != null) {
                String id = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
                if (id != null && !id.isEmpty()) {
                    score += 100;
                }
                if (md.getString(MediaMetadata.METADATA_KEY_TITLE) != null) {
                    score += 5;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            PlaybackState ps = c.getPlaybackState();
            if (ps != null) {
                if (ps.getPosition() > 0) {
                    score += 50;
                }
                if ((ps.getActions() & PlaybackState.ACTION_SEEK_TO) != 0) {
                    score += 10;
                }
                if (ps.getState() == PlaybackState.STATE_PLAYING) {
                    score += 20;
                }
            }
        } catch (Exception ignored) {
        }
        return score;
    }

    private void tick() {
        // 下一轮间隔先按最省电的"B站 不在前台"那档算，拿到播放状态后再收紧
        long next = POLL_BG_MS;
        try {
            healthCheck();
            boolean fg = checkPresence();
            if (exiting) {
                return;
            }
            applyAuto(fg);
            if (!fg) {
                // 待命：B站 不在前台，不检测也不跳，只留低频探活等它回来
                return;
            }
            if (controller == null) {
                refreshSession();
                if (controller == null) {
                    // B站 已在前台但还没会话：用户可能只是没点视频，这属于正常状态，不必催
                    if (System.currentTimeMillis() - lastNoSessionLog > 15000) {
                        lastNoSessionLog = System.currentTimeMillis();
                        Logx.i("[监听] B站 已在前台，暂未拿到播放会话（点开视频后会立刻开始检测）");
                    }
                    next = POLL_FG_IDLE_MS;
                    return;
                }
            }
            next = POLL_FG_IDLE_MS;
            PlaybackState ps = controller.getPlaybackState();
            MediaMetadata md = controller.getMetadata();
            if (ps == null) {
                if (System.currentTimeMillis() - lastNoSessionLog > 15000) {
                    lastNoSessionLog = System.currentTimeMillis();
                    Logx.i("[监听] 已锁定会话但暂时读不到播放状态，稍后重试");
                }
                return;
            }
            // 锁到的会话里没有视频 id（多半是锁到了 B站 的代理会话）：重新扫一遍，挑个更像播放器的
            if (aidOf(md) <= 0) {
                if (System.currentTimeMillis() - lastRescan > 2000) {
                    lastRescan = System.currentTimeMillis();
                    Logx.i("[识别] 当前会话读不到视频 id（MEDIA_ID="
                            + safeMediaId(md) + "），重新扫描 B站 会话");
                    refreshSession();
                }
                return;
            }

            int st = ps.getState();
            boolean playing = st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING;
            next = playing ? POLL_PLAYING_MS : POLL_FG_IDLE_MS;
            // 检测不再看开关：只要会话里有有效 aid 就解析、就检测（用户手动暂停时也把广告段先备好），
            // 开关只决定"要不要跳"。以前这里 enabled=false 就直接 return，连检测都被挡掉了。
            if (!Prefs.enabled(ctx) && playing
                    && System.currentTimeMillis() - lastDisabledLog > 20000) {
                lastDisabledLog = System.currentTimeMillis();
                Logx.i(Prefs.userPaused(ctx)
                        ? "[监听] 跳过处于「已手动暂停」，本次只定位广告不跳转"
                        + "（点主界面「启用」或悬浮球即可恢复）"
                        : "[监听] 跳过开关未开启，本次只定位广告不跳转");
            }
            evaluate(ps, md);
        } catch (Exception e) {
            Logx.i("[监听] 循环异常：" + e.getMessage());
        } finally {
            // 自排下一轮（不用固定周期），空闲时才能真正把节拍降下来
            if (running && !exiting) {
                freezeThresholdMs = next * 3 + 1500;
                try {
                    scheduler.schedule(this::tick, next, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                    // scheduler 已 shutdown（stop() 时）
                }
            }
        }
    }

    /**
     * 在岗判定 + "走了太久就完全退出"。
     *
     * 在岗 = B站 在前台：悬浮球显示、链路启动、通知"正在监听"。
     * 待命 = B站 不在前台：球收成侧边细条、跳过开关置 false、轮询降到 5s
     *        —— 只是"暂停播放"不算离开，B站 仍在前台时球和轮询都保持。
     * 完全退出 = 离开超过 3 分钟：停前台服务（通知一起消失）、停轮询、球窗口消失；
     *        之后由 BiliSessionListener 在 B站 再出现时把服务拉起来。
     * （用户自己的界面在前台时不触发退出：那说明人正在本软件里设置，别把服务撤掉。）
     */
    private boolean checkPresence() {
        long now = System.currentTimeMillis();
        if (now - presenceCheckedAt >= PRESENCE_CACHE_MS) {
            presenceCheckedAt = now;
            boolean fg = BiliPresence.isBiliForeground(ctx);
            if (fg != biliFg) {
                biliFg = fg;
                Logx.i(fg ? "[在岗] B站 已进入前台 → 开始监测"
                        : "[在岗] B站 已离开前台 → 待命（跳过暂不生效，回到 B站 自动恢复）");
                BiliWatchService.refreshNotification();
                FloatingBall b = BiliWatchService.ball();
                if (b != null) {
                    b.setPresence(fg);
                }
                if (!fg && !BiliPresence.isGranted(ctx)) {
                    Logx.i("[在岗] 未授予「使用情况访问」，无法判断是否离开了 B站，"
                            + "将一直按在岗处理（请在「手机权限申请」里授予）");
                }
            }
        }
        if (biliFg) {
            lastBiliFgAt = now;
            return true;
        }
        if (Prefs.configured(ctx) && now - lastBiliFgAt > EXIT_AFTER_BG_MS
                && !BiliWatchService.isBallSuppressed()) {
            exiting = true;
            Logx.i("[在岗] 离开 B站 已超过 " + (EXIT_AFTER_BG_MS / 60000)
                    + " 分钟 → 完全退出（停前台服务与通知，B站 再出现时自动唤醒）");
            try {
                ctx.stopService(new android.content.Intent(ctx, BiliWatchService.class));
            } catch (Exception e) {
                Logx.i("[在岗] 停止前台服务失败：" + e.getMessage());
            }
        }
        return false;
    }

    /**
     * 在岗/待命自动启停：进 B站 就启动（除非用户手动暂停过），离开 B站 就待命。
     * 首次运行不自动（用户还要先配权限与大模型）。
     * 注意：这里只切换 enabled 开关，不停 watcher —— watcher 必须一直跑，才能发现 B站 何时回到前台。
     */
    private void applyAuto(boolean fg) {
        if (!Prefs.autoMode(ctx)) {
            return;
        }
        if (fg) {
            // 手动暂停是粘性的：用户自己按的暂停，B站 再打开也不自动恢复
            if (!Prefs.enabled(ctx) && !Prefs.userPaused(ctx)) {
                Prefs.setEnabled(ctx, true);
                Logx.i("[自动] B站 在前台 → 已自动启动跳过");
                onStateChanged();
            }
        } else if (Prefs.enabled(ctx)) {
            Prefs.setEnabled(ctx, false);
            Logx.i("[自动] B站 已离开前台 → 待命（跳过暂不生效）");
            onStateChanged();
        }
    }

    private void onStateChanged() {
        BiliWatchService.refreshNotification();
        FloatingBall b = BiliWatchService.ball();
        if (b != null) {
            b.refresh();
        }
    }

    /**
     * 冻结自检。
     * 进程被系统冻结时轮询线程会被挂起，两次 tick 的真实间隔就会出现远大于轮询间隔的"空洞"，
     * 这是"只有切回本 App 才跳过"的直接证据；同时打印进程重要性，CACHED 就说明会被冻。
     */
    private void healthCheck() {
        long now = android.os.SystemClock.uptimeMillis();
        long gap = lastTickUptime == 0 ? 0 : now - lastTickUptime;
        lastTickUptime = now;
        if (gap > freezeThresholdMs) {
            Logx.i("[健康] ⚠ 检测到进程被冻结约 " + gap + "ms（后台调度被限制，跳过会失准）");
        }
        if (now - lastHealthLog > 15000) {
            lastHealthLog = now;
            Logx.i("[健康] 心跳 | 进程重要性=" + importance()
                    + " | 前台服务=" + (BiliWatchService.isRunning() ? "在" : "不在")
                    + " | 悬浮球=" + (BiliWatchService.isBallShown() ? "在" : "不在"));
        }
    }

    private static String importance() {
        try {
            android.app.ActivityManager.RunningAppProcessInfo info =
                    new android.app.ActivityManager.RunningAppProcessInfo();
            android.app.ActivityManager.getMyMemoryState(info);
            return info.importance + "(" + importanceName(info.importance) + ")";
        } catch (Exception e) {
            return "未知";
        }
    }

    private static String importanceName(int v) {
        switch (v) {
            case android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND:
                return "FOREGROUND";
            case android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE:
                return "FOREGROUND_SERVICE";
            case android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE:
                return "VISIBLE";
            case android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE:
                return "PERCEPTIBLE";
            case android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE:
                return "SERVICE";
            case android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED:
                return "CACHED(会被冻结)";
            case android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_EMPTY:
                return "EMPTY";
            default:
                return "OTHER";
        }
    }

    /** 轮询与状态回调共用的判定入口；synchronized 防止两路并发重复触发检测 */
    private synchronized void evaluate(PlaybackState ps, MediaMetadata md) {
        long aid = aidOf(md);
        if (aid > 0 && aid != currentAid) {
            currentAid = aid;
            currentBvid = null;
            currentCid = -1;
            ranges = new ArrayList<>();
            skipped.clear();
            warned.clear();
            detecting = false;
            pendingRange = null;
            pendingTarget = -1;
            pendingRetries = 0;
            actionsLogged = false;
            currentTitle = md == null ? "" : String.valueOf(md.getString(MediaMetadata.METADATA_KEY_TITLE));
            Logx.i("[识别] 检测到新视频 aid=" + aid + " 标题=" + currentTitle);
            Logx.i("[识别] 会话能力 actions=" + ps.getActions() + " → " + describeActions(ps.getActions()));
            actionsLogged = true;
            final long faid = aid;
            worker.execute(() -> resolveAndDetect(faid));
        } else if (aid > 0 && !actionsLogged) {
            Logx.i("[识别] 会话能力 actions=" + ps.getActions() + " → " + describeActions(ps.getActions()));
            actionsLogged = true;
        }

        // 检测照做（上面），跳过才看开关：待命 / 手动暂停时只定位不跳
        List<double[]> local = ranges;
        if (Prefs.enabled(ctx) && !local.isEmpty() && !detecting) {
            int state = ps.getState();
            if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) {
                long posMs = realPosition(ps);
                if (posMs >= 0) {
                    maybeSkip(posMs, local);
                }
            }
        }
    }

    /**
     * 取"此刻"的播放位置。
     * PlaybackState.getPosition() 给的是**上次状态更新那一刻**的位置，不是实时的：
     * 直接拿它去算"还有几秒到广告"，会系统性偏小（位置偏小 → 剩余时间偏大），
     * 于是提示说"6 秒后"、实际可能只剩 5 秒。这里按更新时刻和倍速外推补回来。
     */
    private static long realPosition(PlaybackState ps) {
        long pos = ps.getPosition();
        if (pos < 0) {
            return pos;
        }
        float speed = 0f;
        try {
            speed = ps.getPlaybackSpeed();
        } catch (Exception ignored) {
        }
        if (speed <= 0f) {
            return pos;
        }
        long dt = android.os.SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime();
        if (dt <= 0) {
            return pos;
        }
        // 外推上限 1.2 秒：更新很旧时不硬编，免得越算越离谱
        if (dt > 1200) {
            dt = 1200;
        }
        return pos + (long) (dt * speed);
    }

    /** 注册播放状态回调：B站 推送即刻响应，不必等下一次轮询 */
    private void registerStateCallback() {
        final MediaController c = controller;
        if (c == null) {
            return;
        }
        try {
            stateCallback = new MediaController.Callback() {
                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    MediaController cur = controller;
                    if (state == null || cur == null) {
                        return;
                    }
                    evaluate(state, cur.getMetadata());
                }
            };
            // 必须显式指定主线程 Handler：本方法会被轮询线程调用，而轮询线程没有 Looper，
            // 不传 Handler 时 MediaController 会在当前线程建 Handler 并直接抛异常，回调就注册不上
            c.registerCallback(stateCallback, new android.os.Handler(android.os.Looper.getMainLooper()));
            Logx.i("[监听] 已注册播放状态回调");
        } catch (Exception e) {
            Logx.i("[监听] 注册状态回调失败（轮询仍可用）：" + e.getMessage());
            stateCallback = null;
        }
    }

    private void unregisterStateCallback() {
        final MediaController c = controller;
        final MediaController.Callback cb = stateCallback;
        stateCallback = null;
        if (c == null || cb == null) {
            return;
        }
        try {
            c.unregisterCallback(cb);
        } catch (Exception ignored) {
        }
    }

    /**
     * 跳过判定 + 结果验证。
     * 之前只记录"我调用了 seekTo"，无法区分「调了但机器没跳」和「根本没调到」，
     * 现在把 seek 结果落成日志：生效 / 未生效并重试 / 连续失败。
     */
    private void maybeSkip(long posMs, List<double[]> local) {
        // 跳过前预告：位置进入广告区间前约 5 秒，先给用户一个提示
        for (double[] r : local) {
            long s0 = (long) (r[0] * 1000);
            long e0 = (long) (r[1] * 1000);
            String k0 = s0 + "-" + e0;
            if (posMs >= s0 - 5500 && posMs < s0 - 400 && !warned.contains(k0)) {
                warned.add(k0);
                // 向下取整：宁可说少也不说多，否则用户会觉得"提示 6 秒、结果 5 秒就跳了"
                int secs = (int) ((s0 - posMs) / 1000);
                if (secs < 1) {
                    secs = 1;
                }
                FloatingBall b = BiliWatchService.ball();
                if (b != null) {
                    b.notifyUpcoming(secs);
                }
                break;
            }
        }

        double[] pr = pendingRange;
        if (pr != null) {
            long since = System.currentTimeMillis() - pendingAt;
            if (since < 1500) {
                return; // 等播放器把位置刷新过来
            }
            if (since > 8000) {
                // 会话中途丢失等异常情况，放弃本次校验，避免误判
                pendingRange = null;
                pendingTarget = -1;
                return;
            }
            long s = (long) (pr[0] * 1000);
            long e = (long) (pr[1] * 1000);
            if (posMs >= s - 400 && posMs < e) {
                if (pendingRetries < 2) {
                    pendingRetries++;
                    Logx.i("[跳过] ⚠ seekTo 未生效：位置仍是 " + fmt(posMs) + "，重试 " + pendingRetries + "/2");
                    doSeek(pendingTarget, pr, pendingKey);
                } else {
                    Logx.i("[跳过] ✗ seekTo 连续失败 → 这台设备/系统可能不允许外部 App 跳转");
                    FloatingBall b = BiliWatchService.ball();
                    if (b != null) {
                        b.notifyFailed();
                    }
                    if (pendingKey != null) {
                        skipped.add(pendingKey);
                    }
                    pendingRange = null;
                    pendingTarget = -1;
                }
            } else {
                Logx.i("[跳过] ✓ 跳过生效：位置已从广告区间移到 " + fmt(posMs) + "（目标 " + fmt(pendingTarget) + "）");
                FloatingBall b = BiliWatchService.ball();
                if (b != null) {
                    b.notifySkipped((int) ((e - s) / 1000), fmt(s) + "→" + fmt(e));
                }
                if (pendingKey != null) {
                    skipped.add(pendingKey);
                }
                pendingRange = null;
                pendingTarget = -1;
            }
            return;
        }

        for (double[] r : local) {
            long startMs = (long) (r[0] * 1000);
            long endMs = (long) (r[1] * 1000);
            String key = startMs + "-" + endMs;
            // 允许提前 0.4s 进入，避免采样间隔漏掉
            if (posMs >= startMs - 400 && posMs < endMs) {
                if (skipped.contains(key)) {
                    return;
                }
                doSeek(endMs + 500, r, key);
                return;
            }
        }
    }

    private void doSeek(long target, double[] range, String key) {
        pendingTarget = target;
        pendingRange = range;
        pendingKey = key;
        pendingAt = System.currentTimeMillis();
        try {
            controller.getTransportControls().seekTo(target);
            Logx.i("[跳过] 请求跳到 " + fmt(target) + "（广告区间 " + fmt((long) (range[0] * 1000))
                    + "~" + fmt((long) (range[1] * 1000)) + "）");
        } catch (Exception e) {
            Logx.i("[跳过] seekTo 抛异常：" + e.getMessage());
            if (key != null) {
                skipped.add(key);
            }
            pendingRange = null;
            pendingTarget = -1;
        }
    }

    static String describeActions(long a) {
        long[] bits = {
                android.media.session.PlaybackState.ACTION_STOP,
                android.media.session.PlaybackState.ACTION_PAUSE,
                android.media.session.PlaybackState.ACTION_PLAY,
                android.media.session.PlaybackState.ACTION_REWIND,
                android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT,
                android.media.session.PlaybackState.ACTION_FAST_FORWARD,
                android.media.session.PlaybackState.ACTION_SEEK_TO,
                android.media.session.PlaybackState.ACTION_PLAY_PAUSE,
        };
        String[] names = {"STOP", "PAUSE", "PLAY", "REWIND", "PREV", "NEXT", "FF", "SEEK_TO", "PLAY_PAUSE"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bits.length; i++) {
            if ((a & bits[i]) != 0) {
                if (sb.length() > 0) {
                    sb.append('|');
                }
                sb.append(names[i]);
            }
        }
        return sb.length() == 0 ? "(空)" : sb.toString();
    }

    // ------------------------------------------------------------------ 识别 + 检测

    private void resolveAndDetect(long aid) {
        String cookie = Prefs.cookie(ctx);
        try {
            org.json.JSONObject v = BiliApi.viewByAid(aid, cookie);
            String bvid = v.optString("bvid", "");
            long cid = v.optLong("cid", -1L);
            String title = v.optString("title", currentTitle);
            if (bvid.isEmpty() || cid <= 0) {
                Logx.i("[识别] aid=" + aid + " 解析不到 bvid/cid");
                return;
            }
            currentBvid = bvid;
            currentCid = cid;
            currentTitle = title;
            Logx.i("[识别] 解析成功 bvid=" + bvid + " cid=" + cid + " 时长=" + v.optLong("duration") + "s");

            detecting = true;
            AdDetector.Result r = AdDetector.detect(ctx, bvid, cid, aid, title);
            // 检测只在切换视频时触发一次，一旦被网络抖动打断（例如字幕 CDN 偶发 TLS 握手失败），
            // 整条视频都会没有广告区间。所以这里对"中断型失败"做两次补测。
            for (int i = 0; i < 2 && r.failed; i++) {
                Logx.i("[检测] 本次检测中断，5 秒后补测（第 " + (i + 1) + " 次）");
                Thread.sleep(5000);
                if (Thread.currentThread().isInterrupted() || currentAid != aid) {
                    Logx.i("[检测] 已切换视频或循环已停止，放弃补测");
                    return;
                }
                r = AdDetector.detect(ctx, bvid, cid, aid, title);
            }
            List<double[]> out = new ArrayList<>(r.ranges);
            ranges = out;
            Logx.i("[检测] 结果：exist=" + r.exist + " 区间=" + out.size()
                    + (r.note.isEmpty() ? "" : "｜" + r.note));
            for (double[] seg : out) {
                Logx.i("[检测] 广告段 " + fmt((long) (seg[0] * 1000)) + " ~ " + fmt((long) (seg[1] * 1000)));
            }
        } catch (Exception e) {
            Logx.i("[检测] 失败：" + e.getMessage());
        } finally {
            detecting = false;
        }
    }

    private static long aidOf(MediaMetadata md) {
        if (md == null) {
            return -1;
        }
        String id;
        try {
            id = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        } catch (Exception e) {
            // MEDIA_ID 不是字符串类型时会抛异常，直接当读不到处理
            return -1;
        }
        if (id == null || id.isEmpty()) {
            return -1;
        }
        try {
            return Long.parseLong(id) / MEDIA_ID_TO_AID;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 诊断用：把会话里的 MEDIA_ID 打出来（读不到时说清是空白还是类型不对） */
    private static String safeMediaId(MediaMetadata md) {
        try {
            String v = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            return v == null || v.isEmpty() ? "(空)" : v;
        } catch (Exception e) {
            return "(非字符串类型)";
        }
    }

    static String fmt(long ms) {
        long total = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60);
    }
}
