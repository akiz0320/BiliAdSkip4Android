package com.biliadskip.app;

import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * 悬浮球（无开关，启用即显示）。
 *
 * 作用一：让本 App 始终有"可见窗口"，系统就不会把它当 cached 进程冻结 —— 这是后台轮询能跑起来的前提；
 *         所以"靠边隐藏"只是把它变细变淡，绝不移除窗口。
 * 作用二：随时可见的启停状态指示灯 + 跳过提示的展示位。
 *
 * 状态表达：
 *   启动中 → 粉色 + 播放/跳过图标（▶|）；已停止 → 灰色 + 暂停图标（❚❚）
 *   球形态（可拖动，轻点=开/停用，长按=主界面）；靠边/闲置 → 只留一道很淡的状态渐变条（无图标）
 */
public final class FloatingBall {

    private static final int BALL_DP = 56;
    private static final int IDLE_W_DP = 22;
    private static final int IDLE_H_DP = 74;
    /** 待机时实际画出来的那一道状态条宽度：比窗口窄，两侧留白仍吃点击，保证还能点得到 */
    private static final int IDLE_STRIP_DP = 10;
    /** 待机条的不透明度：尽量不打扰，只保留一点"这里有个东西"的暗示 */
    private static final float IDLE_ALPHA = 0.30f;
    private static final long AUTO_DOCK_MS = 8000;
    private static final long BUBBLE_MS = 1800;

    private static final int BUBBLE_UPCOMING = 0xFF3A4150;
    private static final int BUBBLE_DONE = 0xFFFB7299;

    private final Context ctx;
    private final WindowManager wm;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ImageView ballView;
    private WindowManager.LayoutParams ballLp;
    private TextView bubbleView;
    private WindowManager.LayoutParams bubbleLp;

    private boolean shown;
    private boolean idle;
    /** B站 是否在前台（在岗）：决定球的初始形态 */
    private boolean presenceActive = true;

    private float downRawX;
    private float downRawY;
    private int downX;
    private int downY;
    private long downAt;
    private boolean moved;

    private final Runnable dockTask = () -> dock();
    private final Runnable hideBubbleTask = this::hideBubble;

    /**
     * 横竖屏切换（例如 B站 强行竖屏）后屏幕尺寸会变，而悬浮球坐标是绝对值。
     * 不重新夹取的话，球会停在旧屏幕的坐标上，直接跑到屏幕外，用户既看不到也点不到
     * —— 保活窗口丢失，后台又会被系统冻结。所以这里随配置变化重新贴边。
     */
    private final ComponentCallbacks configCallbacks = new ComponentCallbacks() {
        @Override
        public void onConfigurationChanged(Configuration newConfig) {
            reclamp();
        }

        @Override
        public void onLowMemory() {
        }
    };

    public FloatingBall(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    public static boolean canDraw(Context c) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(c);
    }

    public boolean isShown() {
        return shown;
    }

    // ------------------------------------------------------------------ 显示 / 隐藏

