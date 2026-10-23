package net.gsantner.markor.frontend;

import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import net.gsantner.markor.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「最近」页的列表：分组头（固定文件 / 最近文件）、已打开标签、最近打开但没开的文件。
 * <p>
 * 只有列表这一种形态 —— 之前那套"列表 / 方块"切换去掉之后，每一行都是一个文件名加几个动作，
 * 眼睛扫一行就能读完，翻页手势也不会跟方块里的横向拖动打架。
 */
public class EditorTabsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int TYPE_HEADER = 0;
    public static final int TYPE_TAB = 1;
    public static final int TYPE_RECENT = 2;

    public static class Item {
        public int type;
        public String path;
        public String title;
        public boolean pinned;
        public boolean dirty;
        public boolean collapsed;
        /** 当前正在编辑的那一个：行会高亮 */
        public boolean selected;
        public int count;
    }

    public interface Listener {
        void onTabClick(@NonNull String path);

        void onTabClose(@NonNull String path);

        /** 点星标：已开着的标签切换固定状态，没开的文件直接"打开并固定" */
        void onTabPin(@NonNull String path, boolean wantPinned);

        void onGroupToggle(boolean pinnedGroup);

        /** 按住行尾的拖动手柄：把这个 viewHolder 交给 ItemTouchHelper 开始上下排序 */
        void onStartDrag(@NonNull RecyclerView.ViewHolder holder);
    }

    private final List<Item> _items = new ArrayList<>();
    private final Listener _listener;

    public EditorTabsAdapter(@NonNull final Listener listener) {
        _listener = listener;
    }

    public void submit(@NonNull final List<Item> items) {
        _items.clear();
        _items.addAll(items);
        notifyDataSetChanged();
    }

    @Nullable
    public Item getItemAt(final int position) {
        return position >= 0 && position < _items.size() ? _items.get(position) : null;
    }

    public int getTypeAt(final int position) {
        final Item item = getItemAt(position);
        return item == null ? TYPE_HEADER : item.type;
    }

    /** 拖动排序：同组内交换两个位置，返回是否真的换了 */
    public boolean swapItems(final int fromPos, final int toPos) {
        final Item from = getItemAt(fromPos);
        final Item to = getItemAt(toPos);
        if (from == null || to == null || from.type != TYPE_TAB || to.type != TYPE_TAB) {
            return false;
        }
        if (from.pinned != to.pinned) {
            // 固定和普通不混在一起排，跨组拖不放行
            return false;
        }
        _items.set(fromPos, to);
        _items.set(toPos, from);
        notifyItemMoved(fromPos, toPos);
        return true;
    }

    /** 只刷新未保存圆点，不动列表顺序（轮询用，避免整表重绘） */
    public void updateDirty(final Map<String, Boolean> dirtyByPath) {
        for (int i = 0; i < _items.size(); i++) {
            final Item item = _items.get(i);
            if (item.path == null || item.type == TYPE_HEADER) {
                continue;
            }
            final Boolean dirty = dirtyByPath.get(item.path);
            final boolean value = dirty != null && dirty;
            if (item.dirty != value) {
                item.dirty = value;
                notifyItemChanged(i);
            }
        }
    }

    /** 高亮"当前正在编辑"的那一项，只重绑受影响的两行 */
    public void select(@Nullable final String path) {
        for (int i = 0; i < _items.size(); i++) {
            final Item item = _items.get(i);
            final boolean selected = item.path != null && item.path.equals(path);
            if (item.selected != selected) {
                item.selected = selected;
                notifyItemChanged(i);
            }
        }
    }

    @Override
    public int getItemViewType(final int position) {
        return getTypeAt(position);
    }

    @Override
    public int getItemCount() {
        return _items.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_TAB || viewType == TYPE_RECENT) {
            return new ItemHolder(inflater.inflate(R.layout.editor__tabs_row, parent, false));
        }
        return new HeaderHolder(inflater.inflate(R.layout.editor__tabs_header, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull final RecyclerView.ViewHolder holder, final int position) {
        final Item item = getItemAt(position);
        if (item == null) {
            return;
        }
        if (holder instanceof HeaderHolder) {
            ((HeaderHolder) holder).bind(item);
        } else if (holder instanceof ItemHolder) {
            ((ItemHolder) holder).bind(item);
        }
    }

    class HeaderHolder extends RecyclerView.ViewHolder {
        private final ImageView _chevron;
        private final TextView _title;
        private final TextView _count;

        HeaderHolder(@NonNull final View view) {
            super(view);
            _chevron = view.findViewById(R.id.editor__header_chevron);
            _title = view.findViewById(R.id.editor__header_title);
            _count = view.findViewById(R.id.editor__header_count);
        }

        void bind(final Item item) {
            final boolean pinnedGroup = item.pinned;
            _title.setText(item.title);
            _count.setText(_count.getContext().getString(R.string.editor_tab_count, item.count));
            // 收起时箭头指向右，展开时转向下
            _chevron.setRotation(item.collapsed ? 0f : 90f);
            itemView.setOnClickListener(v -> _listener.onGroupToggle(pinnedGroup));
        }
    }

    class ItemHolder extends RecyclerView.ViewHolder {
        private final View _dot;
        private final TextView _title;
        private final ImageView _pin;
        private final ImageView _close;
        private final ImageView _drag;

        ItemHolder(@NonNull final View view) {
            super(view);
            _dot = view.findViewById(R.id.editor__row_dot);
            _title = view.findViewById(R.id.editor__row_title);
            _pin = view.findViewById(R.id.editor__row_pin);
            _close = view.findViewById(R.id.editor__row_close);
            _drag = view.findViewById(R.id.editor__row_drag);
        }

        void bind(final Item item) {
            final boolean recent = item.type == TYPE_RECENT;
            _title.setText(item.title);
            _dot.setVisibility(item.dirty ? View.VISIBLE : View.GONE);
            _pin.setImageResource(item.pinned ? R.drawable.ic_star_black_24dp : R.drawable.ic_star_border_black_24dp);
            _pin.setAlpha(item.pinned ? 1f : 0.55f);
            // 没打开的文件没有"关闭"，只有"打开（并固定）"
            _close.setVisibility(recent ? View.GONE : View.VISIBLE);
            _title.setAlpha(recent ? 0.75f : 1f);
            // 只有开着的标签能排序；没开的文件按打开时间排，拖它没有意义
            bindDragHandle(!recent);
            // 当前正在编辑的那一项给个浅底
            itemView.setActivated(item.selected);

            itemView.setOnClickListener(v -> _listener.onTabClick(item.path));
            _pin.setOnClickListener(v -> _listener.onTabPin(item.path, !item.pinned));
            _close.setOnClickListener(v -> _listener.onTabClose(item.path));
        }

        /** 手柄上按一下就开始拖：手指不用先长按，拖起来的方向也只有上下 */
        private void bindDragHandle(final boolean reorderable) {
            if (_drag == null) {
                return;
            }
            _drag.setVisibility(reorderable ? View.VISIBLE : View.GONE);
            if (!reorderable) {
                _drag.setOnTouchListener(null);
                return;
            }
            _drag.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(final View v, final MotionEvent event) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        _listener.onStartDrag(ItemHolder.this);
                    }
                    // 不了结事件：往后的 MOVE/UP 由 ItemTouchHelper 接着处理
                    return false;
                }
            });
        }
    }
}
