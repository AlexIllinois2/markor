package net.gsantner.markor.activity;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import net.gsantner.markor.R;
import net.gsantner.opoc.frontend.base.GsPreferenceFragmentBase;

/**
 * 主界面里的设置页（底部导航第四格）。
 * <p>
 * 设置不再是"另一个 Activity"：左右滑到最后一页就是它，和文件/编辑/最近一起翻，
 * 不再有"滑到这里断了"的割裂感。
 * <p>
 * 这一页在 ViewPager 里是常驻的（offscreenPageLimit = 页数），如果建页时就把
 * 设置项全 inflate 出来，冷启动要白白背这份开销 —— 所以这里只摆一个空容器，
 * 第一帧画完之后（或者用户真的滑过来了）再把第一层设置项建出来。
 * 因为提前建好了，点进来时是现成的，不用等。
 */
public class SettingsFragment extends Fragment implements SettingsHost {

    public static final String TAG = "SettingsFragment";

    private static final int CONTAINER_ID = R.id.settings__page_container;
    private static final String TAG_MASTER = "settings_master";
    /** 主界面首帧之后多久开始预建设置项：躲开启动那一下，又不至于让人等 */
    private static final long PREPARE_DELAY_MS = 700;

    private View _container;
    private String _title;
    private boolean _contentRequested = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater, @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.settings__page, container, false);
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        _container = view.findViewById(CONTAINER_ID);
        if (TextUtils.isEmpty(_title)) {
            _title = getString(R.string.settings);
        }
        if (_container != null) {
            _container.postDelayed(this::prepare, PREPARE_DELAY_MS);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // 预建还没轮到就被滑进来了：当场补上，别让这一页空着
        prepare();
    }

    /**
     * 建第一层设置项。已经建过 / 正在建的话直接返回 —— 反复调用是安全的，
     * 页面回调、onResume、延迟预建都会走到这里。
     */
    public void prepare() {
        if (_contentRequested || !isAdded() || getView() == null) {
            return;
        }
        final FragmentManager fm = getChildFragmentManager();
        if (fm.isStateSaved() || fm.findFragmentById(CONTAINER_ID) != null) {
            return;
        }
        _contentRequested = true;
        fm.beginTransaction()
                .replace(CONTAINER_ID, new SettingsActivity.SettingsFragmentMaster(), TAG_MASTER)
                .commit();
    }

    @Override
    public void showSettingsSubScreen(final int xmlRes, final CharSequence title) {
        if (!isAdded() || getView() == null) {
            return;
        }
        prepare();
        final FragmentManager fm = getChildFragmentManager();
        final String tag = TAG_MASTER + "_" + xmlRes;
        Fragment frag = fm.findFragmentByTag(tag);
        if (!(frag instanceof SettingsActivity.SettingsFragmentMaster)) {
            frag = SettingsActivity.SettingsFragmentMaster.newInstance(xmlRes, title);
        }
        setTitle(title);
        fm.beginTransaction()
                .replace(CONTAINER_ID, frag, tag)
                .addToBackStack(tag)
                .commit();
    }

    /** 标题栏上这一页该写什么：第一层是"设置"，进了二级页就换成二级页的名字 */
    @NonNull
    public String getCurrentTitle() {
        return TextUtils.isEmpty(_title) ? getString(R.string.settings) : _title;
    }

    /** 设置片段内部又钻进了下一层（比如 编辑 → 基础配色）：标题跟着，别停在上一级 */
    void onPreferenceScreenTitle(@Nullable final CharSequence title) {
        if (TextUtils.isEmpty(title)) {
            syncTitleFromCurrentFragment();
        } else {
            setTitle(title);
        }
    }

    /** 返回键：先退二级页，再退片段内部的下一层，都没有才算这一页不处理 */
    public boolean onBackPressed() {
        if (!isAdded()) {
            return false;
        }
        final FragmentManager fm = getChildFragmentManager();
        final Fragment current = fm.findFragmentById(CONTAINER_ID);
        if (current instanceof GsPreferenceFragmentBase
                && ((GsPreferenceFragmentBase<?>) current).canGoBack()) {
            ((GsPreferenceFragmentBase<?>) current).goBack();
            return true;
        }
        if (fm.getBackStackEntryCount() > 0) {
            fm.popBackStackImmediate();
            syncTitleFromCurrentFragment();
            return true;
        }
        return false;
    }

    private void syncTitleFromCurrentFragment() {
        final Fragment current = getChildFragmentManager().findFragmentById(CONTAINER_ID);
        final String fallback = getString(R.string.settings);
        setTitle(current instanceof GsPreferenceFragmentBase
                ? ((GsPreferenceFragmentBase<?>) current).getTitleOrDefault(fallback) : fallback);
    }

    private void setTitle(@Nullable final CharSequence title) {
        _title = TextUtils.isEmpty(title) ? getString(R.string.settings) : title.toString();
        final Activity activity = getActivity();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).onSettingsTitleChanged(_title);
        }
    }
}
