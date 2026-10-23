import io

p = "app/src/main/java/net/gsantner/markor/activity/MainActivity.java"
t = io.open(p, encoding="utf-8").read()
orig = t

t = t.replace("    private static final int PAGE_COUNT = 3;",
              "    private static final int PAGE_COUNT = 2;")

t = t.replace("""    private GsFileBrowserFragment _notebook;
    private DocumentEditAndViewFragment _quicknote, _todo;""",
              """    private GsFileBrowserFragment _notebook;
    private EditorWorkspaceFragment _editor;""")

t = t.replace("""            if (itemId == R.id.nav_quicknote) {
                showLargeFileOpenToastIfNeeded(_appSettings.getQuickNoteFile());
            } else if (itemId == R.id.nav_todo) {
                showLargeFileOpenToastIfNeeded(_appSettings.getTodoFile());
            }
""", "")

t = t.replace("""    public int tabIdToPos(final int id) {
        if (id == R.id.nav_notebook) return 0;
        if (id == R.id.nav_todo) return 1;
        if (id == R.id.nav_quicknote) return 2;
        return -1; // 设置只是一个入口，不是页面
    }""",
              """    public int tabIdToPos(final int id) {
        if (id == R.id.nav_notebook) return 0;
        if (id == R.id.nav_editor) return 1;
        return -1; // 设置只是一个入口，不是页面
    }""")

t = t.replace("""    public String getPosTitle(final int pos) {
        if (pos == 0) return getFileBrowserTitle();
        if (pos == 1) return getString(R.string.todo);
        if (pos == 2) return getString(R.string.quicknote);
        return "";
    }

    public GsFragmentBase<?, ?> getPosFragment(final int pos) {
        if (pos == 0) return _notebook;
        if (pos == 1) return _todo;
        if (pos == 2) return _quicknote;
        return null;
    }""",
              """    public String getPosTitle(final int pos) {
        if (pos == 0) return getFileBrowserTitle();
        if (pos == 1) {
            final File file = _editor != null ? _editor.getCurrentFile() : null;
            return file != null ? file.getName() : "";
        }
        return "";
    }

    public GsFragmentBase<?, ?> getPosFragment(final int pos) {
        // 编辑工作区不是 GsFragmentBase 的子类，按键事件由它自己处理
        return pos == 0 ? _notebook : null;
    }""")

t = t.replace("""    public void hideKeyboard() {
        if (_quicknote != null) {
            _cu.showSoftKeyboard(this, false, _quicknote.getEditor());
        }
        if (_todo != null) {
            _cu.showSoftKeyboard(this, false, _todo.getEditor());
        }
    }""",
              """    public void hideKeyboard() {
        if (_editor != null) {
            _editor.hideKeyboard();
        }
    }""")

t = t.replace("""            manager.putFragment(outState, Integer.toString(R.id.nav_notebook), _notebook);
            manager.putFragment(outState, Integer.toString(R.id.nav_quicknote), _quicknote);
            manager.putFragment(outState, Integer.toString(R.id.nav_todo), _todo);""",
              """            manager.putFragment(outState, Integer.toString(R.id.nav_notebook), _notebook);
            if (_editor != null) {
                manager.putFragment(outState, Integer.toString(R.id.nav_editor), _editor);
            }""")

t = t.replace("""            _notebook = (GsFileBrowserFragment) manager.getFragment(savedInstanceState, Integer.toString(R.id.nav_notebook));
            _quicknote = (DocumentEditAndViewFragment) manager.getFragment(savedInstanceState, Integer.toString(R.id.nav_quicknote));
            _todo = (DocumentEditAndViewFragment) manager.getFragment(savedInstanceState, Integer.toString(R.id.nav_todo));

            if (_sectionsAdapter != null) {
                _sectionsAdapter.restoreFragment(tabIdToPos(R.id.nav_notebook));
                _sectionsAdapter.restoreFragment(tabIdToPos(R.id.nav_quicknote));
                _sectionsAdapter.restoreFragment(tabIdToPos(R.id.nav_todo));
            }""",
              """            _notebook = (GsFileBrowserFragment) manager.getFragment(savedInstanceState, Integer.toString(R.id.nav_notebook));
            _editor = (EditorWorkspaceFragment) manager.getFragment(savedInstanceState, Integer.toString(R.id.nav_editor));

            if (_sectionsAdapter != null) {
                _sectionsAdapter.restoreFragment(tabIdToPos(R.id.nav_notebook));
                _sectionsAdapter.restoreFragment(tabIdToPos(R.id.nav_editor));
            }""")

t = t.replace("""            final GsFragmentBase<?, ?> frag;
            final int id = tabIdFromPos(pos);
            if (id == R.id.nav_quicknote) {
                frag = _quicknote = DocumentEditAndViewFragment.newInstance(new Document(_appSettings.getQuickNoteFile()), -1, false);
            } else if (id == R.id.nav_todo) {
                frag = _todo = DocumentEditAndViewFragment.newInstance(new Document(_appSettings.getTodoFile()), -1, false);
            } else {
                frag = _notebook = GsFileBrowserFragment.newInstance();
            }
            frag.setMenuVisibility(false);
            return frag;""",
              """            final Fragment frag;
            final int id = tabIdFromPos(pos);
            if (id == R.id.nav_editor) {
                frag = _editor = new EditorWorkspaceFragment();
            } else {
                frag = _notebook = GsFileBrowserFragment.newInstance();
            }
            frag.setMenuVisibility(false);
            return frag;""")

t = t.replace("""        String title = "";
        final DocumentEditAndViewFragment editor = pos == tabIdToPos(R.id.nav_quicknote) ? _quicknote
                : pos == tabIdToPos(R.id.nav_todo) ? _todo : null;
        if (editor != null && editor.getDocument() != null) {
            title = editor.getDocument().title;
        }
        setTitle(title);""",
              """        final File file = _editor != null && pos == tabIdToPos(R.id.nav_editor) ? _editor.getCurrentFile() : null;
        setTitle(file != null ? file.getName() : "");""")

io.open(p, "w", encoding="utf-8", newline="\n").write(t)
print("changed" if t != orig else "NO CHANGE")
