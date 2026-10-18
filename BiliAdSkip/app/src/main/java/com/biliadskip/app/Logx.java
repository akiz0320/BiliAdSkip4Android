package com.biliadskip.app;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 日志：写 logcat（tag=BiliAdSkip，便于 adb 观察）+ 内存缓冲（界面展示） */
public final class Logx {

    public static final String TAG = "BiliAdSkip";

    private static final List<String> LINES = new ArrayList<>();
    private static Runnable onChange;

    private Logx() {
    }

    public static void setOnChange(Runnable r) {
        onChange = r;
    }

    public static synchronized void i(String msg) {
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        LINES.add(stamp + "  " + msg);
        if (LINES.size() > 400) {
            LINES.remove(0);
        }
        Log.i(TAG, msg);
        Runnable r = onChange;
        if (r != null) {
            r.run();
        }
    }

    /** 供界面按级别染色：返回当前日志行的快照 */
    public static synchronized List<String> lines() {
        return new ArrayList<>(LINES);
    }

    public static synchronized void clear() {
        LINES.clear();
    }
}
