package com.biliadskip.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewParent;
import android.widget.ScrollView;

/**
 * 日志容器。
 * 它嵌在页面自己的 ScrollView 里，默认情况下手势会被外层抢走，导致日志区根本滚不动。
 * 这里按方向做手势交接：内层还有余量就自己滚，滚到头再把手势还给外层继续翻页面。
 */
public class LogScrollView extends ScrollView {

    private float downY;

    public LogScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        ViewParent parent = getParent();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = e.getY();
                break;
            case MotionEvent.ACTION_MOVE: {
                // 手指下滑(up=true)是想看更早的日志 → 需要内层还能向上滚；手指上滑同理
                boolean up = e.getY() > downY;
                boolean innerCanScroll = up ? canScrollVertically(-1) : canScrollVertically(1);
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(innerCanScroll);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(false);
                }
                break;
            default:
                break;
        }
        return super.onTouchEvent(e);
    }
}
