package net.gsantner.markor.frontend;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

@SuppressLint("ClickableViewAccessibility")
public class DraggableScrollbarScrollView extends ScrollView {

    /**
     * 滚动位置变化回调（带上一帧位置，方便判断方向）。
     * 直接挂在 onScrollChanged 上，API 21 也能用，不需要 23+ 的 View 监听器。
     */
    public interface OnScrollPositionChangedListener {
        void onScrollPositionChanged(int scrollY, int oldScrollY);
    }

    private OnScrollPositionChangedListener _scrollPositionListener;
    // 记录"手指是否真的按在屏幕上"。程序性滚动（聚焦时把光标滚进视野、恢复上次位置）
    // 不会走触摸链路，所以用它来区分手势滚动与代码滚动
    private boolean _userTouching = false;

    public void setOnScrollPositionChangedListener(final OnScrollPositionChangedListener listener) {
        _scrollPositionListener = listener;
    }

    public boolean isUserTouching() {
        return _userTouching;
    }

    private void trackUserTouch(final MotionEvent ev) {
        final int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            _userTouching = true;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            _userTouching = false;
        }
    }

    @Override
    protected void onScrollChanged(final int l, final int t, final int oldl, final int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (_scrollPositionListener != null) {
            _scrollPositionListener.onScrollPositionChanged(t, oldt);
        }
    }

    private boolean _isFastScrolling = false;
    private boolean _fastScrollEnabled = true;
    private boolean _ltr = true;
    private int _thumbHeight;
    private int _grabWidth;

    public DraggableScrollbarScrollView(Context context) {
        this(context, null);
    }

    public DraggableScrollbarScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public boolean slowScrollShift(final int shift) {
        if (!_isFastScrolling && Math.abs(shift) > 0) {
            setScrollY(getScrollY() + shift);
            return true;
        }
        return false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        trackUserTouch(ev);
        if (!_fastScrollEnabled) {
            return super.onInterceptTouchEvent(ev);
        }
        if (_isFastScrolling) {
            return true;
        }
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN &&
                ((_ltr && getWidth() - _grabWidth < ev.getX())
                        || (!_ltr && _grabWidth > ev.getX()))) {
            computeThumbHeight();
            awakenScrollBars();
            float scrollbarStartPos = (float) computeVerticalScrollOffset() / computeVerticalScrollRange() * (getHeight());
            if (Math.abs(ev.getY() - scrollbarStartPos) < _thumbHeight) {
                _isFastScrolling = true;
            }
            return true;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        trackUserTouch(ev);
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            _isFastScrolling = false;
        }
        if (_isFastScrolling && action == MotionEvent.ACTION_MOVE) {
            smoothScrollTo(0, (int) (((ev.getY() - _thumbHeight / 2) / (getHeight() - _thumbHeight)) * (computeVerticalScrollRange() - computeVerticalScrollExtent())));
            return true;
        }
        return super.onTouchEvent(ev);
    }

    // Approximate height of thumb
    private void computeThumbHeight() {
        int height = (int) ((float) computeVerticalScrollExtent() * getHeight() / computeVerticalScrollRange());
        int minHeight = getHeight() / 8;
        if (height < minHeight) {
            height = minHeight;
        }
        _thumbHeight = height;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setSmoothScrollingEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            _ltr = getLayoutDirection() == View.LAYOUT_DIRECTION_LTR;
        }
        final DisplayMetrics displayMetrics = getContext().getResources().getDisplayMetrics();
        _grabWidth = (int) (1.5 * (float) getVerticalScrollbarWidth() * displayMetrics.density);
    }

    public void setFastScrollEnabled(boolean fastScrollEnabled) {
        _fastScrollEnabled = fastScrollEnabled;
    }

    public boolean isFastScrollEnabled() {
        return _fastScrollEnabled;
    }
}
