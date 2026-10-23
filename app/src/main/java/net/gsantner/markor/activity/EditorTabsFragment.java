package net.gsantner.markor.activity;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.gsantner.markor.R;
import net.gsantner.markor.frontend.EditorTabsAdapter;
import net.gsantner.markor.model.AppSettings;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「最近」页：固定文件 + 最近文件两组列表。
 * <p>
 * 只做展示和转发 —— 点一行就切到「编辑」页去改那个文件；星标切换固定；
 * × 关掉标签（有改动会先问）；拖行尾的手柄在同组内排序。
 * 这里没有"新建文件"：新建只在「文件」页。
 */
public class EditorTabsFragment extends Fragment implements EditorTabsAdapter.Listener {

    public static final String TAG = "EditorTabsFragment";
    private static final int RECENT_MAX = 12;
    private static final long REFRESH_MS = 700;

    private RecyclerView _list;
    private View _empty;
    private EditorTabsAdapter _adapter;
    private AppSettings _appSettings;
    private ItemTouchHelper _touchHelper;
    private boolean _dragActive = false;
    private String _lastSignature = "";

    private final Handler _handler = new Handler(Looper.getMainLooper());
    private final Runnable _poll = new Runnable() {
        @Override
        public void run() {
            refresh(false);
            if (isAdded()) {
                _handler.postDelayed(this, REFRESH_MS);
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater, @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.editor__tabs_page, container, false);
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        final Activity activity = getActivity();
        _appSettings = activity != null ? AppSettings.get(activity) : null;
        _list = view.findViewById(R.id.editor__tabs_list);
        _empty = view.findViewById(R.id.editor__tabs_empty);

        _adapter = new EditorTabsAdapter(this);
        _list.setAdapter(_adapter);
        // 只有列表一种形态：一行一个文件，长得像文件列表，扫一眼就知道是哪个
        _list.setLayoutManager(new LinearLayoutManager(requireContext()));
        // 行高是固定的，别每来一次内容变化就重新量一遍
        _list.setHasFixedSize(true);
        setupDragReorder();
        refresh(true);
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh(true);
        _handler.removeCallbacks(_poll);
        _handler.postDelayed(_poll, REFRESH_MS);
    }

    @Override
    public void onPause() {
        _handler.removeCallbacks(_poll);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        _handler.removeCallbacks(_poll);
        _list = null;
        _empty = null;
        _adapter = null;
        _lastSignature = "";
        super.onDestroyView();
    }

    // -----------------------------------------------------------------
    // 数据
    // -----------------------------------------------------------------

    @Nullable
    private EditorFragment editor() {
        final Activity activity = getActivity();
        return activity instanceof MainActivity ? ((MainActivity) activity).getEditorFragment() : null;
    }

    private List<String> recentNotOpen(final EditorFragment editor) {
        final List<String> out = new ArrayList<>();
        if (_appSettings == null) {
            return out;
        }
        for (final String path : _appSettings.getEditorRecentFiles()) {
            if (out.size() >= RECENT_MAX) {
                break;
            }
            final File file = new File(path);
            final boolean open = editor != null && editor.indexOfPath(path) >= 0;
            if (file.isFile() && !open) {
                out.add(path);
            }
        }
        return out;
    }

    private boolean isGroupCollapsed(final boolean pinnedGroup) {
        return _appSettings != null && _appSettings.isEditorTabsGroupCollapsed(pinnedGroup);
    }

    /** 结构没变就只刷圆点/高亮，变了才整表重建 */
    private void refresh(final boolean force) {
        if (_adapter == null) {
            return;
        }
        // 正在拖动：这一帧一个字节都别碰列表。
        // 轮询是 700ms 一次，只要这期间重绑一次行（notifyDataSetChanged / notifyItemChanged），
        // ItemTouchHelper 手里攥着的那个 viewHolder 就作废了，手感上就是"拖一半自己松开"。
        if (_dragActive) {
            return;
        }
        final EditorFragment editor = editor();
        final List<EditorFragment.Tab> tabs = editor != null ? editor.getTabs() : new ArrayList<>();
        final List<String> recents = recentNotOpen(editor);
        final String current = editor != null && editor.getCurrentFile() != null
                ? editor.getCurrentFile().getAbsolutePath() : null;

        final StringBuilder sig = new StringBuilder();
        sig.append(isGroupCollapsed(true)).append('/').append(isGroupCollapsed(false));
        for (final EditorFragment.Tab tab : tabs) {
            sig.append('|').append(tab.file.getAbsolutePath()).append(tab.pinned ? "*" : "");
        }
        for (final String path : recents) {
            sig.append('~').append(path);
        }
        sig.append('#').append(current);
        final String signature = sig.toString();

        if (!force && signature.equals(_lastSignature)) {
            // 只更新未保存圆点
            final Map<String, Boolean> dirty = new HashMap<>();
            for (final EditorFragment.Tab tab : tabs) {
                dirty.put(tab.file.getAbsolutePath(),
                        editor != null && editor.isDirty(tab.file.getAbsolutePath()));
            }
            _adapter.updateDirty(dirty);
            return;
        }
        _lastSignature = signature;
        rebuild(tabs, recents, current);
    }

    private void rebuild(final List<EditorFragment.Tab> tabs, final List<String> recents, final String currentPath) {
        final List<EditorTabsAdapter.Item> items = new ArrayList<>();
        final List<EditorFragment.Tab> pinnedTabs = new ArrayList<>();
        final List<EditorFragment.Tab> normalTabs = new ArrayList<>();
        for (final EditorFragment.Tab tab : tabs) {
            (tab.pinned ? pinnedTabs : normalTabs).add(tab);
        }

        items.add(headerItem(true, getString(R.string.editor_pinned_files), pinnedTabs.size()));
        if (!isGroupCollapsed(true)) {
            for (final EditorFragment.Tab tab : pinnedTabs) {
                items.add(tabItem(tab, currentPath));
            }
        }

        items.add(headerItem(false, getString(R.string.editor_recent_files), normalTabs.size() + recents.size()));
        if (!isGroupCollapsed(false)) {
            for (final EditorFragment.Tab tab : normalTabs) {
                items.add(tabItem(tab, currentPath));
            }
            for (final String path : recents) {
                final EditorTabsAdapter.Item item = new EditorTabsAdapter.Item();
                item.type = EditorTabsAdapter.TYPE_RECENT;
                item.path = path;
                item.title = new File(path).getName();
                items.add(item);
            }
        }

        _adapter.submit(items);
        final boolean nothing = tabs.isEmpty() && recents.isEmpty();
        if (_empty != null) {
            _empty.setVisibility(nothing ? View.VISIBLE : View.GONE);
        }
        if (_list != null) {
            _list.setVisibility(nothing ? View.GONE : View.VISIBLE);
        }
    }

    private EditorTabsAdapter.Item headerItem(final boolean pinnedGroup, final String title, final int count) {
        final EditorTabsAdapter.Item item = new EditorTabsAdapter.Item();
        item.type = EditorTabsAdapter.TYPE_HEADER;
        item.title = title;
        item.pinned = pinnedGroup;
        item.count = count;
        item.collapsed = isGroupCollapsed(pinnedGroup);
        return item;
    }

    private EditorTabsAdapter.Item tabItem(final EditorFragment.Tab tab, final String currentPath) {
        final EditorTabsAdapter.Item item = new EditorTabsAdapter.Item();
        item.type = EditorTabsAdapter.TYPE_TAB;
        item.path = tab.file.getAbsolutePath();
        item.title = tab.file.getName();
        item.pinned = tab.pinned;
        final EditorFragment editor = editor();
        item.dirty = editor != null && editor.isDirty(item.path);
        item.selected = item.path.equals(currentPath);
        return item;
    }

    // -----------------------------------------------------------------
    // 拖动排序
    // -----------------------------------------------------------------

    private void setupDragReorder() {
        // 只有上下 —— 横向留给翻页，手一歪就换页比换顺序的可能性大得多
        final ItemTouchHelper.SimpleCallback callback = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull final RecyclerView rv, @NonNull final RecyclerView.ViewHolder vh,
                                  @NonNull final RecyclerView.ViewHolder target) {
                // 用绝对下标：拖动过程中列表在动，getAdapterPosition 会因为待处理的更新返回 -1，
                // 那样 onMove 直接返回 false，手感就是"拖到一半粘住了"
                final int from = vh.getAbsoluteAdapterPosition();
                final int to = target.getAbsoluteAdapterPosition();
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
                    return false;
                }
                final EditorTabsAdapter.Item a = _adapter != null ? _adapter.getItemAt(from) : null;
                final EditorTabsAdapter.Item b = _adapter != null ? _adapter.getItemAt(to) : null;
                if (a == null || b == null || a.path == null || b.path == null) {
                    return false;
                }
                if (_adapter.swapItems(from, to)) {
                    final EditorFragment editor = editor();
                    if (editor != null) {
                        editor.swapTabs(a.path, b.path);
                    }
                    return true;
                }
                return false;
            }

