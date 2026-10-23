/*#######################################################
 *
 *
 *   Maintained 2017-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.activity;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.inputmethod.EditorInfo;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import net.gsantner.markor.BuildConfig;
import net.gsantner.markor.R;
import net.gsantner.markor.format.FormatRegistry;
import net.gsantner.markor.frontend.NewFileDialog;
import net.gsantner.markor.frontend.MarkorDialogFactory;
import net.gsantner.markor.frontend.filebrowser.MarkorFileBrowserFactory;
import net.gsantner.markor.model.Document;
import net.gsantner.markor.model.git.GitNotebook;
import net.gsantner.markor.util.MarkorContextUtils;
import net.gsantner.markor.widget.TodoWidgetProvider;
import net.gsantner.opoc.format.GsSimpleMarkdownParser;
import net.gsantner.opoc.frontend.base.GsFragmentBase;
import net.gsantner.opoc.frontend.filebrowser.GsFileBrowserFragment;
import net.gsantner.opoc.frontend.filebrowser.GsFileBrowserListAdapter;
import net.gsantner.opoc.frontend.filebrowser.GsFileBrowserOptions;
import net.gsantner.opoc.util.GsContextUtils;
import net.gsantner.opoc.util.GsFileUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.TimeUnit;

import other.writeily.widget.WrMarkorWidgetProvider;

public class MainActivity extends MarkorBaseActivity implements GsFileBrowserFragment.FilesystemFragmentOptionsListener, GitNotebook.Ui {

    public static boolean IS_DEBUG_ENABLED = false;

    /**
     * 底部导航有 4 个入口，四个都是页面：文件 / 编辑 / 最近 / 设置。
     * 设置也在 ViewPager 里 —— 左右滑动到最后就是它，不用"跳出去"再回来。
     */
    private static final int PAGE_COUNT = 4;

    /** 手指在标题栏上横向滑多远才开始翻页（dp） */
    private static final int SWIPE_SLOP_DP = 24;

    /**
     * 设置页分出去之后，外部（系统"应用设置"、深链）仍然可以带这个 extra 启动 MainActivity，
     * 落在对应页上。
     */
    public static final String EXTRA_TAB_ID = "extra_tab_id";

    private BottomNavigationView _bottomNav;
    private ViewPager2 _viewPager;
    private View _appBar;
    private int _pagerTopMargin = 0;
    private SectionsPagerAdapter _sectionsAdapter;
    private GsFileBrowserFragment _notebook;
    private EditorFragment _editor;
    private EditorTabsFragment _tabsPage;
    private SettingsFragment _settingsPage;
    private FloatingActionButton _fab;
    // 文件页常驻的搜索行（工具栏只在选中条目时才出现）
    private View _toolbar, _fileHeader, _searchClear;
    private TextView _fileHeaderTitle;
    private EditText _searchInput;

    private MarkorContextUtils _cu;
    private boolean _gitOpenedNotified = false;
    private File _quickSwitchPrevFolder = null;
    private File _startFolder = null, _showFile = null;
    // 从别处（如独立设置页）带着标签 id 跳过来时，要落在哪一页；-1 表示没特殊要求
    private int _startTabOverride = -1;
    // 编辑时向上滑是否已把底部导航收起来
    private boolean _navAutoHidden = false;
    private boolean _keyboardShown = false;
    // 手势跟踪（收放底栏 / 标题栏上翻页）
    private float _swipeDownX = 0f, _swipeDownY = 0f;
    private boolean _swipeTracked = false;
    private boolean _swipeStartedInAppBar = false;
    private boolean _pageSwitchedBySwipe = false;
    private static final int NAV_AUTO_HIDE_DURATION = 220;

    @SuppressLint("SdCardPath")
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        IS_DEBUG_ENABLED |= BuildConfig.IS_TEST_BUILD;

        try {
            //noinspection ResultOfMethodCallIgnored
            _appSettings.getNotebookDirectory().mkdirs();
        } catch (Exception ignored) {
        }

        _cu = new MarkorContextUtils(this);
        setContentView(R.layout.main__activity);
        _bottomNav = findViewById(R.id.bottom_navigation_bar);
        _viewPager = findViewById(R.id.main__view_pager_container);
        _appBar = findViewById(R.id.appbar);
        _pagerTopMargin = ((ViewGroup.MarginLayoutParams) _viewPager.getLayoutParams()).topMargin;
        _fab = findViewById(R.id.fab_add_new_item);
        _fab.setOnClickListener(this::onClickFab);
        _fab.setOnLongClickListener(this::onLongClickFab);
        _viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                onViewPagerPageSelected(position);
            }
        });

        setSupportActionBar(findViewById(R.id.toolbar));
        _toolbar = findViewById(R.id.toolbar);
        // 标题只承担"我在哪个目录"这一个信息：根目录就是应用名，重复且被要求去掉，
        // 所以根目录返回空串，进了子目录才出现目录名。
        setToolbarTitleEnabled(true);
        setupFileHeader();
        setupKeyboardChromeAutoHide();
        optShowRate();

        // Setup viewpager
        _sectionsAdapter = new SectionsPagerAdapter(getSupportFragmentManager());
        _viewPager.setAdapter(_sectionsAdapter);
        // Keep created fragments alive, but only realize them once the user visits.
        _viewPager.setOffscreenPageLimit(PAGE_COUNT);
        _bottomNav.setOnItemSelectedListener((item) -> {
            final int pos = tabIdToPos(item.getItemId());
            if (pos < 0) {
                return false;
            }
            bindFragmentRef(pos);
            _viewPager.setCurrentItem(pos);
            return true;
        });

        reduceViewpagerSwipeSensitivity();
        // 文件页是首屏，onPageSelected 不一定来，这里主动挂一次列表滚动监听
        _viewPager.post(this::setupFileListChromeAutoHide);

        // noinspection PointlessBooleanExpression - Send Test intent
        if (BuildConfig.IS_TEST_BUILD && false) {
            DocumentActivity.launch(this, new File("/sdcard/Documents/mordor/aa-beamer.md"), true, null);
        }

        _cu.applySpecialLaunchersVisibility(this, _appSettings.isSpecialFileLaunchersEnabled());

        // Determine start folder —— 文件页只能在笔记本里活动，"起始文件夹"这个设置已经没有意义，
        // 直接固定成笔记本根；带进来的 intent 路径如果落在笔记本外面，会在下面被拉回根目录
        final File fallback = _appSettings.getNotebookDirectory();
        final Intent intent = getIntent();
        _startFolder = MarkorContextUtils.getValidIntentFile(intent, fallback);
        if (!GsFileUtils.isDirectory(_startFolder)) {
            _showFile = _startFolder;
            _startFolder = _startFolder.getParentFile();
        }
        if (!GsFileUtils.isDirectory(_startFolder)) {
            _startFolder = _appSettings.getNotebookDirectory();
        }
        // 文件页只能在笔记本里面活动：起始目录、要定位的文件一旦落在外面，直接回到笔记本根
        final File notebookDir = _appSettings.getNotebookDirectory();
        if (!isInsideNotebook(notebookDir, _startFolder)) {
            _startFolder = notebookDir;
            _showFile = null;
        } else if (_showFile != null && !isInsideNotebook(notebookDir, _showFile)) {
            _showFile = null;
        }

        // 从设置页的底部导航过来时，记住要落到哪一页（消费一次，避免重建时又跳）
        final int tabExtra = intent.getIntExtra(EXTRA_TAB_ID, 0);
        if (tabExtra != 0) {
            intent.removeExtra(EXTRA_TAB_ID);
            _startTabOverride = tabIdToPos(tabExtra);
        }
    }

    private static boolean isInsideNotebook(final File notebook, final File file) {
        return notebook != null && file != null
                && (notebook.equals(file) || GsFileUtils.isChild(notebook, file));
    }

    /**
     * 一个手势要在两件事里分工：竖着划收放底栏，横着划（而且从标题栏起手）翻页。
     * <p>
     * 竖向上滑一律收起底栏、下滑一律放回来 —— 不依赖页面内容是否真的能滚动。
     * 编辑器内容没满屏时 ScrollView 不会产生滚动回调，只靠那边的监听会漏掉手势。
     * <p>
     * 横向翻页平时由 ViewPager2 处理，但标题栏在它上面，手势根本到不了那一页；
     * 所以这里补一条：起手点在标题栏、位移明显偏横向，就直接翻一页（带翻页动画）。
     */
    private void trackVerticalSwipeForNav(final MotionEvent ev) {
        if (_bottomNav == null || _viewPager == null) {
            return;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                _swipeDownX = ev.getX();
                _swipeDownY = ev.getY();
                _swipeTracked = false;
                _pageSwitchedBySwipe = false;
                if (_appBar != null && _appBar.getVisibility() == View.VISIBLE) {
                    // 手势坐标是窗口坐标，标题栏的位置也要按窗口坐标取，否则状态栏高度会把界线算歪
                    final int[] location = new int[2];
                    _appBar.getLocationInWindow(location);
                    _swipeStartedInAppBar = ev.getY() >= location[1]
                            && ev.getY() <= location[1] + _appBar.getHeight();
                } else {
                    _swipeStartedInAppBar = false;
                }
                break;
            case MotionEvent.ACTION_MOVE: {
                final float dy = ev.getY() - _swipeDownY;
                final float dx = ev.getX() - _swipeDownX;
                final float slop = SWIPE_SLOP_DP * getResources().getDisplayMetrics().density;
                final boolean horizontal = Math.abs(dx) > Math.abs(dy) * 1.5f;

                // 标题栏上横向划一格 = 翻一页。一个手势只翻一页，划得再长也不会连跳
                if (_swipeStartedInAppBar && !_pageSwitchedBySwipe
                        && horizontal && Math.abs(dx) > slop) {
                    _pageSwitchedBySwipe = true;
                    switchPageBySwipe(dx < 0 ? 1 : -1);
                }

                if (_swipeTracked || Math.abs(dy) < slop) {
                    return;
                }
                // 以横向为主的手势是翻页，不插手
                if (horizontal) {
                    return;
                }
                _swipeTracked = true;
                if (dy < 0) {
                    setBottomNavAutoHidden(true);
                    // 键盘还开着就一起收，正文才能铺满
                    if (_keyboardShown) {
                        hideKeyboard();
                    }
                } else {
                    setBottomNavAutoHidden(false);
                }
                break;
            }
            default:
                _swipeStartedInAppBar = false;
                break;
        }
    }

    /** 标题栏上左右划：往相邻的一页走，越界就不动 */
    private void switchPageBySwipe(final int delta) {
        if (_sectionsAdapter == null) {
            return;
        }
        final int current = _viewPager.getCurrentItem();
        final int target = Math.max(0, Math.min(_sectionsAdapter.getItemCount() - 1, current + delta));
        if (target != current) {
            _viewPager.setCurrentItem(target, true);
        }
    }

    @Override
    public boolean dispatchTouchEvent(final MotionEvent ev) {
        trackVerticalSwipeForNav(ev);
        return super.dispatchTouchEvent(ev);
    }

    /**
     * 编辑器里向上滑时把底部导航滑出屏幕，正文顺势铺到屏幕底；
     * 向下滑再把它请回来。只有正在编辑的页面会调用，切页时一律复位。
     */
    public void setBottomNavAutoHidden(final boolean hidden) {
        if (_bottomNav == null || _viewPager == null || hidden == _navAutoHidden) {
            return;
        }
        _navAutoHidden = hidden;

        final int space = getResources().getDimensionPixelSize(R.dimen.bottom_nav_space);
        final int marginBottom = getResources().getDimensionPixelSize(R.dimen.bottom_nav_margin_bottom);
        final int navHeight = _bottomNav.getHeight() > 0 ? _bottomNav.getHeight() : space;
        // 多给 8dp，保证整块（含底部外边距和投影）完全离开可视区
        final int overshoot = (int) (8 * getResources().getDisplayMetrics().density);
        final float targetY = hidden ? navHeight + marginBottom + overshoot : 0f;

        _bottomNav.animate()
                .translationY(targetY)
                .setDuration(NAV_AUTO_HIDE_DURATION)
                .start();
        animatePagerBottomSpace(hidden ? 0 : space);
    }

    /** 底部留白跟着导航一起收放，正文才不会在下面留一条空带 */
    private void animatePagerBottomSpace(final int targetPx) {
        final ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) _viewPager.getLayoutParams();
        final int from = lp.bottomMargin;
        if (from == targetPx) {
            return;
        }
        final ValueAnimator animator = ValueAnimator.ofInt(from, targetPx);
        animator.addUpdateListener(a -> {
            lp.bottomMargin = (int) a.getAnimatedValue();
            _viewPager.setLayoutParams(lp);
        });
        animator.setDuration(NAV_AUTO_HIDE_DURATION).start();
    }

    @Override
    public void onActivityFirstTimeVisible() {
        super.onActivityFirstTimeVisible();
        // Switch to tab if specific folder _not_ requested, and not recreating from saved instance
        final int startTab = _appSettings.getAppStartupTab();
        final int startPos = _startTabOverride > 0 ? _startTabOverride : tabIdToPos(startTab);
        if (startPos > 0 && MarkorContextUtils.getValidIntentFile(getIntent(), null) == null) {
            bindFragmentRef(startPos);
            _viewPager.postDelayed(() -> _viewPager.setCurrentItem(startPos), 100);
        }
    }

    /**
     * 文件页顶部的常驻行：目录名 + 搜索框 + 排序。搜索按钮不是"点开一个弹窗"，
     * 而是直接过滤当前目录的列表；回车仍然走原来的全局文件搜索。
     */
    private void setupFileHeader() {
        _fileHeader = findViewById(R.id.file_header);
        _fileHeaderTitle = findViewById(R.id.file_header_title);
        _searchInput = findViewById(R.id.file_search_input);
        _searchClear = findViewById(R.id.file_search_clear);

        if (_searchInput != null) {
            _searchInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    applySearchFilter(s == null ? "" : s.toString());
                }
            });
            _searchInput.setOnEditorActionListener((v, actionId, event) -> {
                // 回车 = 在整个笔记本里搜，跟搜索框的"就地过滤"分工
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    forwardToFileBrowser(R.id.action_search);
                    return true;
                }
                return false;
            });
        }

        if (_searchClear != null) {
            _searchClear.setOnClickListener(v -> {
                if (_searchInput != null) {
                    _searchInput.setText("");
                }
            });
        }

        final View sort = findViewById(R.id.file_sort);
        if (sort != null) {
            sort.setOnClickListener(v -> forwardToFileBrowser(R.id.action_sort));
        }
    }

    /** 搜索行里的控件没有自己的 MenuItem，借工具栏菜单里的同名条目把动作转给文件页 */
    private void forwardToFileBrowser(final int actionId) {
        if (_notebook == null || !(_toolbar instanceof Toolbar)) {
            return;
        }
        final MenuItem item = ((Toolbar) _toolbar).getMenu().findItem(actionId);
        if (item != null) {
            _notebook.onOptionsItemSelected(item);
        }
    }

    private void applySearchFilter(final String text) {
        if (_searchClear != null) {
            _searchClear.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
        }
        if (_notebook != null && _notebook.getAdapter() != null) {
            _notebook.getAdapter().getFilter().filter(text);
        }
    }

    /**
     * 同一格里两套头部：文件页平时显示搜索行，选中条目后换成工具栏（选择计数 + 动作），
     * 其它页面永远用工具栏。
     */
    private void updateHeaderVisibility() {
        if (_fileHeader == null || _toolbar == null || _viewPager == null) {
            return;
        }
        final boolean onFiles = _viewPager.getCurrentItem() == tabIdToPos(R.id.nav_notebook);
        final boolean selecting = onFiles && _notebook != null && _notebook.getAdapter() != null
                && _notebook.getAdapter().areItemsSelected();

        _fileHeader.setVisibility(onFiles && !selecting ? View.VISIBLE : View.GONE);
        _toolbar.setVisibility(onFiles && !selecting ? View.GONE : View.VISIBLE);
    }

    @Override
    public Integer getNewNavigationBarColor() {
        return ContextCompat.getColor(this, R.color.background);
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);

        // Save references to fragments
        try {
            final FragmentManager manager = getSupportFragmentManager();
            // Put and get notebook first. Most important for correct operation.
            manager.putFragment(outState, Integer.toString(R.id.nav_notebook), _notebook);
            if (_editor != null) {
                manager.putFragment(outState, Integer.toString(R.id.nav_editor), _editor);
            }
            if (_tabsPage != null) {
                manager.putFragment(outState, Integer.toString(R.id.nav_tabs), _tabsPage);
            }
            if (_settingsPage != null) {
                manager.putFragment(outState, Integer.toString(R.id.nav_settings), _settingsPage);
            }
        } catch (NullPointerException | IllegalStateException ignored) {
            Log.d(MainActivity.class.getName(), "Child fragments null in onSaveInstanceState()");
        }
    }

    @Override
    public void onRestoreInstanceState(final Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        if (savedInstanceState == null) {
            return;
        }

        // Get back references to fragments
        try {
            final FragmentManager manager = getSupportFragmentManager();
            _notebook = (GsFileBrowserFragment) manager.getFragment(savedInstanceState, Integer.toString(R.id.nav_notebook));
            _editor = (EditorFragment) manager.getFragment(savedInstanceState, Integer.toString(R.id.nav_editor));
            _tabsPage = (EditorTabsFragment) manager.getFragment(savedInstanceState, Integer.toString(R.id.nav_tabs));
            _settingsPage = (SettingsFragment) manager.getFragment(savedInstanceState, Integer.toString(R.id.nav_settings));

            final NewFileDialog nf = (NewFileDialog) manager.findFragmentByTag(NewFileDialog.FRAGMENT_TAG);
            if (nf != null) {
                nf.setCallback(this::newItemCallback);
            }

        } catch (NullPointerException | IllegalStateException ignored) {
            Log.d(MainActivity.class.getName(), "Child fragment not found in onRestoreInstanceState()");
        }
    }

    // Reduces swipe sensitivity
    // Inspired by https://stackoverflow.com/a/72067439
    private void reduceViewpagerSwipeSensitivity() {
        final int SLOP_MULTIPLIER = 4;
        try {
            final Field ff = ViewPager2.class.getDeclaredField("mRecyclerView");
            ff.setAccessible(true);
            final RecyclerView recyclerView = (RecyclerView) ff.get(_viewPager);
            // Set a constant so we don't continuously reduce this value with every call
            recyclerView.setScrollingTouchSlop(RecyclerView.TOUCH_SLOP_PAGING);
            final Field touchSlopField = RecyclerView.class.getDeclaredField("mTouchSlop");
            touchSlopField.setAccessible(true);
            final int touchSlop = (int) touchSlopField.get(recyclerView);
            touchSlopField.set(recyclerView, touchSlop * SLOP_MULTIPLIER);
        } catch (Exception e) {
            Log.d(MainActivity.class.getName(), e.getMessage());
        }
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);

        // 设置页底部导航点回来的情况：带 EXTRA_TAB_ID，切到对应页即可
        final int tabId = intent.getIntExtra(EXTRA_TAB_ID, 0);
        if (tabId != 0) {
            final int tabPos = tabIdToPos(tabId);
            if (tabPos >= 0) {
                bindFragmentRef(tabPos);
                _viewPager.setCurrentItem(tabPos, false);
            }
        }

        final File file = MarkorContextUtils.getValidIntentFile(intent, null);
        if (_notebook != null && file != null) {
            hideKeyboard();
            _viewPager.setCurrentItem(tabIdToPos(R.id.nav_notebook), false);
            if (GsFileUtils.isDirectory(file)) {
                _notebook.getAdapter().setCurrentFolder(file);
            } else {
                _notebook.getAdapter().showFile(file);
            }
            _notebook.setReloadRequiredOnResume(false);
        }
    }

    public static void launch(final Activity activity, final File file, final boolean finishfromActivity) {
        if (activity != null && file != null) {
            final Intent intent = new Intent(activity, MainActivity.class);
            intent.putExtra(Document.EXTRA_FILE, file);
            // intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            GsContextUtils.instance.animateToActivity(activity, intent, finishfromActivity, null);
        }
    }

    private void optShowRate() {
        try {
            new com.pixplicity.generate.Rate.Builder(this)
                    .setTriggerCount(4)
                    .setMinimumInstallTime((int) TimeUnit.MINUTES.toMillis(30))
                    .setFeedbackAction(() -> _cu.showGooglePlayEntryForThisApp(MainActivity.this))
                    .build().count().showRequest();
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        super.onOptionsItemSelected(item);
        if (item.getItemId() == R.id.action_settings) {
            // 设置就是最后一页，点菜单等于翻到那一页（工具栏里这一项默认是隐藏的）
            _viewPager.setCurrentItem(tabIdToPos(R.id.nav_settings));
            return true;
        }
        return false;
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.main__menu, menu);
        // 设置已经是底部导航上的一个入口，工具栏里不再重复一份
        menu.findItem(R.id.action_settings).setVisible(false);

        _cu.tintMenuItems(menu, true, _cu.rcolor(this, R.color.primary_text));
        _cu.setSubMenuIconsVisibility(menu, true);
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!IntroActivity.isFirstStart(this)) {
            StoragePermissionActivity.requestPermissions(this);
        }

        if (_appSettings.isRecreateMainRequired()) {
            // recreate(); // does not remake fragments
            final Intent intent = getIntent();
            overridePendingTransition(0, 0);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            finish();
            overridePendingTransition(0, 0);
            startActivity(intent);
        }

        _cu.setKeepScreenOn(this, _appSettings.isKeepScreenOn());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && _appSettings.isMultiWindowEnabled()) {
            setTaskDescription(new ActivityManager.TaskDescription(getString(R.string.app_name)));
        }

        // Introduction dialog and show changelog etc.
        final boolean firstStart = IntroActivity.optStart(this);
        try {
            if (!firstStart && _appSettings.isAppCurrentVersionFirstStart(true)) {
                GsSimpleMarkdownParser smp = GsSimpleMarkdownParser.get().setDefaultSmpFilter(GsSimpleMarkdownParser.FILTER_ANDROID_TEXTVIEW);
                String html = "";
                html += smp.parse(getString(R.string.copyright_license_text_official).replace("\n", "  \n"), "").getHtml();
                html += "<br/><br/><br/><big><big>" + getString(R.string.changelog) + "</big></big><br/>" + smp.parse(getResources().openRawResource(R.raw.changelog), "", GsSimpleMarkdownParser.FILTER_ANDROID_TEXTVIEW);
                html += "<br/><br/><br/><big><big>" + getString(R.string.licenses) + "</big></big><br/>" + smp.parse(getResources().openRawResource(R.raw.licenses_3rd_party), "").getHtml();
                if (GsContextUtils.instance.isDarkModeEnabled(this)) {
                    html = html.replace("font color='#000000'", "font color='#D3D3D3'");
                }
                _cu.showDialogWithHtmlTextView(this, 0, html);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        GitNotebook.attach(this);
        GitNotebook.startTimer(this);
        if (!_gitOpenedNotified) {
            // 换笔记本根目录会重建 MainActivity，这个标记随之复位
            _gitOpenedNotified = true;
            GitNotebook.onNotebookOpened(this);
        }
    }

    @Override
    public void onPostResume() {
        super.onPostResume();
    }

    // Cycle between recent, favourite, and current
    public boolean onLongClickFab(View view) {
        if (_notebook != null) {
            final File current = _notebook.getCurrentFolder();
            final File dest;
            if (GsFileBrowserListAdapter.VIRTUAL_STORAGE_RECENTS.equals(current)) {
                dest = GsFileBrowserListAdapter.VIRTUAL_STORAGE_FAVOURITE;
            } else if (GsFileBrowserListAdapter.VIRTUAL_STORAGE_FAVOURITE.equals(current)) {
                if (_quickSwitchPrevFolder != null) {
                    dest = _quickSwitchPrevFolder;
                } else {
                    dest = GsFileBrowserListAdapter.VIRTUAL_STORAGE_RECENTS;
                }
            } else {
                _quickSwitchPrevFolder = current;
                dest = GsFileBrowserListAdapter.VIRTUAL_STORAGE_FAVOURITE;
            }
            _notebook.getAdapter().setCurrentFolder(dest);
        }
        return true;
    }

    public void onClickFab(final View view) {
        if (_notebook == null || _notebook.getAdapter() == null) {
            return;
        }

        if (!_notebook.getAdapter().isCurrentFolderWriteable()) {
            _notebook.getAdapter().setCurrentFolder(_appSettings.getNotebookDirectory());
            return;
        }

        if (view.getId() == R.id.fab_add_new_item) {
            if (_cu.isUnderStorageAccessFolder(this, _notebook.getCurrentFolder(), true) && _cu.getStorageAccessFrameworkTreeUri(this) == null) {
                _cu.showMountSdDialog(this);
                return;
            }

            NewFileDialog.newInstance(_notebook.getCurrentFolder(), true, this::newItemCallback)
                    .show(getSupportFragmentManager(), NewFileDialog.FRAGMENT_TAG);
        }
    }

    private void newItemCallback(final File file) {
        if (file.isFile()) {
            openInEditor(file);
        }
        if (_notebook != null && _notebook.getAdapter() != null) {
            _notebook.getAdapter().showFile(file);
        }
    }

    private void showLargeFileOpenToastIfNeeded(final File file) {
        final long LARGE_FILE_TOAST_THRESHOLD_BYTES = 128L * 1024L;

        // Check if file is large and if true show a toast notification for user to wait
        if (file != null && file.isFile() && !FormatRegistry.CONVERTER_EMBEDBINARY.isFileOutOfThisFormat(file)) {
            final long fileBytes = file.length();
            if (fileBytes > LARGE_FILE_TOAST_THRESHOLD_BYTES) {
                final String readableSize = GsFileUtils.getReadableFileSize(fileBytes, true);
                Toast.makeText(this, getString(R.string.loading_large_file_may_take_a_moment_witharg, readableSize), Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onBackPressed() {
        // 设置页里还有二级页 / 下一层时，先退那一层
        if (getCurrentPos() == tabIdToPos(R.id.nav_settings)) {
            if (_settingsPage != null && _settingsPage.onBackPressed()) {
                return;
            }
            // 退干净了就回文件页 —— 设置以前是个带返回箭头的页面，这里别一按就退出应用
            _viewPager.setCurrentItem(tabIdToPos(R.id.nav_notebook));
            return;
        }
        // Check if fragment handled back press
        final GsFragmentBase<?, ?> frag = getPosFragment(getCurrentPos());
        if (frag == null || !frag.onBackPressed()) {
            super.onBackPressed();
        }
    }

    /** 设置页内部换了层级（二级页 / 下一层偏好）时，反过来通知标题栏 */
    void onSettingsTitleChanged(@NonNull final String title) {
        if (_viewPager != null && _viewPager.getCurrentItem() == tabIdToPos(R.id.nav_settings)) {
            setTitle(title);
        }
    }

    public String getFileBrowserTitle() {
        final File file = _notebook != null ? _notebook.getCurrentFolder() : null;
        if (file != null && !_appSettings.getNotebookDirectory().equals(file)) {
            // 进到子目录时标题就是这个目录名（原来那截 "> " 面包屑前缀已经没必要）
            return file.getName();
        } else {
            // 根目录名就是应用名：标题栏里不重复它
            return "";
        }
    }

    public int tabIdToPos(final int id) {
        if (id == R.id.nav_notebook) return 0;
        if (id == R.id.nav_editor) return 1;
        if (id == R.id.nav_tabs) return 2;
        if (id == R.id.nav_settings) return 3;
        return -1;
    }

    public int tabIdFromPos(final int pos) {
        return _bottomNav.getMenu().getItem(pos).getItemId();
    }

    public int getCurrentPos() {
        return _viewPager.getCurrentItem();
    }

    public String getPosTitle(final int pos) {
        if (pos == 0) return getFileBrowserTitle();
        if (pos == 1) {
            final File file = _editor != null ? _editor.getCurrentFile() : null;
            return file != null ? file.getName() : "";
        }
        if (pos == tabIdToPos(R.id.nav_settings)) {
            return _settingsPage != null ? _settingsPage.getCurrentTitle() : getString(R.string.settings);
        }
        return "";
    }

    public GsFragmentBase<?, ?> getPosFragment(final int pos) {
        // 编辑工作区不是 GsFragmentBase 的子类，按键事件由它自己处理
        return pos == 0 ? _notebook : null;
    }

    /**
     * Restores the default toolbar. Used when changing the tab or moving to another activity
     * while {@link GsFileBrowserFragment} action mode is active (e.g. when renaming a file)
     */
    private void restoreDefaultToolbar() {
        GsFileBrowserFragment wrFragment = getNotebook();
        if (wrFragment != null) {
            wrFragment.clearSelection();
        }
    }

    public void hideKeyboard() {
        if (_editor != null) {
            _editor.hideKeyboard();
        }
    }

    /**
     * 文件页里点文件：不另开 Activity，直接在"编辑"页开一个标签。
     * 这样底部导航的左右滑动、底栏收放都还是同一套体验。
     */
    public void openInEditor(final File file) {
        if (file == null || !file.isFile()) {
            return;
        }
        showLargeFileOpenToastIfNeeded(file);
        final int pos = tabIdToPos(R.id.nav_editor);
        if (pos < 0) {
            return;
        }
        _viewPager.setCurrentItem(pos, false);
        // 编辑页可能是这一帧才建出来的，等它挂上再把文件塞进去
        _viewPager.postDelayed(() -> {
            bindFragmentRef(pos);
            if (_editor != null) {
                _editor.openFile(file);
                // 打开期间页面回调可能已经写过标题，这里以编辑器里的当前文件为准再刷一次
                updatePageTitle();
            }
        }, 60);
    }

    /** 编辑页切换标签后更新标题栏（文件名） */
    public void onEditorTabChanged(final File file) {
        setTitle(file != null ? GsFileUtils.getFilenameWithoutExtension(file) : "");
    }

    /**
     * 键盘一弹出就把底栏收走（编辑状态要整屏），键盘收起再还回来。
     * 用可见区域高度差判断，不依赖特定控件。
     */
    private void setupKeyboardChromeAutoHide() {
        final View root = findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            final android.graphics.Rect visible = new android.graphics.Rect();
            root.getWindowVisibleDisplayFrame(visible);
            final int hidden = root.getRootView().getHeight() - visible.bottom;
            final boolean shown = hidden > 120 * getResources().getDisplayMetrics().density;
            if (shown != _keyboardShown) {
                _keyboardShown = shown;
                setBottomNavAutoHidden(shown);
            }
        });
    }

    /** 文件列表上滑收底栏、下滑还回来（编辑页由编辑器自己回调） */
    private void setupFileListChromeAutoHide() {
        if (_notebook == null) {
            return;
        }
        final RecyclerView list = _notebook.getRecyclerView();
        if (list == null || list.getTag(R.id.tag_chrome_scroll) != null) {
            return;
        }
        list.setTag(R.id.tag_chrome_scroll, Boolean.TRUE);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull final RecyclerView rv, final int dx, final int dy) {
                if (Math.abs(dy) < 8) {
                    return;
                }
                setBottomNavAutoHidden(dy > 0);
            }
        });
    }

    public void onViewPagerPageSelected(final int pos) {
        // 换页时底栏必须回来，不能停在上一页收起来的状态
        setBottomNavAutoHidden(false);
        // 文件页会在选择条目时写工具栏副标题（"(1 / 4)"），离开文件页要擦掉
        if (pos != tabIdToPos(R.id.nav_notebook) && _toolbar instanceof Toolbar) {
            ((Toolbar) _toolbar).setSubtitle(null);
        }
        _bottomNav.getMenu().getItem(pos).setChecked(true);
        if (_sectionsAdapter != null) {
            bindFragmentRef(pos);
            // 「最近」页展示的是编辑页里的标签，两个引用都要拿全
            if (pos == tabIdToPos(R.id.nav_tabs)) {
                bindFragmentRef(tabIdToPos(R.id.nav_editor));
            }
            // 设置页通常是提前建好的；要是还没轮到（比如启动就直接落在这一页），当场补建
            if (pos == tabIdToPos(R.id.nav_settings) && _settingsPage != null) {
                _settingsPage.prepare();
            }
        }
        // 编辑页退到后台，它的工具栏菜单（撤销/重做/保存/搜索…）要跟着收起
        if (_editor != null) {
            _editor.setPageVisible(pos == tabIdToPos(R.id.nav_editor));
        }

        if (pos == tabIdToPos(R.id.nav_notebook)) {
            _fab.show();
            hideKeyboard();
        } else {
            _fab.hide();
            // 离开文件页时把搜索框收干净，回来是完整列表而不是上一轮的过滤结果
            if (_searchInput != null && !TextUtils.isEmpty(_searchInput.getText())) {
                _searchInput.setText("");
            }
            hideKeyboard();
        }

        updatePageTitle();
        updateHeaderVisibility();
        // 页面首次访问时片段是这一帧才建出来的，等布局跑完再取一次文档名
        if (_viewPager != null) {
            _viewPager.post(() -> {
                bindFragmentRef(pos);
                if (_editor != null) {
                    _editor.setPageVisible(pos == tabIdToPos(R.id.nav_editor));
                }
                updatePageTitle();
                updateHeaderVisibility();
                // 文件页的列表也是这一帧才建出来的，挂滚动监听要等到这里
                setupFileListChromeAutoHide();
            });
        }
    }

    /**
     * 标题只回答一个问题：我现在在哪。文件页答目录名，编辑页答文件名，最近页就写"最近"，
     * 拿不到名字就留空——留空时标题栏会退成页底色，不会剩下一块只有图标的空条。
     */
    private void updatePageTitle() {
        if (_viewPager == null) {
            return;
        }
        final int pos = _viewPager.getCurrentItem();
        if (pos == tabIdToPos(R.id.nav_notebook)) {
            setTitle(getFileBrowserTitle());
            return;
        }
        if (pos == tabIdToPos(R.id.nav_tabs)) {
            setTitle(getString(R.string.tabs_page));
            return;
        }
        if (pos == tabIdToPos(R.id.nav_settings)) {
            setTitle(getPosTitle(pos));
            return;
        }
        final File file = _editor != null && pos == tabIdToPos(R.id.nav_editor) ? _editor.getCurrentFile() : null;
        // 和标签里显示的名字保持一致：都不带扩展名
        setTitle(file != null ? GsFileUtils.getFilenameWithoutExtension(file) : "");
    }

    @Override
    public void setTitle(final CharSequence title) {
        super.setTitle(title);
        // 文件页把目录名放进搜索行左侧；速记/待办仍然由工具栏显示文件名
        if (_fileHeaderTitle != null) {
            _fileHeaderTitle.setText(title);
            _fileHeaderTitle.setVisibility(TextUtils.isEmpty(title) ? View.GONE : View.VISIBLE);
        }
        syncTopSurfaceTint();
    }

    /**
     * 状态栏和顶部那块卡片必须同时决定，否则顶部会先亮一截再掉到页底色上。
     * 文件页的搜索行是常驻的，所以永远铺卡片；编辑器页有文件名才铺，没有就露出页底色。
     */
    private boolean topIsCard() {
        if (_appBar == null || _viewPager == null) {
            return true;
        }
        if (_appBar.getVisibility() != View.VISIBLE) {
            return true;
        }
        if (_viewPager.getCurrentItem() == tabIdToPos(R.id.nav_notebook)) {
            return true;
        }
        return !TextUtils.isEmpty(getTitle());
    }

    private void syncTopSurfaceTint() {
        if (_appBar == null) {
            return;
        }
        final boolean card = topIsCard();
        _appBar.setBackgroundResource(card ? R.drawable.bg_header_rounded : android.R.color.transparent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(ContextCompat.getColor(this,
                    card ? R.color.card : R.color.background));
        }
    }

    private GsFileBrowserOptions.Options _filesystemDialogOptions = null;

    @Override
    public GsFileBrowserOptions.Options getFilesystemFragmentOptions(GsFileBrowserOptions.Options existingOptions) {
        if (_filesystemDialogOptions == null) {
            _filesystemDialogOptions = MarkorFileBrowserFactory.prepareFsViewerOpts(this, false, new GsFileBrowserOptions.SelectionListenerAdapter() {

                @Override
                public void onFsViewerConfig(GsFileBrowserOptions.Options dopt) {
                    dopt.descModtimeInsteadOfParent = true;
                    dopt.rootFolder = _appSettings.getNotebookDirectory();
                    dopt.startFolder = _startFolder;
                    // 文件页锁在笔记本里：连".."都不给，永远跳不出这个目录
                    dopt.restrictToRootFolder = true;
                    // 选中行的高亮靠浅灰底 + 细描边，勾本身用墨色，不要在浅灰底上再画一个浅灰勾
                    dopt.accentColor = R.color.primary_text;
                    dopt.doSelectMultiple = dopt.doSelectFolder = dopt.doSelectFile = true;
                    dopt.mountedStorageFolder = _cu.getStorageAccessFolder(MainActivity.this);
                }

                @Override
                public void onFsViewerDoUiUpdate(final GsFileBrowserListAdapter adapter) {
                    if (adapter != null && adapter.getCurrentFolder() != null && !TextUtils.isEmpty(adapter.getCurrentFolder().getName())) {
                        _appSettings.setFileBrowserLastBrowsedFolder(adapter.getCurrentFolder());
                        if (getCurrentPos() == tabIdToPos(R.id.nav_notebook)) {
                            setTitle(getFileBrowserTitle());
                        }
                    }
                    // 选中条目会把头部从搜索行换成工具栏，选择状态一变就要重算
                    updateHeaderVisibility();

                    if (_showFile != null && adapter != null) {
                        adapter.showFile(_showFile);
                        _showFile = null;
                    }
                }

                @Override
                public void onFsViewerSelected(String request, File file, final Integer lineNumber) {
                    if (file != null && file.isFile()) {
                        openInEditor(file);
                    } else {
                        DocumentActivity.launch(MainActivity.this, file, null, lineNumber);
                    }
                }
            });
        }
        return _filesystemDialogOptions;
    }

    class SectionsPagerAdapter extends FragmentStateAdapter {

        SectionsPagerAdapter(FragmentManager fragMgr) {
            super(fragMgr, MainActivity.this.getLifecycle());
        }

        @NonNull
        @Override
        public Fragment createFragment(final int pos) {
            final Fragment frag;
            final int id = tabIdFromPos(pos);
            if (id == R.id.nav_editor) {
                frag = _editor = new EditorFragment();
            } else if (id == R.id.nav_tabs) {
                frag = _tabsPage = new EditorTabsFragment();
            } else if (id == R.id.nav_settings) {
                frag = _settingsPage = new SettingsFragment();
            } else {
                frag = _notebook = GsFileBrowserFragment.newInstance();
            }
            frag.setMenuVisibility(false);
            return frag;
        }

        @Override
        public int getItemCount() {
            return PAGE_COUNT;
        }
    }

    /**
     * 三个页面都由 FragmentStateAdapter 真实创建（原先的"占位 Fragment"要在同一帧里
     * notifyItemChanged 换成真身，直接点进「最近」页时会换不及，出现一整页空白）。
     * <p>
     * 引用可能因为系统回收 FragmentManager 里已有实例而没经过 createFragment，
     * 这里按 FragmentStateAdapter 的 tag 规则（"f" + itemId）补一次。
     */
    private boolean bindFragmentRef(final int pos) {
        final Fragment frag = getSupportFragmentManager().findFragmentByTag("f" + pos);
        if (frag == null) {
            return false;
        }
        final int id = tabIdFromPos(pos);
        if (id == R.id.nav_editor) {
            if (frag instanceof EditorFragment) {
                _editor = (EditorFragment) frag;
            }
        } else if (id == R.id.nav_tabs) {
            if (frag instanceof EditorTabsFragment) {
                _tabsPage = (EditorTabsFragment) frag;
            }
        } else if (id == R.id.nav_settings) {
            if (frag instanceof SettingsFragment) {
                _settingsPage = (SettingsFragment) frag;
            }
        } else if (frag instanceof GsFileBrowserFragment) {
            _notebook = (GsFileBrowserFragment) frag;
        }
        return true;
    }

    public GsFileBrowserFragment getNotebook() {
        return _notebook;
    }

    /** 「最近」页需要读编辑页里的标签数据（顺序、固定、未保存） */
    @Nullable
    public EditorFragment getEditorFragment() {
        return _editor;
    }

    @Override
    protected void onPause() {
        super.onPause();
        GitNotebook.detach(this);
        WrMarkorWidgetProvider.updateLauncherWidgets();
        TodoWidgetProvider.updateTodoWidgets();

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED |
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    //
    // GitNotebook.Ui —— 同步结果只在这里落地，同步本身在后台线程
    //
    @Override
    public void onGitMessage(final String message, final boolean isError) {
        Toast.makeText(this, isError ? getString(R.string.git_sync_failed, message) : message,
                Toast.LENGTH_LONG).show();
    }

    @Override
    public void onGitConflict(final List<String> paths, final String stage) {
        MarkorDialogFactory.showGitConflictDialog(this, paths, stage);
    }

    @Override
    protected void onStop() {
        super.onStop();
        restoreDefaultToolbar();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        _cu.extractResultFromActivityResult(this, requestCode, resultCode, data);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return super.onReceiveKeyPress(getPosFragment(getCurrentPos()), keyCode, event) ? true : super.onKeyDown(keyCode, event);
    }
}
