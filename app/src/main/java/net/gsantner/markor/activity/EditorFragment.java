package net.gsantner.markor.activity;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import net.gsantner.markor.R;
import net.gsantner.markor.model.AppSettings;
import net.gsantner.markor.model.Document;
import net.gsantner.markor.util.MarkorContextUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 编辑页：纯文本编辑区，只显示"当前那个文件"。
 * <p>
 * 这一页不放标签条、不放文件列表、也不能新建文件 —— 换文件去「最近」页点，
 * 新建文件在「文件」页做。多个已打开的文件在这一页内部用 hide/show 保活，
 * 所以切来切去时光标位置、滚动位置、没保存的文字都还在。
 * 标签的数据（顺序、固定、未保存）也放在这里，「最近」页只负责展示和转发操作。
 */
public class EditorFragment extends Fragment {

    public static final String TAG = "EditorFragment";
    private static final String TAB_TAG_PREFIX = "editor_tab_";
    private static final int MAX_TABS = 8;

    /** 一个打开的文件 = 一个标签 */
    public static class Tab {
        public final File file;
        public boolean pinned;

        Tab(final File file, final boolean pinned) {
            this.file = file;
            this.pinned = pinned;
        }

        String persist() {
            return (pinned ? "1" : "0") + "|" + file.getAbsolutePath();
        }
    }

    private final List<Tab> _tabs = new ArrayList<>();
    private int _current = -1;

    private AppSettings _appSettings;
    private MarkorContextUtils _cu;
    private FrameLayout _content;
    private View _empty;
    private boolean _startupApplied = false;
    // 这一页当前是否在前台（ViewPager 切到别的页时，标签里的撤销/搜索等菜单要收起来）
    private boolean _pageVisible = true;

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Activity activity = getActivity();
        if (activity != null) {
            _appSettings = AppSettings.get(activity);
            _cu = new MarkorContextUtils(activity);
        }
        if (savedInstanceState == null) {
            restoreTabs();
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater, @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.editor__editor, container, false);
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        _content = view.findViewById(R.id.editor__content);
        _empty = view.findViewById(R.id.editor__empty);