    public void show() {
        if (shown || wm == null) {
            return;
        }
        if (!canDraw(ctx)) {
            Logx.i("[悬浮球] 缺少「显示在其他应用上层」权限，无法显示");
            return;
        }
        ballView = new ImageView(ctx);
        ballView.setScaleType(ImageView.ScaleType.FIT_CENTER);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        ballLp = new WindowManager.LayoutParams(dp(BALL_DP), dp(BALL_DP), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        ballLp.gravity = Gravity.TOP | Gravity.START;
        ballLp.x = Prefs.ballX(ctx);
        ballLp.y = Prefs.ballY(ctx);
        // 上次运行可能是另一种屏幕方向，历史坐标可能已经在屏幕外，先夹回可见区域
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        ballLp.x = Math.max(0, Math.min(ballLp.x, dm.widthPixels - dp(BALL_DP)));
        ballLp.y = Math.max(0, Math.min(ballLp.y, dm.heightPixels - dp(BALL_DP)));

        ballView.setOnTouchListener(this::onTouch);
        try {
            wm.addView(ballView, ballLp);
        } catch (Exception e) {
            Logx.i("[悬浮球] 显示失败：" + e.getMessage());
            ballView = null;
            return;
        }
        shown = true;
        ctx.registerComponentCallbacks(configCallbacks);
        // 在岗 → 直接以圆球出现（宁可挡一下，也要让人看到"已经开始监测了"），8 秒后自动收边；
        // 待命 → 直接就是侧边细条
        applyForm(!presenceActive);
        if (presenceActive) {
            scheduleDock();
        }
        Logx.i("[悬浮球] 已显示（进程被系统视为可见，后台不再被冻结）");
    }

    public void hide() {
        cancelDock();
        handler.removeCallbacks(hideBubbleTask);
        try {
            ctx.unregisterComponentCallbacks(configCallbacks);
        } catch (Exception ignored) {
        }
        if (bubbleView != null && wm != null) {
            try {
                wm.removeView(bubbleView);
            } catch (Exception ignored) {
            }
        }
        bubbleView = null;
        if (!shown || wm == null || ballView == null) {
            return;
        }
        try {
            wm.removeView(ballView);
        } catch (Exception ignored) {
        }
        ballView = null;
        shown = false;
        Logx.i("[悬浮球] 已隐藏");
    }

    /** 启停状态变化后刷新图标与配色 */
    public void refresh() {
        runOnMain(() -> {
            if (ballView != null) {
                applyForm(idle);
            }
        });
    }

    /**
     * 在岗/待命切换时的形态变化：
     * 在岗（B站 回到前台）→ 展开成圆球，让人一眼看到"开始监测了"，随后照旧 8 秒自动收边；
     * 待命（B站 离开前台）→ 立即收成侧边细条，不再占地方。
     * 注意：两种形态都保留窗口本身 —— 窗口没了进程就会被系统冻结，轮询也就停了。
     */
    public void setPresence(boolean active) {
        runOnMain(() -> {
            if (presenceActive == active) {
                return;
            }
            presenceActive = active;
            if (!shown || ballView == null) {
                return;
            }
            if (active) {
                applyForm(false);
                scheduleDock();
            } else {
                cancelDock();
                applyForm(true);
            }
        });
    }

    // ------------------------------------------------------------------ 跳过提示

    public void notifyUpcoming(int seconds) {
        showBubble(seconds + " 秒后自动跳过广告", BUBBLE_UPCOMING);
    }

    public void notifySkipped(int skippedSeconds, String range) {
        showBubble("已跳过广告 " + skippedSeconds + " 秒（" + range + "）", BUBBLE_DONE);
    }

    public void notifyFailed() {
        showBubble("本机不允许自动跳转，请手动拖动进度条", BUBBLE_UPCOMING);
    }

    private void showBubble(String text, int strokeColor) {
        runOnMain(() -> {
            if (!shown || wm == null) {
                return;
            }
            if (bubbleView == null) {
                bubbleView = new TextView(ctx);
                bubbleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                bubbleView.setTextColor(0xE6FFFFFF);
                bubbleView.setPadding(dp(11), dp(7), dp(11), dp(7));
                bubbleView.setMaxWidth(dp(260));

                int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;
                bubbleLp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT, type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT);
                bubbleLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                bubbleLp.y = dp(54);
                try {
                    wm.addView(bubbleView, bubbleLp);
                } catch (Exception e) {
                    Logx.i("[悬浮球] 提示显示失败：" + e.getMessage());
                    bubbleView = null;
                    return;
                }
            }
            bubbleView.setText(text);
            Logx.i("[提示] " + text);
            bubbleView.setBackground(bubbleBackground(strokeColor));
            bubbleView.setAlpha(0f);
            bubbleView.setVisibility(View.VISIBLE);
            bubbleView.animate().alpha(0.88f).setDuration(160).start();
            handler.removeCallbacks(hideBubbleTask);
            handler.postDelayed(hideBubbleTask, BUBBLE_MS);
        });
    }

