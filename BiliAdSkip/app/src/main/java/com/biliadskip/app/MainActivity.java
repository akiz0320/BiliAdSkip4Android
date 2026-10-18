package com.biliadskip.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_NOTI = 101;
    /** 日志变化后合并渲染的间隔：避免连续多条日志触发整屏重建 */
    private static final long RENDER_COALESCE_MS = 300;

    private LinearLayout statusBox;
    private TextView output;
    private TextView toggle;
    private TextView loginBtn;
    /** 日志卡是否展开：展开时每来一条日志都要刷新，收起时不必浪费渲染 */
    private boolean logExpanded;
    private ScrollView logScroll;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean renderPending;
    private final Runnable renderTask = () -> {
        renderPending = false;
        render();
    };

    /** 0=未知 1=有效 2=失效 */
    private int sessionState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusBox = findViewById(R.id.status_box);
        output = findViewById(R.id.output);
        toggle = findViewById(R.id.btn_toggle);
        loginBtn = findViewById(R.id.btn_login);
        logScroll = findViewById(R.id.log_scroll);

        Logx.setOnChange(this::scheduleRender);

        loginBtn.setOnClickListener(v -> {
            // 已登录且校验有效时，按钮只做状态展示，不提供换号
            if (Prefs.isLoggedIn(this) && sessionState != 2) {
                Toast.makeText(this, "已登录 B站，暂不支持切换账号", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(this, LoginActivity.class));
        });

        findViewById(R.id.btn_notif).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        findViewById(R.id.btn_usage).setOnClickListener(v -> requestUsageAccess());

        findViewById(R.id.btn_battery).setOnClickListener(v -> requestBatteryWhitelist());

        findViewById(R.id.btn_ball).setOnClickListener(v -> requestOverlayPermission());

        findViewById(R.id.btn_llm).setOnClickListener(v -> editLlm());

        findViewById(R.id.btn_groq).setOnClickListener(v -> editGroq());

        findViewById(R.id.btn_clear).setOnClickListener(v -> {
            Logx.clear();
            renderLog();
        });

        toggle.setOnClickListener(v -> {
            boolean turnOn = !Prefs.enabled(this);
            Prefs.setEnabled(this, turnOn);
            // 记住用户的手动意图：手动暂停后，下次播 B站 也不自动唤起，直到用户手动再启用
            Prefs.setUserPaused(this, !turnOn);
            if (turnOn && !Prefs.configured(this)) {
                // 第一次启用即完成配置，之后进入"跟随 B站 播放自动启停"的自动模式
                Prefs.setConfigured(this, true);
                Logx.i("[开关] 首次启用完成配置，后续将跟随 B站 播放自动启停");
            }
            if (turnOn) {
                ensureNotificationPermission();
                if (!BiliSessionListener.isGranted(this)) {
                    Logx.i("[开关] 已启用，但还缺「通知使用权」，请先授予");
                }
                if (!BiliPresence.isGranted(this)) {
                    Logx.i("[开关] 还缺「使用情况访问」，无法判断 B站 是否在前台，请先授予");
                }
                if (!FloatingBall.canDraw(this)) {
                    Logx.i("[开关] 还缺「显示在其他应用上层」权限，悬浮球无法常驻 → 后台会被冻结");
                    requestOverlayPermission();
                }
                if (!MediaWatcher.isBiliForeground()) {
                    // 待命时开关会自动置回关闭，先讲清楚，免得用户以为"点了启用却不生效"
                    Logx.i("[开关] 已启用；当前 B站 不在前台，属于待命状态，进入 B站 后会自动开始监测");
                }
                try {
                    // 前台服务承载 + 悬浮球保活；自动模式期间服务要常驻，所以暂停时也不停它
                    startForegroundService(new Intent(this, BiliWatchService.class));
                } catch (Exception e) {
                    Logx.i("[开关] 启动前台服务失败：" + e.getMessage());
                }
            } else {
                Logx.i("[开关] 已手动暂停跳过（B站 下次播放时会自动恢复）");
                if (BiliWatchService.isRunning()) {
                    try {
                        startService(new Intent(this, BiliWatchService.class));
                    } catch (Exception e) {
                        Logx.i("[开关] 同步服务状态失败：" + e.getMessage());
                    }
                }
            }
            // 立刻同步悬浮球外观与通知文案，不等服务回调
            FloatingBall b = BiliWatchService.ball();
            if (b != null) {
                b.refresh();
            }
            BiliWatchService.refreshNotification();
            render();
        });

        render();

        // 可折叠卡片：点标题行展开/收起（运行状态常驻展开，不参与折叠）
        bindCard(R.id.card_perm_head, R.id.card_perm_arrow, R.id.card_perm_body, false);
        bindCard(R.id.card_ai_head, R.id.card_ai_arrow, R.id.card_ai_body, false);
        bindCard(R.id.card_log_head, R.id.card_log_arrow, R.id.card_log_body, false);
        bindCard(R.id.card_about_head, R.id.card_about_arrow, R.id.card_about_body, false);

        renderAbout();
    }

    @Override
    protected void onDestroy() {
        // Logx 的回调是静态的，不清掉会把本界面一起泄漏住
        Logx.setOnChange(null);
        uiHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 折叠卡

    private void bindCard(int headId, int arrowId, int bodyId, boolean expanded) {
        final View body = findViewById(bodyId);
        final View arrow = findViewById(arrowId);
        final boolean[] state = {expanded};
        applyCard(body, arrow, expanded, false);
        findViewById(headId).setOnClickListener(v -> {
            state[0] = !state[0];
            applyCard(body, arrow, state[0], true);
            if (bodyId == R.id.card_log_body) {
                logExpanded = state[0];
                if (state[0]) {
                    renderLog();
                }
            }
        });
    }

    private void applyCard(View body, View arrow, boolean expanded, boolean animated) {
        rotateArrow(arrow, expanded, animated);
        if (animated) {
            animateHeight(body, expanded);
        } else {
            body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
    }

    /** 展开箭头：收起=0°（指向下），展开=180°（指向上），带弹簧回弹 */
    private void rotateArrow(View arrow, boolean expanded, boolean animated) {
        float to = expanded ? 180f : 0f;
        if (animated) {
            arrow.animate().rotation(to).setDuration(320)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.8f))
                    .start();
        } else {
            arrow.setRotation(to);
        }
    }

    /** 高度动画：展开/收起顺滑，避免"啪"地跳变 */
    private void animateHeight(final View body, final boolean expanded) {
        final ViewGroup.LayoutParams lp = body.getLayoutParams();
        final int stored = lp.height > 0 ? lp.height : ViewGroup.LayoutParams.WRAP_CONTENT;

        int target;
        if (expanded) {
            body.setVisibility(View.VISIBLE);
            if (stored > 0) {
                target = stored;
            } else {
                View parent = (View) body.getParent();
                body.measure(View.MeasureSpec.makeMeasureSpec(
                                Math.max(parent.getWidth(), 1), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                target = body.getMeasuredHeight();
            }
        } else {
            target = 0;
        }
        final int from = expanded ? 0 : Math.max(body.getHeight(), 1);

        ValueAnimator anim = ValueAnimator.ofInt(from, target);
        anim.setDuration(expanded ? 320 : 240);
        anim.setInterpolator(expanded
                ? new android.view.animation.OvershootInterpolator(0.7f)
                : new android.view.animation.DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            lp.height = (int) a.getAnimatedValue();
            body.requestLayout();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                lp.height = expanded ? stored : 0;
                if (!expanded) {
                    body.setVisibility(View.GONE);
                }
                body.requestLayout();
            }
        });
        anim.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 已启用但前台服务没在跑（例如重启手机后）：在界面里补拉一次
        if (Prefs.configured(this) && !BiliWatchService.isRunning()) {
            try {
                startForegroundService(new Intent(this, BiliWatchService.class));
                Logx.i("[设置] 检测到已启用但前台服务未运行，已补拉前台服务");
            } catch (Exception e) {
                Logx.i("[设置] 补拉前台服务失败：" + e.getMessage());
            }
        }
        render();
        checkSession();
    }

    // ------------------------------------------------------------------ 渲染

    /** 日志可能连续刷屏，这里合并成一次渲染，避免整屏反复重建 */
    private void scheduleRender() {
        if (renderPending) {
            return;
        }
        renderPending = true;
        uiHandler.postDelayed(renderTask, RENDER_COALESCE_MS);
    }

    private void render() {
        boolean on = Prefs.enabled(this);
        boolean notif = BiliSessionListener.isGranted(this);
        boolean usage = BiliPresence.isGranted(this);
        boolean login = Prefs.isLoggedIn(this);
        boolean llm = Prefs.llmReady(this);
        boolean groq = !Prefs.groqKey(this).isEmpty();
        boolean ballShown = BiliWatchService.isBallShown();

        // 权限按钮自带状态：授予了就给个 ✓ 与绿色，没授予保持中性色
        boolean canBall = FloatingBall.canDraw(this);
        syncPermBtn(R.id.btn_notif, "通知使用权", notif);
        syncPermBtn(R.id.btn_usage, "使用情况访问", usage);
        syncPermBtn(R.id.btn_ball, "悬浮窗权限", canBall);
        syncPermBtn(R.id.btn_battery, "电池白名单", batteryWhitelisted());

        // 主按钮：关闭态用主色（引导点），开启态用中性色（表示"可停"）
        toggle.setText(on ? "暂停" : "启用");
        toggle.setBackgroundResource(on ? R.drawable.btn_primary_off : R.drawable.btn_primary);

        // 已登录且校验有效 → 完成态；失效 → 允许重新登录；未登录 → 去登录
        boolean loginOk = login && sessionState != 2;
        loginBtn.setText(loginOk ? "✓ 已登录 B站"
                : (login ? "登录已失效 · 点此重新登录" : "登录 B站"));
        loginBtn.setTextColor(getColor(loginOk ? R.color.ok : R.color.accent));
        loginBtn.setBackgroundResource(loginOk ? R.drawable.btn_login_done : R.drawable.btn_login);

        statusBox.removeAllViews();
        addRow("前台服务", BiliWatchService.isRunning() ? "运行中" : "未运行",
                BiliWatchService.isRunning() ? R.color.ok : R.color.bad);
        // 独立于"前台服务"：服务是常驻的，这里反映的是跳过开关的启停
        addRow("跳过状态", on ? "启动中" : "已停止", on ? R.color.ok : R.color.text_dim);

        // 在岗/待命：新模型下"没在监听"多半只是 B站 不在前台，不是故障，必须写清楚
        if (!BiliWatchService.isRunning()) {
            addRow("在岗状态", "未在岗（前台服务已停）", R.color.bad);
        } else if (MediaWatcher.isBiliForeground()) {
            addRow("在岗状态", "B站 在前台 · 正在监测", R.color.ok);
        } else {
            addRow("在岗状态", "待命中 · B站 不在前台", R.color.warn);
        }

        // 手动暂停是"粘性"的（B站 再播放也不自动启用），必须在界面上说清楚，否则用户只会以为软件坏了
        String modeValue;
        int modeColor;
        if (!Prefs.configured(this)) {
            modeValue = "手动（首次配置）";
            modeColor = R.color.text_dim;
        } else if (Prefs.userPaused(this)) {
            modeValue = "已手动暂停 · 不再自动启用";
            modeColor = R.color.warn;
        } else {
            modeValue = "自动（跟随 B站）";
            modeColor = R.color.ok;
        }
        addRow("运行模式", modeValue, modeColor);

        String ballValue;
        int ballColor;
        if (!canBall) {
            ballValue = "缺悬浮窗权限";
            ballColor = R.color.warn;
        } else if (BiliWatchService.isBallSuppressed()) {
            // 界面在前台时刻意隐藏：此时进程已是前台，不靠球保活
            ballValue = "软件内隐藏 · 后台常驻";
            ballColor = R.color.ok;
        } else {
            ballValue = ballShown ? "已常驻" : "未显示";
            ballColor = ballShown ? R.color.ok : R.color.warn;
        }
        addRow("悬浮球", ballValue, ballColor);

        addRow("通知使用权", notif ? "已授予" : "未授予", notif ? R.color.ok : R.color.warn);
        addRow("使用情况访问", usage ? "已授予" : "未授予（无法判断是否离开 B站）",
                usage ? R.color.ok : R.color.warn);

        String loginValue;
        int loginColor;
        if (!login) {
            loginValue = "未登录（无官方字幕）";
            loginColor = R.color.accent;
        } else if (sessionState == 1) {
            loginValue = "已登录 · 有效";
            loginColor = R.color.ok;
        } else if (sessionState == 2) {
            loginValue = "已登录 · 失效";
            loginColor = R.color.bad;
        } else {
            loginValue = "已登录";
            loginColor = R.color.text_dim;
        }
        addRow("B站账号", loginValue, loginColor);

        addRow("大模型", llm ? Prefs.llmModel(this) : "未配置", llm ? R.color.ok : R.color.warn);
        addRow("语音转写", groq ? "已配置" : "未配置", groq ? R.color.ok : R.color.text_faint);

        // 日志只在展开时渲染；展开着的时候每来一条都要跟着刷，否则看起来像"日志卡住不动"
        if (logExpanded) {
            renderLog();
        }
    }

    /**
     * 「关于与防伪」：版本、本机签名指纹、唯一来源与免责声明。
     * 签名指纹是硬防伪：用户拿界面上这一串和仓库 README / Release 说明里公布的指纹比对，
     * 对得上才能确认手上的 APK 是本仓库构建的，没有被人重新打包塞进别的东西。
     */
    private void renderAbout() {
        ((TextView) findViewById(R.id.card_about_title))
                .setText("关于与防伪 · v" + BuildConfig.VERSION_NAME);
        ((TextView) findViewById(R.id.about_version))
                .setText("版本 " + BuildConfig.VERSION_NAME + "（versionCode " + BuildConfig.VERSION_CODE + "）");
        ((TextView) findViewById(R.id.about_fingerprint))
                .setText("本机签名指纹 SHA-256：\n" + signingFingerprint());
        ((TextView) findViewById(R.id.about_notice))
                .setText("唯一发布来源：github.com/akiz0320/BiliAdSkip4Android\n"
                        + "本项目为个人非商业项目，与哔哩哔哩官方无关，未使用其闭源代码或素材；"
                        + "请勿用于商业用途或二次分发牟利。\n"
                        + "B站 接口、登录态与自动跳转的使用风险由使用者自负，广告识别结果可能出错；"
                        + "本软件不对任何账号异常负责。");
    }

    /** 当前安装包的签名证书 SHA-256 指纹（大写、冒号分隔，与 keytool 输出格式一致） */
    private String signingFingerprint() {
        try {
            PackageManager pm = getPackageManager();
            String pkg = getPackageName();
            android.content.pm.Signature[] sigs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                android.content.pm.PackageInfo pi =
                        pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                android.content.pm.SigningInfo info = pi.signingInfo;
                sigs = info == null ? null : info.getApkContentsSigners();
            } else {
                sigs = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures;
            }
            if (sigs == null || sigs.length == 0) {
                return "读取失败（包里没有签名信息）";
            }
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sigs[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                if (sb.length() > 0) {
                    sb.append(':');
                }
                sb.append(String.format(Locale.US, "%02X", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "读取失败：" + e.getMessage();
        }
    }

    private void addRow(String label, String value, int colorRes) {
        if (statusBox.getChildCount() > 0) {
            View divider = new View(this);
            divider.setBackgroundColor(getColor(R.color.stroke));
            statusBox.addView(divider, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(0.6f)));
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(13f);
        l.setTextColor(getColor(R.color.text_dim));
        l.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(13f);
        v.setTextColor(getColor(colorRes));
        v.setGravity(Gravity.END);

        row.addView(l);
        row.addView(v);
        statusBox.addView(row);
    }

    /**
     * 日志按级别染色，并**倒序**输出：最新的一条在最上面，不用一路往下翻。
     * 只有当用户本来就停在顶部时才跟着滚到顶，避免打断正在翻旧日志的人。
     */
    private void renderLog() {
        List<String> lines = Logx.lines();
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            int start = sb.length();
            sb.append(line).append('\n');
            sb.setSpan(new ForegroundColorSpan(logColor(line)), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        boolean atTop = logScroll == null || logScroll.getScrollY() <= dp(8);
        output.setText(sb);
        if (atTop && logScroll != null) {
            logScroll.scrollTo(0, 0);
        }
    }

    private int logColor(String line) {
        String t = line.toLowerCase(Locale.US);
        if (t.contains("失败") || t.contains("异常") || t.contains("错误") || t.contains("⚠")) {
            return getColor(R.color.bad);
        }
        if (t.contains("✓") || t.contains("成功") || t.contains("生效")) {
            return getColor(R.color.ok);
        }
        if (t.contains("未配置") || t.contains("未授予") || t.contains("缺少")
                || t.contains("已暂停") || t.contains("未就绪")) {
            return getColor(R.color.warn);
        }
        return getColor(R.color.log_text);
    }

    /** 输入框占位符：浅色 + 不换行 + 超长省略，避免长文案糊在输入框里 */
    private void styleHint(EditText e, String hint) {
        e.setHint(hint);
        e.setHintTextColor(getColor(R.color.text_faint));
        e.setTextColor(getColor(R.color.text));
        e.setSingleLine(true);
        e.setEllipsize(TextUtils.TruncateAt.END);
    }

    /** 校验已保存的登录态是否仍然有效 */
    private void checkSession() {
        if (!Prefs.isLoggedIn(this)) {
            sessionState = 0;
            return;
        }
        final String cookie = Prefs.cookie(this);
        new Thread(() -> {
            long mid = CookieStore.verify(cookie);
            runOnUiThread(() -> {
                sessionState = mid > 0 ? 1 : 2;
                if (mid > 0) {
                    Logx.i("[登录] 登录态有效 mid=" + mid);
                } else {
                    Logx.i("[登录] 登录态已失效，请重新登录");
                }
                render();
            });
        }).start();
    }

    // ------------------------------------------------------------------ 各入口

    private void editLlm() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));

        EditText base = new EditText(this);
        styleHint(base, "Base URL");
        base.setText(Prefs.llmBase(this));

        EditText key = new EditText(this);
        styleHint(key, "API Key");
        key.setText(Prefs.llmKey(this));

        EditText model = new EditText(this);
        styleHint(model, "Model name");
        model.setText(Prefs.llmModel(this));

        box.addView(base);
        box.addView(key);
        box.addView(model);

        new AlertDialog.Builder(this)
                .setTitle("大模型设置（OpenAI 兼容接口）")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    Prefs.setLlm(this, base.getText().toString(), key.getText().toString(),
                            model.getText().toString());
                    Logx.i("[设置] 大模型配置已保存：" + model.getText().toString());
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void editGroq() {
        EditText key = new EditText(this);
        styleHint(key, "Groq API Key");
        key.setInputType(InputType.TYPE_CLASS_TEXT);
        key.setText(Prefs.groqKey(this));
        key.setPadding(dp(16), dp(16), dp(16), dp(16));

        new AlertDialog.Builder(this)
                .setTitle("语音转写（可选）")
                .setMessage("没有官方字幕时改用音频转写兜底：匿名下载音频 → Groq 转写 → 大模型分析。需要自备 Groq Key。")
                .setView(key)
                .setPositiveButton("保存", (d, w) -> {
                    Prefs.setGroqKey(this, key.getText().toString());
                    Logx.i("[设置] Groq 密钥已保存");
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 悬浮窗权限：悬浮球没有开关（启用即常驻），但没有这个权限它就无法显示，
     * 进程会掉回 cached 被冻结、跳过失效，所以这里必须引导授权。
     */
    private void requestOverlayPermission() {
        if (FloatingBall.canDraw(this)) {
            Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show();
            Logx.i("[设置] 悬浮窗权限已授予，悬浮球会在离开本软件后自动出现");
            return;
        }
        Toast.makeText(this, "请允许「显示在其他应用上层」，否则后台会被冻结", Toast.LENGTH_LONG).show();
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Logx.i("[设置] 打开悬浮窗权限页失败：" + e.getMessage());
        }
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTI);
        }
    }

    /** 权限按钮自带状态：已授予 → ✓ + 绿色；未授予 → 中性色（点了去申请） */
    private void syncPermBtn(int id, String label, boolean ok) {
        TextView b = findViewById(id);
        b.setText(ok ? "✓ " + label : label);
        b.setTextColor(getColor(ok ? R.color.ok : R.color.text_dim));
    }

    /** 是否已在电池优化白名单里；不在名单里时系统可能限制后台 */
    private boolean batteryWhitelisted() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 使用情况访问：判定"B站 是否在前台"的唯一依据。
     * 没有它就只能靠播放会话反推，用户只是打开 B站 首页、还没点视频时会判不出来（球不出现）。
     */
    private void requestUsageAccess() {
        if (BiliPresence.isGranted(this)) {
            Toast.makeText(this, "使用情况访问已授予", Toast.LENGTH_SHORT).show();
            Logx.i("[设置] 使用情况访问已授予");
            return;
        }
        Toast.makeText(this, "请在列表里找到本应用并允许「使用情况访问」，用于判断 B站 是否在前台",
                Toast.LENGTH_LONG).show();
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (Exception e) {
            Logx.i("[设置] 打开使用情况访问设置页失败：" + e.getMessage());
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception e2) {
                Logx.i("[设置] 也无法打开系统设置：" + e2.getMessage());
            }
        }
    }

    private void requestBatteryWhitelist() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            Logx.i("[设置] 打开电池优化设置页失败，改用通用页面：" + e.getMessage());
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                Logx.i("[设置] 无法打开电池优化设置：" + e2.getMessage());
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTI) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Logx.i(granted ? "[设置] 通知权限已授予" : "[设置] 通知权限被拒绝（前台服务仍会运行）");
            Toast.makeText(this, granted ? "通知权限已授予" : "未授予通知权限，跳过仍可用",
                    Toast.LENGTH_SHORT).show();
            render();
        }
    }

    private int dp(float v) {
        return Math.max(1, Math.round(v * getResources().getDisplayMetrics().density));
    }
}
