/*#######################################################
 *
 *   Maintained 2017-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.rarepebble.colorpicker.ColorPreference;

import net.gsantner.markor.R;
import net.gsantner.markor.frontend.MarkorDialogFactory;
import net.gsantner.markor.frontend.filebrowser.MarkorFileBrowserFactory;
import net.gsantner.markor.model.AppSettings;
import net.gsantner.markor.model.git.GitNotebook;
import net.gsantner.markor.model.git.GitSyncJob;
import net.gsantner.markor.util.BackupUtils;
import net.gsantner.markor.util.MarkorContextUtils;
import net.gsantner.markor.widget.TodoWidgetProvider;
import net.gsantner.opoc.frontend.base.GsActivityBase;
import net.gsantner.opoc.frontend.base.GsPreferenceFragmentBase;
import net.gsantner.opoc.frontend.filebrowser.GsFileBrowserOptions;
import net.gsantner.opoc.frontend.settings.GsFontPreferenceCompat;
import net.gsantner.opoc.util.GsContextUtils;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Locale;

import other.writeily.widget.WrMarkorWidgetProvider;

public class SettingsActivity extends MarkorBaseActivity implements SettingsHost {

    @SuppressWarnings("WeakerAccess")
    public static class RESULT {
        public static final int NOCHANGE = -1;
        public static final int CHANGED = 1;
        public static final int RESTART_REQ = 2;
    }

    public static int activityRetVal = RESULT.NOCHANGE;
    private static int iconColor = Color.WHITE;

    protected Toolbar toolbar;

    @Override
    public void onCreate(Bundle b) {
        // Must be applied before setContentView
        super.onCreate(b);

        // Load UI
        setContentView(R.layout.settings__activity);
        toolbar = findViewById(R.id.toolbar);

        // Custom code
        GsFontPreferenceCompat.additionalyCheckedFolder = new File(_appSettings.getNotebookDirectory(), ".app/fonts");
        iconColor = _cu.rcolor(this, R.color.primary_text);
        toolbar.setTitle(R.string.settings);
        setSupportActionBar(findViewById(R.id.toolbar));
        // 标题栏现在是浅色表面，返回箭头要跟着用正文墨色，不能再用写死的白色图标
        final Drawable backIcon = ContextCompat.getDrawable(this, R.drawable.ic_arrow_back_white_24dp);
        if (backIcon != null) {
            DrawableCompat.setTint(backIcon.mutate(), ContextCompat.getColor(this, R.color.primary_text));
        }
        toolbar.setNavigationIcon(backIcon);
        toolbar.setNavigationOnClickListener(view -> SettingsActivity.this.onBackPressed());
        setupBottomNav();
        showFragment(SettingsFragmentMaster.TAG, false);
    }

    /**
     * 设置页保留底部导航：点别的标签就回主界面并把那一页选上，
     * 点"设置"本身不动（已经在设置里了）。
     */
    private void setupBottomNav() {
        final BottomNavigationView nav = findViewById(R.id.settings__bottom_navigation_bar);
        if (nav == null) {
            return;
        }
        nav.setOnItemSelectedListener(item -> {
            final int id = item.getItemId();
            if (id == R.id.nav_settings) {
                return false;
            }
            final Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra(MainActivity.EXTRA_TAB_ID, id);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            overridePendingTransition(0, 0);
            finish();
            overridePendingTransition(0, 0);
            return true;
        });
        // 默认选中的是第一项，进设置页要把它挪到"设置"上
        final MenuItem settingsItem = nav.getMenu().findItem(R.id.nav_settings);
        if (settingsItem != null) {
            settingsItem.setChecked(true);
        }
    }

    protected void showFragment(String tag, boolean addToBackStack) {
        String toolbarTitle = getString(R.string.settings);
        GsPreferenceFragmentBase prefFrag = (GsPreferenceFragmentBase) getSupportFragmentManager().findFragmentByTag(tag);
        if (prefFrag == null) {
            switch (tag) {
                case SettingsFragmentMaster.TAG:
                default: {
                    prefFrag = new SettingsFragmentMaster();
                    toolbarTitle = prefFrag.getTitleOrDefault(toolbarTitle);
                    break;
                }
            }
        }
        toolbar.setTitle(toolbarTitle);
        FragmentTransaction t = getSupportFragmentManager().beginTransaction();
        if (addToBackStack) {
            t.addToBackStack(tag);
        }
        t.replace(R.id.settings__activity__fragment_placeholder, prefFrag, tag).commit();
    }

    /**
     * 打开二级设置页（通用/编辑/视图/各格式…）。子页各自一个 XML，点进来才 inflate，
     * 顶层那一页因此只剩十几行入口，进设置不再需要把七十多个 Preference 全建一遍。
     * <p>
     * 主界面里的设置页（{@link SettingsFragment}）也走同一条路，所以这里实现的是
     * {@link SettingsHost} 的接口，而不是只给自己用的方法。
     */
    @Override
    public void showSettingsSubScreen(final int xmlRes, final CharSequence title) {
        final String tag = SettingsFragmentMaster.TAG + "_" + xmlRes;
        SettingsFragmentMaster frag = (SettingsFragmentMaster) getSupportFragmentManager().findFragmentByTag(tag);
        if (frag == null) {
            frag = SettingsFragmentMaster.newInstance(xmlRes, title);
        }
        toolbar.setTitle(title != null ? title : getString(R.string.settings));
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.settings__activity__fragment_placeholder, frag, tag)
                .addToBackStack(tag)
                .commit();
    }

    @Override
    protected void onStop() {
        setResult(activityRetVal);
        super.onStop();
    }

    public static abstract class MarkorSettingsFragment extends GsPreferenceFragmentBase<AppSettings> {
        @Override
        protected AppSettings getAppSettings(Context context) {
            return AppSettings.get(context);
        }

        @Override
        protected void onPreferenceChanged(SharedPreferences prefs, String key) {
            activityRetVal = RESULT.CHANGED;
        }

        /**
         * 设置片段可能挂在独立设置页里，也可能挂在主界面的设置页里 —— 往上找一个宿主，
         * 由它负责显示二级页。两边都不用知道对方的存在。
         */
        @Nullable
        protected SettingsHost findSettingsHost() {
            final Fragment parent = getParentFragment();
            if (parent instanceof SettingsHost) {
                return (SettingsHost) parent;
            }
            final Activity activity = getActivity();
            return activity instanceof SettingsHost ? (SettingsHost) activity : null;
        }

        @Override
        @SuppressWarnings("rawtypes")
        protected void onPreferenceScreenChanged(PreferenceFragmentCompat preferenceFragmentCompat, PreferenceScreen preferenceScreen) {
            super.onPreferenceScreenChanged(preferenceFragmentCompat, preferenceScreen);
            final CharSequence title = preferenceScreen.getTitle();
            // 在主界面里时，标题归承载它的那一页管（它会自己算"设置 / 通用 / 基础配色"），
            // 只有独立设置页才需要片段自己去改工具栏
            final Fragment parent = getParentFragment();
            if (parent instanceof SettingsFragment) {
                ((SettingsFragment) parent).onPreferenceScreenTitle(title);
                return;
            }
            final Activity activity = getActivity();
            if (activity instanceof GsActivityBase && !TextUtils.isEmpty(title)) {
                ((GsActivityBase<?, ?>) activity).setToolbarText(title);
            }
        }
    }

    @Override
    public void onBackPressed() {
        // 先看"当前可见"的这层：它自己可能还叠着一层（比如 编辑 → 基础配色），
        // 再往下才是回退到上一级设置页
        final androidx.fragment.app.Fragment current = getSupportFragmentManager()
                .findFragmentById(R.id.settings__activity__fragment_placeholder);
        if (current instanceof GsPreferenceFragmentBase && ((GsPreferenceFragmentBase) current).canGoBack()) {
            ((GsPreferenceFragmentBase) current).goBack();
            toolbar.setTitle(((GsPreferenceFragmentBase) current).getTitleOrDefault(getString(R.string.settings)));
            return;
        }
        if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            getSupportFragmentManager().popBackStack();
            return;
        }
        super.onBackPressed();
    }

    public static class SettingsFragmentMaster extends MarkorSettingsFragment {
        public static final String TAG = "SettingsFragmentMaster";
        private static final String ARG_XML_RES = "xml_res";
        private static final String ARG_TITLE = "screen_title";

        static SettingsFragmentMaster newInstance(final int xmlRes, final CharSequence title) {
            final SettingsFragmentMaster frag = new SettingsFragmentMaster();
            final Bundle args = new Bundle();
            args.putInt(ARG_XML_RES, xmlRes);
            args.putString(ARG_TITLE, title != null ? title.toString() : null);
            frag.setArguments(args);
            return frag;
        }

        @Override
        public int getPreferenceResourceForInflation() {
            final Bundle args = getArguments();
            return args != null && args.containsKey(ARG_XML_RES)
                    ? args.getInt(ARG_XML_RES) : R.xml.preferences_master;
        }

        @Override
        public String getTitle() {
            final Bundle args = getArguments();
            return args != null ? args.getString(ARG_TITLE) : null;
        }

        @Override
        public String getFragmentTag() {
            final Bundle args = getArguments();
            return args != null && args.containsKey(ARG_XML_RES)
                    ? TAG + "_" + args.getInt(ARG_XML_RES) : TAG;
        }

        @Override
        public void doUpdatePreferences() {
            // 顶层只有十来个"入口"行 + 主题/语言，两个都不需要算动态摘要。
            // 下面那一大段（目录、git、颜色…）在顶层一个都命中不了，却要跑几十次
            // findPreference 和好几次 HTML 解析 —— 进设置慢，慢的就是这部分。
            if (getPreferenceResourceForInflation() == R.xml.preferences_master) {
                return;
            }
            String remove = "/storage/emulated/0/";
            updateSummary(R.string.pref_key__notebook_directory,
                    _cu.htmlToSpanned("<small><small>" + _appSettings.getNotebookDirectory().getAbsolutePath().replace(remove, "") + "</small></small>")
            );
            updateSummary(R.string.pref_key__quicknote_filepath,
                    _cu.htmlToSpanned("<small><small>" + _appSettings.getQuickNoteFile().getAbsolutePath().replace(remove, "") + "</small></small>")
            );
            updateSummary(R.string.pref_key__todo_filepath,
                    _cu.htmlToSpanned("<small><small>" + _appSettings.getTodoFile().getAbsolutePath().replace(remove, "") + "</small></small>")
            );
            updatePreference(R.string.pref_key__is_launcher_for_special_files_enabled, null,
                    ("Launcher (" + getString(R.string.special_documents) + ")"),
                    getString(R.string.app_drawer_launcher_special_files_description), true
            );
            updateSummary(R.string.pref_key__exts_to_always_open_in_this_app, _appSettings.getString(R.string.pref_key__exts_to_always_open_in_this_app, ""));

            updateSummary(R.string.pref_key__snippet_directory_path, _appSettings.getSnippetsDirectory().getAbsolutePath());

            final String fileDescFormat = _appSettings.getString(R.string.pref_key__file_description_format, "");
            if (fileDescFormat.equals("")) {
                updateSummary(R.string.pref_key__file_description_format, getString(R.string.default_));
            } else {
                updateSummary(R.string.pref_key__file_description_format, fileDescFormat);
            }

            setPreferenceVisible(R.string.pref_key__is_multi_window_enabled, Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP);

            setPreferenceVisible(R.string.pref_key__set_encryption_password, Build.VERSION.SDK_INT >= Build.VERSION_CODES.M);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && _appSettings.isDefaultPasswordSet()) {
                updateSummary(R.string.pref_key__set_encryption_password, getString(R.string.hidden_password));
            }

            final File gitRepository = _appSettings.getGitRepositoryDirectory();
            final boolean hasGitRepository = gitRepository != null;
            for (final int keyId : new int[]{R.string.pref_key__git_sync_now, R.string.pref_key__git_pull,
                    R.string.pref_key__git_push, R.string.pref_key__git_status}) {
                setPreferenceVisible(keyId, hasGitRepository);
            }
            final String lastSync = _appSettings.getGitLastSyncInfo();
            updateSummary(R.string.pref_key__git_sync_enabled, hasGitRepository
                    ? gitRepository.getAbsolutePath().replace(remove, "")
                    + (lastSync.isEmpty() ? "" : "\n" + lastSync)
                    : getString(R.string.git_not_a_repository));
            updateSummary(R.string.pref_key__git_commit_message, _appSettings.getGitCommitMessageTemplate());
            // 作者没填本身不会让 git 报错（会用占位身份提交），所以在这里显式标出来
            final boolean authorSet = _appSettings.isGitAuthorConfigured();
            updateSummary(R.string.pref_key__git_author_name, authorSet
                    ? _appSettings.getGitAuthorName() : getString(R.string.git_author_not_set));
            updateSummary(R.string.pref_key__git_author_email, authorSet
                    ? _appSettings.getGitAuthorEmail() : getString(R.string.git_author_not_set));
            setPreferenceVisible(R.string.pref_key__git_access_token, Build.VERSION.SDK_INT >= Build.VERSION_CODES.M);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && _appSettings.isGitAccessTokenSet()) {
                updateSummary(R.string.pref_key__git_access_token, getString(R.string.hidden_password));
            }
            updateSummary(R.string.pref_key__git_ssh_key_path, _appSettings.getGitSshKeyPath());
            setPreferenceVisible(R.string.pref_key__git_ssh_passphrase, Build.VERSION.SDK_INT >= Build.VERSION_CODES.M);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && _appSettings.isGitSshPassphraseSet()) {
                updateSummary(R.string.pref_key__git_ssh_passphrase, getString(R.string.hidden_password));
            }

            final int[] experimentalKeys = new int[]{
                    R.string.pref_key__swipe_to_change_mode,
                    R.string.pref_key__todotxt__hl_delay,
                    R.string.pref_key__markdown__hl_delay_v2,
                    R.string.pref_key__theming_hide_system_statusbar,
                    R.string.pref_key__tab_width_v2,
                    R.string.pref_key__editor_line_spacing,
            };
            for (final int keyId : experimentalKeys) {
                setPreferenceVisible(keyId, _appSettings.isExperimentalFeaturesEnabled());
            }

            refreshEditorColorPreferences();
        }

        @SuppressLint("ApplySharedPref")
        @Override
        protected void onPreferenceChanged(final SharedPreferences prefs, final String key) {
            super.onPreferenceChanged(prefs, key);
            final Context context = getContext();
            if (context == null) {
                return;
            }

            if (eq(key, R.string.pref_key__language)) {
                activityRetVal = RESULT.RESTART_REQ;
                _appSettings.setRecreateMainRequired(true);
            } else if (eq(key, R.string.pref_key__app_theme)) {
                _appSettings.applyAppTheme();
                _appSettings.setRecreateMainRequired(true);
                if (getActivity() != null) {
                    getActivity().recreate();
                }
            } else if (eq(key, R.string.pref_key__theming_hide_system_statusbar)) {
                activityRetVal = RESULT.RESTART_REQ;
                _appSettings.setRecreateMainRequired(true);
            } else if (eq(key, R.string.pref_key__is_launcher_for_special_files_enabled)) {
                boolean extraLaunchersEnabled = prefs.getBoolean(key, false);
                new MarkorContextUtils(getActivity()).applySpecialLaunchersVisibility(getActivity(), extraLaunchersEnabled);
            } else if (eq(key, R.string.pref_key__file_description_format)) {
                try {
                    new SimpleDateFormat(prefs.getString(key, ""), Locale.getDefault());
                } catch (IllegalArgumentException e) {
                    Toast.makeText(getContext(), e.getLocalizedMessage() + "\n\n" + getString(R.string.loading_default_value), Toast.LENGTH_SHORT).show();
                    prefs.edit().putString(key, "").commit();
                }
            } else if (eq(key, R.string.pref_key__share_into_format)) {
                try {
                    Toast.makeText(context, GsContextUtils.instance.formatDateTime(context, prefs.getString(key, ""), System.currentTimeMillis()), Toast.LENGTH_SHORT).show();
                } catch (IllegalArgumentException e) {
                    Toast.makeText(context, e.getLocalizedMessage() + "\n\n" + getString(R.string.loading_default_value), Toast.LENGTH_SHORT).show();
                }
            } else if (eq(key, R.string.pref_key__notebook_directory, R.string.pref_key__quicknote_filepath, R.string.pref_key__todo_filepath)) {
                WrMarkorWidgetProvider.updateLauncherWidgets();
                TodoWidgetProvider.updateTodoWidgets();
            } else if (eq(key, R.string.pref_key__git_sync_enabled, R.string.pref_key__git_sync_interval_minutes)) {
                // 后台任务的节奏跟着这两项走，界面在时另有前台定时器
                GitSyncJob.schedule(context, _appSettings);
                GitNotebook.startTimer(context);
            }
        }

        @Override
        @SuppressWarnings({"ConstantConditions", "ConstantIfStatement", "StatementWithEmptyBody"})
        public Boolean onPreferenceClicked(Preference preference, String key, int keyResId) {
            final FragmentManager fragManager = getActivity().getSupportFragmentManager();
            // 顶层的"入口"行：按 key 打开对应的独立子页
            final int subScreenXml = subScreenXmlFor(key);
            if (subScreenXml != 0) {
                final SettingsHost host = findSettingsHost();
                if (host != null) {
                    host.showSettingsSubScreen(subScreenXml, preference.getTitle());
                }
                return true;
            }
            switch (keyResId) {
                case R.string.pref_key__request_external_storage: {
                    GsContextUtils.instance.requestExternalStoragePermission(getActivity());
                    return true;
                }
                case R.string.pref_key__snippet_directory_path: {
                    MarkorFileBrowserFactory.showFolderDialog(new GsFileBrowserOptions.SelectionListenerAdapter() {
                        @Override
                        public void onFsViewerSelected(String request, File file, final Integer lineNumber) {
                            _appSettings.setSnippetDirectory(file);
                            doUpdatePreferences();
                        }

                        @Override
                        public void onFsViewerConfig(GsFileBrowserOptions.Options dopt) {
                            dopt.titleText = R.string.snippet_directory;
                            dopt.rootFolder = _appSettings.getNotebookDirectory();
                        }
                    }, fragManager, getActivity());
                    return true;
                }

                case R.string.pref_key__notebook_directory: {
                    MarkorFileBrowserFactory.showFolderDialog(new GsFileBrowserOptions.SelectionListenerAdapter() {
                        @Override
                        public void onFsViewerSelected(String request, File file, final Integer lineNumber) {
                            _appSettings.setNotebookDirectory(file);
                            _appSettings.setRecreateMainRequired(true);
                            doUpdatePreferences();
                        }

                        @Override
                        public void onFsViewerConfig(GsFileBrowserOptions.Options dopt) {
                            dopt.titleText = R.string.select_storage_folder;
                            dopt.rootFolder = _appSettings.getNotebookDirectory();
                        }
                    }, fragManager, getActivity());
                    return true;
                }
                case R.string.pref_key__git_clone: {
                    MarkorDialogFactory.showGitCloneDialog(getActivity());
                    return true;
                }
                case R.string.pref_key__git_sync_now: {
                    GitNotebook.requestSync(getActivity(), true, false);
                    return true;
                }
                case R.string.pref_key__git_pull: {
                    GitNotebook.requestPull(getActivity());
                    return true;
                }
                case R.string.pref_key__git_push: {
                    GitNotebook.requestPush(getActivity());
                    return true;
                }
                case R.string.pref_key__git_status: {
                    MarkorDialogFactory.showGitStatusDialog(getActivity());
                    return true;
                }
                case R.string.pref_key__git_commit_message: {
                    MarkorDialogFactory.showGitTextSettingDialog(getActivity(), R.string.git_commit_message,
                            _appSettings.getGitCommitMessageTemplate(), false, value -> {
                                _appSettings.setGitCommitMessageTemplate(value);
                                doUpdatePreferences();
                            });
                    return true;
                }
                case R.string.pref_key__git_access_token: {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                        Toast.makeText(getActivity(), R.string.git_secure_storage_unavailable, Toast.LENGTH_LONG).show();
                        return true;
                    }
                    MarkorDialogFactory.showGitTextSettingDialog(getActivity(), R.string.git_access_token,
                            getActivity().getString(R.string.hidden_password), true, value -> {
                                _appSettings.setGitAccessToken(value);
                                doUpdatePreferences();
                            });
                    return true;
                }
                case R.string.pref_key__git_ssh_key_path: {
                    MarkorFileBrowserFactory.showFileDialog(new GsFileBrowserOptions.SelectionListenerAdapter() {
                        @Override
                        public void onFsViewerSelected(String request, File file, final Integer lineNumber) {
                            _appSettings.setGitSshKeyPath(file.getAbsolutePath());
                            doUpdatePreferences();
                        }

                        @Override
                        public void onFsViewerConfig(GsFileBrowserOptions.Options dopt) {
                            dopt.titleText = R.string.git_ssh_key_path;
                            dopt.rootFolder = Environment.getExternalStorageDirectory();
                            dopt.newDirButtonEnable = false;
                            // 私钥文件没有扩展名，不能按类型过滤
                            dopt.fileOverallFilter = (context, f) -> true;
                        }
                    }, fragManager, getActivity(), null);
                    return true;
                }
                case R.string.pref_key__git_ssh_passphrase: {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                        Toast.makeText(getActivity(), R.string.git_secure_storage_unavailable, Toast.LENGTH_LONG).show();
                        return true;
                    }
                    MarkorDialogFactory.showGitTextSettingDialog(getActivity(), R.string.git_ssh_passphrase,
                            getActivity().getString(R.string.hidden_password), true, value -> {
                                _appSettings.setGitSshPassphrase(value);
                                doUpdatePreferences();
                            });
                    return true;
                }
                case R.string.pref_key__quicknote_filepath: {
                    MarkorFileBrowserFactory.showFileDialog(new GsFileBrowserOptions.SelectionListenerAdapter() {
                        @Override
                        public void onFsViewerSelected(String request, File file, final Integer lineNumber) {
                            _appSettings.setQuickNoteFile(file);
                            _appSettings.setRecreateMainRequired(true);
                            doUpdatePreferences();
                        }

                        @Override
                        public void onFsViewerConfig(GsFileBrowserOptions.Options dopt) {
                            dopt.titleText = R.string.quicknote;
                            dopt.rootFolder = _appSettings.getNotebookDirectory();
                            dopt.newDirButtonEnable = false;
                        }
                    }, fragManager, getActivity(), MarkorFileBrowserFactory.IsMimeText);
                    return true;
                }
                case R.string.pref_key__todo_filepath: {
                    MarkorFileBrowserFactory.showFileDialog(new GsFileBrowserOptions.SelectionListenerAdapter() {
                        @Override
                        public void onFsViewerSelected(String request, File file, final Integer lineNumber) {
                            _appSettings.setTodoFile(file);
                            _appSettings.setRecreateMainRequired(true);
                            doUpdatePreferences();
                        }

                        @Override
                        public void onFsViewerConfig(GsFileBrowserOptions.Options dopt) {
                            dopt.titleText = R.string.todo;
                            dopt.rootFolder = _appSettings.getNotebookDirectory();
                            dopt.newDirButtonEnable = false;
                        }
                    }, fragManager, getActivity(), MarkorFileBrowserFactory.IsMimeText);
                    return true;
                }
                case R.string.pref_key__basic_color_scheme_markor: {
                    _appSettings.setEditorBasicColor(true, R.color.dark__primary_text, R.color.dark__editor_bg);
                    _appSettings.setEditorBasicColor(false, R.color.light__primary_text, R.color.light__editor_bg);
                    _appSettings.setRecreateMainRequired(true);
                    break;
                }
                case R.string.pref_key__basic_color_scheme_blackorwhite: {
                    _appSettings.setEditorBasicColor(true, R.color.white, R.color.black);
                    _appSettings.setEditorBasicColor(false, R.color.black, R.color.white);
                    _appSettings.setRecreateMainRequired(true);
                    break;
                }
                case R.string.pref_key__basic_color_scheme_solarized: {
                    _appSettings.setEditorBasicColor(true, R.color.solarized_fg, R.color.solarized_bg_dark);
                    _appSettings.setEditorBasicColor(false, R.color.solarized_fg, R.color.solarized_bg_light);
                    _appSettings.setRecreateMainRequired(true);
                    break;
                }
                case R.string.pref_key__basic_color_scheme_gruvbox: {
                    _appSettings.setEditorBasicColor(true, R.color.gruvbox_fg_dark, R.color.gruvbox_bg_dark);
                    _appSettings.setEditorBasicColor(false, R.color.gruvbox_fg_light, R.color.gruvbox_bg_light);
                    _appSettings.setRecreateMainRequired(true);
                    break;
                }
                case R.string.pref_key__basic_color_scheme_nord: {
                    _appSettings.setEditorBasicColor(true, R.color.nord_fg_dark, R.color.nord_bg_dark);
                    _appSettings.setEditorBasicColor(false, R.color.nord_fg_light, R.color.nord_bg_light);
                    _appSettings.setRecreateMainRequired(true);
                    break;
                }
                case R.string.pref_key__basic_color_scheme_greenscale: {
                    _appSettings.setEditorBasicColor(true, R.color.green_dark, R.color.black);
                    _appSettings.setEditorBasicColor(false, R.color.green_light, R.color.white);
                    _appSettings.setRecreateMainRequired(true);
                    break;
                }
                case R.string.pref_key__basic_color_scheme_sepia: {
                    _appSettings.setEditorBasicColor(true, R.color.sepia_bg_light__fg_dark, R.color.sepia_fg_light__bg_dark);
                    _appSettings.setEditorBasicColor(false, R.color.sepia_fg_light__bg_dark, R.color.sepia_bg_light__fg_dark);
                    _appSettings.setRecreateMainRequired(true);
                    break;
                }
                case R.string.pref_key__plaintext__reorder_actions:
                case R.string.pref_key__asciidoc__reorder_actions:
                case R.string.pref_key__markdown__reorder_actions:
                case R.string.pref_key__wikitext_reorder_actions:
                case R.string.pref_key__orgmode__reorder_actions:
                case R.string.pref_key__todotxt__reorder_actions: {
                    startActivity(new Intent(getActivity(), ActionButtonSettingsActivity.class).putExtra(ActionButtonSettingsActivity.EXTRA_FORMAT_KEY, keyResId));
                    break;
                }
                case R.string.pref_key__set_encryption_password: {
                    MarkorDialogFactory.showSetPasswordDialog(getActivity());
                    break;
                }
                case R.string.pref_key__backup_settings: {
                    BackupUtils.showBackupWriteToDialog(getContext(), getFragmentManager());
                    break;
                }
                case R.string.pref_key__restore_settings: {
                    BackupUtils.showBackupSelectFromDialog(getContext(), getFragmentManager());
                    break;
                }
            }

            if (key.startsWith("pref_key__editor_basic_color_scheme") && !key.contains("_fg_") && !key.contains("_bg_")) {
                _appSettings.setRecreateMainRequired(true);
                doUpdatePreferences();
            }
            return null;
        }

        @Override
        public boolean isDividerVisible() {
            return false;
        }

        /** 顶层入口行 → 对应子页的 XML；不是入口行返回 0 */
        private static int subScreenXmlFor(final String key) {
            switch (key) {
                case "nav_general":
                    return R.xml.preferences_general;
                case "nav_other":
                    return R.xml.preferences_other;
                case "nav_edit":
                    return R.xml.preferences_edit;
                case "nav_view":
                    return R.xml.preferences_view;
                case "nav_markdown":
                    return R.xml.preferences_format_markdown;
                case "nav_todo":
                    return R.xml.preferences_format_todo;
                case "nav_wikitext":
                    return R.xml.preferences_format_wikitext;
                case "nav_plaintext":
                    return R.xml.preferences_format_plaintext;
                case "nav_asciidoc":
                    return R.xml.preferences_format_asciidoc;
                case "nav_orgmode":
                    return R.xml.preferences_format_orgmode;
                default:
                    return 0;
            }
        }

        private void refreshEditorColorPreferences() {
            updateColorPreference(R.string.pref_key__basic_color_scheme__bg_light, _appSettings.getInt(R.string.pref_key__basic_color_scheme__bg_light, _cu.rcolor(getContext(), R.color.background)));
            updateColorPreference(R.string.pref_key__basic_color_scheme__fg_light, _appSettings.getInt(R.string.pref_key__basic_color_scheme__fg_light, _cu.rcolor(getContext(), R.color.primary_text)));
            updateColorPreference(R.string.pref_key__basic_color_scheme__bg_dark, _appSettings.getInt(R.string.pref_key__basic_color_scheme__bg_dark, _cu.rcolor(getContext(), R.color.background)));
            updateColorPreference(R.string.pref_key__basic_color_scheme__fg_dark, _appSettings.getInt(R.string.pref_key__basic_color_scheme__fg_dark, _cu.rcolor(getContext(), R.color.primary_text)));
        }

        private void updateColorPreference(@StringRes int key, int color) {
            Preference pref = findPreference(key);
            if (pref instanceof ColorPreference) {
                try {
                    ((ColorPreference) pref).setColor(color);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