            @Override
            public void onSwiped(@NonNull final RecyclerView.ViewHolder vh, final int direction) {
            }

            /** 排序只能从行尾那个手柄起手：点一行是"打开"，长按拖动太容易误触 */
            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }

            @Override
            public void onSelectedChanged(final RecyclerView.ViewHolder vh, final int actionState) {
                super.onSelectedChanged(vh, actionState);
                _dragActive = actionState == ItemTouchHelper.ACTION_STATE_DRAG;
                if (_dragActive && vh != null) {
                    vh.itemView.setAlpha(0.85f);
                }
            }

            @Override
            public void clearView(@NonNull final RecyclerView rv, @NonNull final RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                vh.itemView.setAlpha(1f);
                _dragActive = false;
                _lastSignature = "";
                // 拖动期间一直被跳过，松手后要按新顺序把整表对齐一次。
                // 但 clearView 是在 ItemTouchHelper 的回收动画回调里跑的 —— 那会儿
                // RecyclerView 正在布局，这里 notify 会直接抛
                // "Cannot call this method while RecyclerView is computing a layout"，
                // 所以挂到下一帧再做。
                rv.post(new Runnable() {
                    @Override
                    public void run() {
                        refresh(true);
                    }
                });
            }
        };
        _touchHelper = new ItemTouchHelper(callback);
        _touchHelper.attachToRecyclerView(_list);
    }

    // -----------------------------------------------------------------
    // 交互
    // -----------------------------------------------------------------

    @Override
    public void onTabClick(@NonNull final String path) {
        final Activity activity = getActivity();
        if (activity instanceof MainActivity) {
            // 切到「编辑」页并打开这个文件
            ((MainActivity) activity).openInEditor(new File(path));
        }
    }

    @Override
    public void onTabClose(@NonNull final String path) {
        final EditorFragment editor = editor();
        if (editor != null) {
            editor.requestClose(path);
            refresh(true);
        }
    }

    @Override
    public void onTabPin(@NonNull final String path, final boolean wantPinned) {
        final EditorFragment editor = editor();
        if (editor != null) {
            editor.setPinned(path, wantPinned);
            refresh(true);
        }
    }

    @Override
    public void onGroupToggle(final boolean pinnedGroup) {
        if (_appSettings != null) {
            _appSettings.setEditorTabsGroupCollapsed(pinnedGroup, !isGroupCollapsed(pinnedGroup));
        }
        refresh(true);
    }

    @Override
    public void onStartDrag(@NonNull final RecyclerView.ViewHolder holder) {
        if (_touchHelper != null) {
            _touchHelper.startDrag(holder);
        }
    }
}
