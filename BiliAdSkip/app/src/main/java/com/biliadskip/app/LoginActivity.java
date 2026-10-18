package com.biliadskip.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 内嵌 B站 官方登录页（扫码 / 密码 / 短信 都由 B站 自己的页面处理，含极验滑块）。
 * 登录成功后自动保存 Cookie、自动返回，无需手动确认，也不需要反复登录。
 * 已是登录状态时再进这里＝换号：只有在检测到「别的账号」时才自动返回。
 */
public class LoginActivity extends Activity {

    private static final long POLL_MS = 1500;

    private WebView webView;
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile boolean closed;
    private volatile boolean verifying;
    private long initialMid = -1;

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            if (closed) {
                return;
            }
            checkLogin();
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 便于排查登录页问题（chrome://inspect / CDP），对功能无影响
        WebView.setWebContentsDebuggingEnabled(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        bar.setPadding(pad, pad, pad, 0);

        TextView back = new TextView(this);
        back.setText("← 返回");
        back.setTextSize(13f);
        back.setTextColor(getColor(R.color.text));
        back.setBackgroundResource(R.drawable.btn_ghost);
        back.setPadding(dp(14), dp(9), dp(14), dp(9));
        back.setOnClickListener(v -> exit());

        status = new TextView(this);
        status.setTextSize(12f);
        status.setTextColor(getColor(R.color.text_dim));
        status.setPadding(pad * 2, pad, pad, pad);
        status.setText("登录成功后会自动返回。收不到短信就选「扫描二维码登录」，不需要验证码。");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        status.setLayoutParams(sp);

        bar.addView(back);
        bar.addView(status);

        webView = new WebView(this);
        webView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 15; V2245A) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return blockForeignScheme(String.valueOf(request.getUrl()));
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return blockForeignScheme(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // B站 登录成功后会跳回 www.bilibili.com，这是天然的检测时机
                checkLogin();
            }
        });

        root.addView(bar);
        root.addView(webView);
        setContentView(root);

        webView.loadUrl("https://passport.bilibili.com/login");

        // 登录页的短信倒计时是页面自己的 JS 在计时。
        // 若本进程被系统当成后台 cached 进程冻结，倒计时会直接卡住，
        // 所以登录期间把前台服务拉起来保活（这也是本 App 唯一的常驻手段）。
        try {
            startForegroundService(new Intent(this, BiliWatchService.class));
            Logx.i("[登录] 已拉起前台服务，保证页面计时不被后台冻结");
        } catch (Exception e) {
            Logx.i("[登录] 拉取前台服务失败：" + e.getMessage());
        }
    }

    /** 只让 http/https 在 WebView 内加载，其它 scheme（bilibili://、weixin:// 等）直接忽略，避免报错页 */
    private boolean blockForeignScheme(String url) {
        if (url == null) {
            return true;
        }
        return !(url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        closed = false;
        if (webView != null) {
            webView.resumeTimers();
        }
        // 记录进入时的账号：用于区分「本来就没登录」与「换号」两种情况
        new Thread(() -> {
            String existing = Prefs.cookie(this);
            initialMid = CookieStore.verify(existing);
            if (initialMid > 0) {
                runOnUiThread(() -> status.setText("当前已登录（mid=" + initialMid
                        + "）。如需换号请在下方重新登录；否则直接点返回。"));
            }
        }).start();
        handler.postDelayed(poller, POLL_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(poller);
    }

    @Override
    public void onBackPressed() {
        exit();
    }

    /** 返回前兜底：万一自动检测没触发，这里再存一次 */
    private void exit() {
        if (closed) {
            return;
        }
        closed = true;
        handler.removeCallbacks(poller);
        String cookie = CookieStore.harvest();
        if (cookie.contains("SESSDATA=")) {
            Prefs.saveCookie(this, cookie);
            CookieStore.flush();
            Logx.i("[登录] 退出时保存 Cookie（长度 " + cookie.length() + "）");
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        closed = true;
        handler.removeCallbacks(poller);
        if (webView != null) {
            // WebView 一旦加载过就要占几十 MB 原生内存，登录完就彻底释放，
            // 否则它会跟着常驻的前台服务进程一直留着
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void checkLogin() {
        if (closed || verifying) {
            return;
        }
        String cookie = CookieStore.harvest();
        if (!cookie.contains("SESSDATA=")) {
            return;
        }
        verifying = true;
        status.setText("已发现登录信息，正在校验…");
        new Thread(() -> {
            long mid = CookieStore.verify(cookie);
            runOnUiThread(() -> {
                verifying = false;
                if (closed) {
                    return;
                }
                if (mid <= 0) {
                    status.setText("已读到 Cookie 但校验未通过，请确认页面已显示登录成功。");
                    return;
                }
                if (initialMid > 0 && mid == initialMid) {
                    // 还是同一个账号 → 属于「已登录」，不打断用户
                    status.setText("当前账号已登录（mid=" + mid + "），可直接点返回。");
                    return;
                }
                Prefs.saveCookie(this, cookie);
                CookieStore.flush();
                Logx.i("[登录] 登录成功，已保存 Cookie（mid=" + mid + "，长度 " + cookie.length() + "）");
                Toast.makeText(this, "登录成功，已自动返回", Toast.LENGTH_SHORT).show();
                closed = true;
                handler.removeCallbacks(poller);
                finish();
            });
        }).start();
    }
}