        if (_tabs.isEmpty()) {
            _current = -1;
            updateEmptyState();
        } else {
            if (_current < 0 || _current >= _tabs.size()) {
                _current = 0;
            }
            showTab(_current);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!_startupApplied) {
            _startupApplied = true;
            applyStartupMode();
        }
    }

    @Override
    public void onDestroyView() {
        _content = null;
        _empty = null;
        super.onDestroyView();
    }

    // -----------------------------------------------------------------
    // 对外（「最近」页 / MainActivity 用）
    // -----------------------------------------------------------------

    @NonNull
    public List<Tab> getTabs() {
        return _tabs;
    }

    public File getCurrentFile() {
        return _current >= 0 && _current < _tabs.size() ? _tabs.get(_current).file : null;
    }

    public boolean hasOpenTabs() {
        return !_tabs.isEmpty();
    }

    public int indexOfPath(final String path) {
        if (path == null) {
            return -1;
        }
        for (int i = 0; i < _tabs.size(); i++) {
            if (_tabs.get(i).file.getAbsolutePath().equals(path)) {
                return i;
            }
        }
        return -1;
    }

    public boolean isDirty(final String path) {
        final int index = indexOfPath(path);
        return index >= 0 && isDirty(_tabs.get(index));
    }

    /** 打开一个文件：已经开着就切过去，否则新建一个标签 */
    public void openFile(final File file) {
        if (file == null || !file.isFile()) {
            return;
        }
        int index = indexOfPath(file.getAbsolutePath());
        if (index < 0) {
            if (_tabs.size() >= MAX_TABS) {
                Toast.makeText(requireContext(), getString(R.string.editor_tab_limit_reached, MAX_TABS), Toast.LENGTH_SHORT).show();
                return;
            }
            _tabs.add(new Tab(file, false));
            index = _tabs.size() - 1;
            saveTabs();
        }
        showTab(index);
    }

    /** 关掉一个标签；有未保存的改动会先问一句 */
    public void requestClose(final String path) {
        final int index = indexOfPath(path);
        if (index < 0) {
            return;
        }
        final Tab tab = _tabs.get(index);
        final DocumentEditAndViewFragment frag = fragmentFor(tab);
        if (isDirty(tab) && frag != null) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.editor_unsaved_title)
                    .setMessage(getString(R.string.editor_unsaved_message, tab.file.getName()))
                    .setPositiveButton(R.string.save, (d, w) -> {
                        frag.saveDocument(false);
                        removeTab(index);
                    })
                    .setNegativeButton(R.string.editor_discard, (d, w) -> {
                        // 编辑器在 onPause 会自动保存，先把内容退回磁盘版本再移除，才是真的"不保存"
                        frag.discardUnsavedChanges();
                        removeTab(index);
                    })
                    .setNeutralButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        removeTab(index);
    }

    /** 固定 / 取消固定；文件没开着的话顺手打开并固定 */
    public void setPinned(final String path, final boolean pinned) {
        int index = indexOfPath(path);
        if (index < 0) {
            if (!pinned) {
                return;
            }
            openFile(new File(path));
            index = indexOfPath(path);
            if (index < 0) {
                return;
            }
        }
        final Tab tab = _tabs.get(index);
        if (tab.pinned == pinned) {
            showTab(index);
            return;
        }
        _tabs.remove(index);
        tab.pinned = pinned;
        // 固定的排在最前面，保持原有相对顺序
        int insertAt = pinned ? 0 : _tabs.size();
        if (pinned) {
            while (insertAt < _tabs.size() && _tabs.get(insertAt).pinned) {
                insertAt++;
            }
        }
        _tabs.add(insertAt, tab);
        _current = insertAt;
        saveTabs();
        showTab(insertAt);
    }

    /** 拖动排序：把两个已打开的标签对调（同组内） */
    public void swapTabs(final String pathA, final String pathB) {
        final int a = indexOfPath(pathA);
        final int b = indexOfPath(pathB);
        if (a < 0 || b < 0 || a == b) {
            return;
        }
        final Tab tmp = _tabs.get(a);
        _tabs.set(a, _tabs.get(b));
        _tabs.set(b, tmp);
        // 只换顺序，不换"当前"，重新定位一下当前标签的新下标
        final String currentPath = getCurrentFile() != null ? getCurrentFile().getAbsolutePath() : null;
        if (currentPath != null) {
            final int idx = indexOfPath(currentPath);
            if (idx >= 0) {
                _current = idx;
            }
        }
        saveTabs();
    }

    public void hideKeyboard() {
        final DocumentEditAndViewFragment frag = currentFragment();
        if (frag != null && _cu != null) {
            _cu.showSoftKeyboard(getActivity(), false, frag.getEditor());
        }
    }

    /**
     * ViewPager 切页时由 MainActivity 通知。编辑页在后台时，里面的编辑器仍然 attach 着，
     * 工具栏会一直合并它的撤销/保存/搜索菜单 —— 切到「最近」页还挂着这些按钮就很怪。
     */
    public void setPageVisible(final boolean visible) {
        _pageVisible = visible;
        final DocumentEditAndViewFragment frag = currentFragment();
        if (frag != null) {
            frag.setMenuVisibility(visible);
        }
        final Activity activity = getActivity();
        if (activity != null) {
            activity.invalidateOptionsMenu();
        }
    }

    // -----------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------

    private void applyStartupMode() {
        final int mode = _appSettings != null ? _appSettings.getEditorStartupMode() : 0;
        if (mode == 2) {
            final String last = _appSettings.getEditorLastFile();
            final File file = last != null && !last.isEmpty() ? new File(last) : null;
            if (file != null && file.isFile()) {
                openFile(file);
                return;
            }
        }
        if (mode == 1) {
            for (int i = 0; i < _tabs.size(); i++) {
                if (_tabs.get(i).pinned) {
                    showTab(i);
                    return;
                }
            }
        }
        if (_current >= 0) {
            showTab(_current);
        }
    }

    private void showTab(final int index) {
        if (index < 0 || index >= _tabs.size()) {
            return;
        }
        final Tab target = _tabs.get(index);
        _current = index;
        if (_appSettings != null) {
            _appSettings.setEditorLastFile(target.file.getAbsolutePath());
            _appSettings.addEditorRecentFile(target.file.getAbsolutePath());
        }
        final Activity activity = getActivity();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).onEditorTabChanged(target.file);
        }
        if (activity != null) {
            activity.invalidateOptionsMenu();
        }

        if (_content == null) {
            // 视图还没建出来（例如刚从文件页点进来），等 onViewCreated 再挂
            return;
        }
        final FragmentTransaction ft = getChildFragmentManager().beginTransaction();
        for (int i = 0; i < _tabs.size(); i++) {
            final Fragment existing = getChildFragmentManager().findFragmentByTag(tabTag(_tabs.get(i)));
            if (existing instanceof DocumentEditAndViewFragment) {
                if (i != index && !existing.isHidden()) {
                    ft.hide(existing);
                }
                existing.setMenuVisibility(_pageVisible && i == index);
            }
        }
        DocumentEditAndViewFragment frag = (DocumentEditAndViewFragment)
                getChildFragmentManager().findFragmentByTag(tabTag(target));
        if (frag == null) {
            frag = DocumentEditAndViewFragment.newInstance(new Document(target.file), -1, false);
            ft.add(R.id.editor__content, frag, tabTag(target));
        } else if (frag.isHidden()) {
            ft.show(frag);
        }
        frag.setMenuVisibility(_pageVisible);
        ft.commitAllowingStateLoss();

        updateEmptyState();
    }

    private void removeTab(final int index) {
        if (index < 0 || index >= _tabs.size()) {
            return;
        }
        final Tab tab = _tabs.remove(index);
        final Fragment frag = getChildFragmentManager().findFragmentByTag(tabTag(tab));
        if (frag != null) {
            getChildFragmentManager().beginTransaction().remove(frag).commitAllowingStateLoss();
        }
        if (_current == index) {
            _current = Math.min(index, _tabs.size() - 1);
        } else if (_current > index) {
            _current--;
        }
        saveTabs();
        if (_current >= 0 && _current < _tabs.size()) {
            showTab(_current);
        } else {
            _current = -1;
            updateEmptyState();
            final Activity activity = getActivity();
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).onEditorTabChanged(null);
            }
        }
    }

    private void updateEmptyState() {
        final boolean empty = _tabs.isEmpty();
        if (_empty != null) {
            _empty.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        if (_content != null) {
            _content.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    private String tabTag(final Tab tab) {
        return TAB_TAG_PREFIX + tab.file.getAbsolutePath().hashCode();
    }

    private DocumentEditAndViewFragment currentFragment() {
        if (_current < 0 || _current >= _tabs.size()) {
            return null;
        }
        return fragmentFor(_tabs.get(_current));
    }

    private DocumentEditAndViewFragment fragmentFor(final Tab tab) {
        final Fragment frag = getChildFragmentManager().findFragmentByTag(tabTag(tab));
        return frag instanceof DocumentEditAndViewFragment ? (DocumentEditAndViewFragment) frag : null;
    }

    private boolean isDirty(final Tab tab) {
        final DocumentEditAndViewFragment frag = fragmentFor(tab);
        return frag != null && frag.getDocument() != null && frag.getEditor() != null
                && !frag.getDocument().isContentSame(frag.getEditor().getText());
    }

    private void restoreTabs() {
        if (_appSettings == null) {
            return;
        }
        for (final String entry : _appSettings.getEditorOpenTabs()) {
            final int sep = entry.indexOf('|');
            if (sep <= 0) {
                continue;
            }
            final boolean pinned = "1".equals(entry.substring(0, sep));
            final File file = new File(entry.substring(sep + 1));
            if (file.isFile() && indexOfPath(file.getAbsolutePath()) < 0) {
                _tabs.add(new Tab(file, pinned));
            }
        }
        if (!_tabs.isEmpty()) {
            _current = 0;
        }
    }

    private void saveTabs() {
        if (_appSettings == null) {
            return;
        }
        final List<String> out = new ArrayList<>();
        for (final Tab tab : _tabs) {
            out.add(tab.persist());
        }
        _appSettings.setEditorOpenTabs(out);
    }
}
