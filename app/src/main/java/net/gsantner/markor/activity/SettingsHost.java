package net.gsantner.markor.activity;

/**
 * 设置页的"宿主"：谁负责把二级设置页（通用/编辑/各格式…）显示出来。
 * <p>
 * 设置既可以是独立 Activity（外部入口、系统"应用设置"跳转），也可以是主界面左右滑动的一页，
 * 两种宿主共用同一套设置片段，所以这里只抽一个"显示子页"的动作。
 */
public interface SettingsHost {
    void showSettingsSubScreen(int xmlRes, CharSequence title);
}