    private void hideBubble() {
        runOnMain(() -> {
            if (bubbleView == null) {
                return;
            }
            bubbleView.animate().alpha(0f).setDuration(220)
                    .withEndAction(() -> {
                        if (bubbleView != null && wm != null) {
                            try {
                                wm.removeView(bubbleView);
                            } catch (Exception ignored) {
                            }
                            bubbleView = null;
                        }
                    }).start();
        });
    }

    // ------------------------------------------------------------------ 触摸

    private boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelDock();
                pressFeedback(true);
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                downX = ballLp.x;
                downY = ballLp.y;
                downAt = System.currentTimeMillis();
                moved = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                int nx = downX + (int) (e.getRawX() - downRawX);
                int ny = downY + (int) (e.getRawY() - downRawY);
                if (Math.abs(nx - downX) > dp(6) || Math.abs(ny - downY) > dp(6)) {
                    moved = true;
                }
                if (moved && idle) {
                    applyForm(false);
                }
                ballLp.x = nx;
                ballLp.y = ny;
                try {
                    wm.updateViewLayout(ballView, ballLp);
                } catch (Exception ignored) {
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                long dt = System.currentTimeMillis() - downAt;
                pressFeedback(false);
                if (!moved && dt < 260) {
                    if (idle) {
                        // 待机细条：轻点只是展开，避免误触开关
                        applyForm(false);
                        scheduleDock();
                    } else {
                        toggle();
                        scheduleDock();
                    }
                } else if (!moved) {
                    openApp();
                } else {
                    dock();
                }
                return true;
            }
            default:
                return false;
        }
    }

    /** 点击反馈：按压缩小 + 轻微触感 */
    private void pressFeedback(boolean pressed) {
        if (ballView == null) {
            return;
        }
        float s = pressed ? 0.88f : 1f;
        ballView.animate().scaleX(s).scaleY(s).setDuration(pressed ? 90 : 130).start();
        if (pressed) {
            ballView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    private void toggle() {
        boolean now = !Prefs.enabled(ctx);
        Prefs.setEnabled(ctx, now);
        Prefs.setUserPaused(ctx, !now);
        Prefs.setConfigured(ctx, true);
        Logx.i("[悬浮球] " + (now ? "已启动跳过" : "已停止跳过"));
        if (!now) {
            // 手动暂停是粘性的：不点恢复就一直不会自动启用。
            // 这一下很可能是误触，必须当场把后果讲明白，否则用户只会觉得"软件坏了"
            showBubble("已暂停自动跳过 · 播放时不再自动启用", BUBBLE_UPCOMING);
        }
        applyForm(idle);
        BiliWatchService.refreshNotification();
        try {
            ctx.startService(new Intent(ctx, BiliWatchService.class));
        } catch (Exception e) {
            Logx.i("[悬浮球] 同步服务状态失败：" + e.getMessage());
        }
    }

    private void openApp() {
        try {
            Intent i = new Intent(ctx, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startService(new Intent(ctx, BiliWatchService.class));
            ctx.startActivity(i);
            Logx.i("[悬浮球] 已打开主界面");
        } catch (Exception e) {
            Logx.i("[悬浮球] 打开主界面失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 形态切换

    private void dock() {
        runOnMain(() -> {
            if (!shown || ballView == null) {
                return;
            }
            int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
            int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
            int size = dp(BALL_DP);
            int center = ballLp.x + size / 2;
            ballLp.x = center < screenW / 2 ? 0 : screenW - size;
            ballLp.y = Math.max(0, Math.min(ballLp.y, screenH - size));
            try {
                wm.updateViewLayout(ballView, ballLp);
            } catch (Exception ignored) {
            }
            Prefs.setBallPos(ctx, ballLp.x, ballLp.y);
            applyForm(true);
        });
    }

    /** 屏幕尺寸变化后把球重新夹回可见区域，并保持原来的左/右贴边 */
    private void reclamp() {
        runOnMain(() -> {
            if (!shown || ballView == null || ballLp == null) {
                return;
            }
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            int screenW = dm.widthPixels;
            int screenH = dm.heightPixels;
            int w = ballLp.width;
            int h = ballLp.height;
            ballLp.y = Math.max(0, Math.min(ballLp.y, screenH - h));
            ballLp.x = (ballLp.x + w / 2) < screenW / 2 ? 0 : screenW - w;
            try {
                wm.updateViewLayout(ballView, ballLp);
            } catch (Exception ignored) {
            }
            Prefs.setBallPos(ctx, ballLp.x, ballLp.y);
            Logx.i("[悬浮球] 屏幕尺寸变了，已重新贴边到 " + ballLp.x + "," + ballLp.y);
        });
    }

    /** idle=true → 贴边细条；false → 圆球。两种形态都体现当前启停状态 */
    private void applyForm(boolean idleForm) {
        if (ballView == null) {
            return;
        }
        idle = idleForm;
        boolean on = Prefs.enabled(ctx);
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;

        ballView.setScaleX(1f);
        ballView.setScaleY(1f);

        if (idleForm) {
            ballLp.width = dp(IDLE_W_DP);
            ballLp.height = dp(IDLE_H_DP);
            ballLp.x = (ballLp.x + dp(BALL_DP) / 2) < screenW / 2 ? 0 : screenW - dp(IDLE_W_DP);
            ballLp.y = Math.max(0, Math.min(ballLp.y,
                    ctx.getResources().getDisplayMetrics().heightPixels - dp(IDLE_H_DP)));
            // 方案A：去掉图标和留白，只画一道窄状态渐变条，整体压到很低的透明度，尽量"看不见"
            ballView.setImageDrawable(null);
            ballView.setPadding(0, 0, 0, 0);
            ballView.setBackground(idleBackground(on));
            ballView.setAlpha(IDLE_ALPHA);
        } else {
            ballLp.width = dp(BALL_DP);
            ballLp.height = dp(BALL_DP);
            ballLp.x = Math.max(0, Math.min(ballLp.x, screenW - dp(BALL_DP)));
            ballView.setImageResource(on ? R.drawable.ic_ball_on : R.drawable.ic_ball_off);
            ballView.setPadding(dp(15), dp(15), dp(15), dp(15));
            ballView.setBackground(ballBackground(on));
            ballView.setAlpha(0.95f);
        }
        try {
            wm.updateViewLayout(ballView, ballLp);
        } catch (Exception ignored) {
        }
    }

    private void scheduleDock() {
        handler.removeCallbacks(dockTask);
        handler.postDelayed(dockTask, AUTO_DOCK_MS);
    }

    private void cancelDock() {
        handler.removeCallbacks(dockTask);
    }

    // ------------------------------------------------------------------ 外观

    private GradientDrawable ballBackground(boolean on) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                on ? new int[]{0xFFFB7299, 0xFFFF9DB6} : new int[]{0xFF525A6B, 0xFF7B8496});
        d.setShape(GradientDrawable.OVAL);
        d.setStroke(dp(1), 0x59FFFFFF);
        return d;
    }

    /**
     * 待机细条（方案A）：只画一道 IDLE_STRIP_DP 宽的状态渐变，启动=粉、停止=灰，
     * 用 InsetDrawable 把两侧留白留出来 —— 窗口宽度不变，所以触摸热区还是原来的大小，不会变得点不到。
     */
    private Drawable idleBackground(boolean on) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                on ? new int[]{0xCCFB7299, 0x99FB7299} : new int[]{0xCC64707F, 0x99434B58});
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(IDLE_STRIP_DP / 2));
        int inset = dp((IDLE_W_DP - IDLE_STRIP_DP) / 2);
        return new InsetDrawable(d, inset, 0, inset, 0);
    }

    private GradientDrawable bubbleBackground(int strokeColor) {
        // 更无感：不要描边，半透明小药丸
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(20));
        d.setColor(0xC71E222B);
        return d;
    }

    // ------------------------------------------------------------------ 工具

    private void runOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            handler.post(r);
        }
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
