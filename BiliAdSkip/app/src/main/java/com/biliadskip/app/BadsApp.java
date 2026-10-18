package com.biliadskip.app;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/**
 * 只在"本应用的界面处于前台"时隐藏悬浮球：
 * 这时进程本身就是前台进程，不存在被系统冻结的问题，球反而会挡住界面内容。
 * 用 Activity 生命周期计数，而不是在每个 Activity 里各写一份 onResume/onPause，
 * 是为了避开 MainActivity → LoginActivity 这类内部跳转时球闪一下。
 */
public class BadsApp extends Application {

    /** 处于 resumed 状态的本应用界面数量 */
    private int resumed;

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity activity) {
                resumed++;
                BiliWatchService.setBallSuppressed(true);
            }

            @Override
            public void onActivityPaused(Activity activity) {
                resumed = Math.max(0, resumed - 1);
                if (resumed == 0) {
                    BiliWatchService.setBallSuppressed(false);
                }
            }

            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            }

            @Override
            public void onActivityStarted(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
            }
        });
    }
}
