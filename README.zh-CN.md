**[English](README.md) | 简体中文**

# Markor

<img src="/app/src/main/ic_launcher-web.png" align="left" width="128" hspace="10" vspace="10">
<b>文本编辑器 —— 笔记与待办（Android）</b>。
<br/>轻量简洁，支持 Markdown、todo.txt、Zim 等多种格式！<br/><br/>

**下载：** [F-Droid](https://f-droid.org/repository/browse/?fdid=net.gsantner.markor) · [GitHub Releases](https://github.com/AlexIllinois2/markor/releases)

Markor 是一款 Android 文本编辑器。本项目希望做一个通用、灵活且轻量的编辑器。
Markor 使用 Markdown、todo.txt 这类简单的标记格式来做笔记和任务管理。
它处理文本的能力很通用：也可以用来收藏书签、复制到剪贴板、快速打开文本中的链接等等。
生成的文件与任何平台上的纯文本软件互通。
Markor 是开放开发的自由软件，欢迎社区贡献。

> **本仓库为个人 fork**，基于上游 Markor v2.16.1，新增了内置的 Git 笔记同步、发布签名与构建修复。
> 详细改动见下文「本地修改」；英文说明见 [README.md](README.md)。

## 功能

📝 用简单的标记格式创建笔记、管理待办
<br/>🌲 完全离线工作 —— 随时随地
<br/>👌 与任何平台的纯文本软件互通 —— 用记事本或 vim 编辑、用 grep 过滤、转 PDF 或打包 zip
<br/>
<br/>🖍 语法高亮与格式相关操作 —— 快速插入图片和待办
<br/>👀 转换、预览并以 HTML / PDF 分享文档
<br/>
<br/>📚 Notebook：把所有文档放在同一个文件系统目录中
<br/>📓 QuickNote：随手快速记录
<br/>☑️ To-Do：写下你的待办
<br/>🖍 支持格式：Markdown、todo.txt、Zim/WikiText、纯文本、[csv](doc/2023-06-02-csv-readme.md)、ics、ini、json、toml、vcf、yaml
<br/>📋 复制到剪贴板：复制任意文本，包括分享进 Markor 的文本
<br/>💡 Notebook 是文档根目录，可改为文件系统上的任意位置；QuickNote 与 To-Do 都是单独的文本文件
<br/>
<br/>🎨 高度可定制，支持深色主题
<br/>💾 自动保存，支持撤销/重做
<br/>👌 无广告、无多余权限
<br/>🌎 语言选择 —— 可使用与系统不同的语言
<br/>
<br/>🔃 Markor 是离线应用。它可以配合同步类应用使用，但同步需由这些应用自行完成。
<br/>🔒 可用 AES256 加密文本文件。需在设置中设置密码，并运行 Android Marshmallow 或更高版本。桌面端可用 [jpencconverter](https://gitlab.com/opensource21/jpencconverter) 加解密。注意仅文本会被加密，图片与附件不会。

## 本地修改

本仓库为个人 fork，在上游 Markor v2.16.1 基础上做了以下改动：

### Git 笔记同步（新增功能）

基于 JGit 的笔记目录自动同步：

* **保存即提交**：文件保存后立刻 commit；拉取/推送走 3 秒防抖，冷启动时自动拉取一次。
* **设置 → Git**：同步开关、提交作者名/邮箱、自动同步间隔、提交信息模板，以及 HTTPS 访问令牌 / SSH 私钥认证。未配置作者时以占位身份提交并一次性提醒，不阻断同步。
* **克隆简化**：克隆时只填 `user/repo`，自动拼成 `ssh://git@ssh.github.com:443/user/repo.git`（22 端口被墙也能用）；也接受完整的 `https://`、`ssh://` 和 `git@host:path` 地址。
* **冲突不覆盖远端**：合并遇到冲突时把冲突标记内容原样提交，不做 ours/theirs 选边，也不会用本地覆盖远端；冲突状态在同步完成后明确提示。
* **远端更新刷新提示**：拉取/合并带进工作区的文件会被记住，编辑页显示常驻刷新提示（只有点「刷新」才消失）。用户没在编辑（输入法未弹出）时改为静默重载，不打断阅读或输入；Android 11 以下以底部 inset 判断键盘是否弹出。
* **静默失败修复**：无界面挂载时用 Toast 兜底提示；非自动操作在「不是 git 仓库」「上次操作还在执行」时明确报错。
* 主要代码：[model/git/](app/src/main/java/net/gsantner/markor/model/git/)（GitNotebook、GitRepo、GitSyncJob），并接入 ApplicationObject、MainActivity、Document、GsFileBrowserFragment、MarkorDialogFactory、SettingsActivity；新增字符串已同步中英文。
* 依赖：`org.eclipse.jgit:5.13.3` + `com.github.mwiede:jsch:0.2.21`（SSH 公钥认证）。

### Release 签名与 CI

* [app/build.gradle](app/build.gradle) 增加 `signingConfigs.release`，从 `keystore.properties` 读取；该文件缺失时保持原有不签名行为。`markor-release.jks` 与 `keystore.properties` 均已加入 `.gitignore`。
* [release.yml](.github/workflows/release.yml)：推送 `v*` 标签时由 CI 用标准 Android debug key 生成 `keystore.properties` 完成签名，构建后通过 `apksigner verify` 校验，产物可直接安装覆盖升级。

### 构建与打包修复

* 根 [build.gradle](build.gradle)：`maven.google.com` 在部分网络下 TCP 挂死，替换为官方现行地址 `dl.google.com/dl/android/maven2/`。
* `packagingOptions` 排除 `plugin.properties`（jgit 与 jgit.ssh.jsch 各带一份 OSGi 元数据，内容冲突）。
* [proguard-rules.pro](app/proguard-rules.pro)：整包 keep `com.jcraft.jsch.**` 与 `com.jcraft.jzlib.**` —— JSch 的算法按类名反射加载，R8 裁剪后 SSH 连接即报 `ClassNotFoundException`。

### 编辑器「从文末开始」开关修复

* 上游该开关无任何实际作用。现语义为「记忆优先」：优先恢复上次编辑位置，仅对没有编辑记忆的文件按开关决定文末/开头；默认值统一为 false（未勾选 = 从开头开始）。

## 开发

用 git 克隆项目，然后用 [Android Studio](https://developer.android.com/studio)（推荐）打开，按需安装 Android SDK 依赖。
也可以使用你喜欢的任意纯文本编辑器。

项目内提供 Makefile，便于测试、lint、构建、安装并在设备上运行应用，详见 Makefile。
二进制（.apk）、日志、测试结果等输出位于 `dist/` 目录。例如：`make all install run`。

代码风格为 [AOSP Java Code Style](https://source.android.com/source/code-style#follow-field-naming-conventions)。提交前或创建 Pull Request 前，请先用 Android Studio 的 _auto reformat_ 格式化。

### 技术栈 / 依赖

* Java、Android SDK、AndroidX
* 不依赖 NDK，单个 APK 覆盖所有 Android 支持的架构
* 编辑器：基于 Android EditText 的高级组件
* 预览：Android WebView
* 编辑器语法高亮：针对所有支持格式的自研实现
* Markdown 解析：[flexmark-java](https://github.com/vsch/flexmark-java/wiki/Extensions)
* Zim/WikiText 解析：自研实现，转译成 Markdown
* todo.txt 解析：自研实现
* 二进制支持：WebView 的 html img/audio/video，支持常见格式
* CI/CD：GitHub Actions
* 构建系统：Gradle、Makefile

## 贡献

* **编程**
  项目始终欢迎贡献与合并请求。可以在 [issue tracker](https://github.com/gsantner/markor/issues) 查看待处理问题，尤其是 [good first issues](https://github.com/gsantner/markor/issues?q=is%3Aopen+is%3Aissue+label%3A%22good+first+issue%22)。
* **功能建议与讨论**
  在 [Discussions](https://github.com/gsantner/markor/discussions) 发起讨论。
* **Bug 报告**
  在 [Issues](https://github.com/gsantner/markor/issues) 反馈。请先搜索是否已有类似 [issue](https://github.com/gsantner/markor/issues?q=) 与 [讨论](https://github.com/gsantner/markor/discussions?discussions_q=)。若非 Bug，请前往讨论区。
* **本地化**
  在 [Crowdin](https://crowdin.com/project/markor) 免费参与翻译。

## 隐私

除非你自己生成的内容引用了外部资源（例如通过 URL 引用外部图片），Markor 不会使用你的网络连接。
应用完全离线工作，无需网络连接！
不会向作者或任何第三方分享个人数据。
可以通过应用内的分享按钮把文件分享给其他应用。
文件保存在用户可选的本地目录，默认是内部存储的 `Documents` 目录。

### Android 权限

* WRITE_EXTERNAL_STORAGE、READ_EXTERNAL_STORAGE
  读取与写入存储中的文件。
* INTERNET
  用户生成的内容可能需要从网络加载数据。
* INSTALL_SHORTCUT
  在桌面创建快捷方式，用于在 Markor 中打开文件/目录。

## 许可证

应用代码采用 Apache 2.0 许可。
本地化与翻译文件（string\*.xml）以及 samples 采用 CC0 1.0（公有领域）许可。
项目基于已停止维护的 writeily 与 writeily-pro 项目。

## 更多

* 更新日志：[CHANGELOG.md](CHANGELOG.md) · [NEWS.md](NEWS.md)
* 常见问题（英文）：[README.md#faq](README.md#faq)
* 上游项目：[gsantner/markor](https://github.com/gsantner/markor)
