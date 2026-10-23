package net.gsantner.markor.frontend;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import net.gsantner.markor.R;

/**
 * 高度随内容走、但不超过上限的 RecyclerView。
 * <p>
 * 标签面板条目数量不定：只有两三个标签时不该留一大块空白，
 * 标签多的时候又不能把编辑区挤没，所以量高度时取 min(内容高度, 上限)。
 */
public class MaxHeightRecyclerView extends RecyclerView {

    private int _maxHeight = Integer.MAX_VALUE;

    public MaxHeightRecyclerView(@NonNull final Context context) {
        super(context);
    }

    public MaxHeightRecyclerView(@NonNull final Context context, @Nullable final AttributeSet attrs) {
        super(context, attrs);
        applyAttrs(context, attrs);
    }

    public MaxHeightRecyclerView(@NonNull final Context context, @Nullable final AttributeSet attrs,
                                 final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        applyAttrs(context, attrs);
    }

    private void applyAttrs(final Context context, @Nullable final AttributeSet attrs) {
        if (attrs == null) {
            return;
        }
        final TypedArray array = context.obtainStyledAttributes(attrs, R.styleable.MaxHeightRecyclerView);
        _maxHeight = array.getDimensionPixelSize(R.styleable.MaxHeightRecyclerView_android_maxHeight, Integer.MAX_VALUE);
        array.recycle();
    }

    @Override
    protected void onMeasure(final int widthSpec, final int heightSpec) {
        final int mode = MeasureSpec.getMode(heightSpec);
        if (mode == MeasureSpec.AT_MOST || mode == MeasureSpec.UNSPECIFIED) {
            final int limit = MeasureSpec.makeMeasureSpec(_maxHeight, MeasureSpec.AT_MOST);
            super.onMeasure(widthSpec, limit);
            return;
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
