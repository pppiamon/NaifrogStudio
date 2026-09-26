package com.naifrog.studio;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.core.content.FileProvider;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    static final int GREEN = Color.rgb(38, 84, 60), INK = Color.rgb(37, 50, 40), MUTED = Color.rgb(100, 113, 101);
    static final int BG = Color.rgb(247, 248, 242), LIME = Color.rgb(222, 235, 181), LINE = Color.rgb(224, 229, 216);
    StudioState state;
    LinearLayout root, content, navigation;
    ScrollView scroll;
    EditCanvas editor;
    EditCanvas retainedEditor;
    int tab = 0;
    private boolean showingOriginal;
    private Bitmap renderedOriginal, renderedResult;
    private int lastFace = -1, tool = 0;
    private float brush = 24;
    private Bitmap toolSource;
    private boolean initialToolChosen;
    private static final int PICK_PHOTO = 10, PICK_REFERENCES = 11, EXPORT = 12;
    private File exportFile;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        state = StudioState.get(this);
        if (saved != null) tab = saved.getInt("tab", 0);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        render();
        handleConnectionIntent(getIntent());
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleConnectionIntent(intent); }
    void handleConnectionIntent(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data != null && "naifrog".equals(data.getScheme()) && "connect".equals(data.getHost())) importConnection(data.toString());
    }
    void importConnection(String initial) {
        if (state.busy || state.account.busy) { toast("当前请求结束后可更新连接"); return; }
        LinearLayout form = column(); form.setPadding(dp(24), dp(12), dp(24), dp(12));
        full(form, text("粘贴朋友或服务提供者发来的邀请链接或连接码。连接成功后，登录你自己的 Codex 账号。", 13, MUTED, false), -2, 0);
        EditText code = new EditText(this); code.setHint("邀请链接 / NAIFROG1.…"); code.setMinLines(3); code.setMaxLines(5); code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        code.setText(initial == null ? "" : initial); full(form, code, -2, 12);
        full(form, button("粘贴连接码", false, () -> {
            ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if (clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) code.setText(clipboard.getPrimaryClip().getItemAt(0).coerceToText(this));
        }), 48, 8);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("连接服务").setView(form).setPositiveButton("连接", null).setNegativeButton("关闭", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                org.json.JSONObject profile = ServiceConnection.parse(code.getText().toString());
                Button connect = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                connect.setEnabled(false); connect.setText("正在验证…"); code.setError(null);
                state.control.execute(() -> {
                    try {
                        state.connection.checkProfile(profile);
                        runOnUiThread(() -> {
                            if (!dialog.isShowing()) return;
                            try {
                                if (state.busy || state.account.busy) throw new IOException("请等待当前请求结束后再连接");
                                state.connection.install(profile); state.account.changedService(); dialog.dismiss(); tab = 3; render(); toast("连接成功，可以登录自己的 Codex 账号");
                            } catch (Exception e) { code.setError(e.getMessage()); connect.setEnabled(true); connect.setText("连接"); }
                        });
                    } catch (Exception e) { runOnUiThread(() -> { code.setError(e.getMessage()); connect.setEnabled(true); connect.setText("连接"); }); }
                });
            } catch (Exception e) { code.setError(e.getMessage()); }
        }));
        dialog.show();
    }
    void inviteUsers() {
        toast("正在检查共享入口…");
        state.control.execute(() -> {
            try {
                String link = state.connection.invitation();
                runOnUiThread(() -> {
                    Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link);
                    startActivity(Intent.createChooser(share, "邀请他人使用奶蛙照相馆"));
                });
            } catch (Exception e) { runOnUiThread(() -> toast(e.getMessage())); }
        });
    }
    @Override protected void onResume() { super.onResume(); state.listener = this::render; render(); if (state.provider().equals("codex")) state.account.refresh(true); }
    @Override protected void onPause() { super.onPause(); state.listener = null; if (editor != null && !state.busy) { state.selection = editor.getMask(); state.persistSelection(); } }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putInt("tab", tab); }

    int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    GradientDrawable shape(int color, int radius) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius)); return g; }
    TextView text(String label, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(label); t.setTextSize(size); t.setTextColor(color);
        t.setFontFeatureSettings("kern"); t.setLineSpacing(dp(3), 1f);
        if (bold) t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); return t;
    }
    Button button(String label, boolean primary, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14); b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(primary ? Color.WHITE : GREEN); b.setBackground(shape(primary ? GREEN : Color.WHITE, 16));
        b.setPadding(dp(12), dp(8), dp(12), dp(8)); b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48));
        b.setOnClickListener(v -> action.run()); b.setStateListAnimator(null); return b;
    }
    void full(LinearLayout parent, View view, int height, int marginTop) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, height < 0 ? height : dp(height)); p.topMargin = dp(marginTop); parent.addView(view, p);
    }
    void gap(LinearLayout parent, int h) { full(parent, new View(this), h, 0); }
    void title(String number, String title, String detail) {
        TextView n = text(number + "  /  " + title, 17, INK, true); full(content, n, -2, 22);
        if (!detail.isEmpty()) full(content, text(detail, 12, MUTED, false), -2, 5);
    }
    LinearLayout card(int color) { LinearLayout c = column(); c.setPadding(dp(20), dp(20), dp(20), dp(20)); c.setBackground(shape(color, 24)); return c; }
    void render() {
        if (isFinishing() || isDestroyed()) return;
        int oldY = scroll == null ? 0 : scroll.getScrollY();
        retainedEditor = renderedOriginal == state.original && state.result == null && editor != null && state.selection == editor.getMask() ? editor : null;
        if (retainedEditor != null && retainedEditor.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup)retainedEditor.getParent()).removeView(retainedEditor);
        editor = null;
        root = column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> { v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom()); return insets; });
        LinearLayout header = row(); header.setPadding(dp(24), dp(14), dp(20), dp(12));
        LinearLayout brand = column();
        TextView brandName = text("奶蛙照相馆", 21, INK, true); brand.addView(brandName);
        TextView eyebrow = text("NAIFROG  /  PHOTO STUDIO", 9, MUTED, true); eyebrow.setLetterSpacing(.15f); brand.addView(eyebrow);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        Button settings = button("设置", false, this::settings); settings.setTextSize(12); header.addView(settings, new LinearLayout.LayoutParams(dp(64), dp(48)));
        root.addView(header);
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false); scroll.setVerticalScrollBarEnabled(false);
        content = column(); content.setPadding(dp(24), dp(8), dp(24), dp(28)); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (tab == 0) { if (state.original == null) home(); else if (state.result != null) resultPage(); else editorPage(); }
        else if (tab == 1) libraryPage(); else if (tab == 2) galleryPage(); else accountPage();
        navigation = row(); navigation.setPadding(dp(18), dp(10), dp(18), dp(10)); navigation.setBackgroundColor(Color.WHITE);
        String[] labels = {"创作", "角色库", "作品", "账号"};
        for (int i = 0; i < 4; i++) {
            final int index = i; Button nav = button(labels[i], false, () -> { tab = index; showingOriginal = false; render(); scroll.scrollTo(0, 0); });
            nav.setTextColor(tab == i ? GREEN : MUTED); nav.setBackground(shape(tab == i ? LIME : Color.WHITE, 16));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1); p.setMargins(dp(3), 0, dp(3), 0); navigation.addView(nav, p);
        }
        root.addView(navigation);
        setContentView(root); root.requestApplyInsets();
        renderedOriginal = state.original; renderedResult = state.result;
        scroll.post(() -> scroll.scrollTo(0, oldY));
    }
    void home() {
        LinearLayout hero = card(LIME);
        TextView tag = text("PORTRAIT LAB     ·     01", 10, GREEN, true); tag.setLetterSpacing(.16f); full(hero, tag, -2, 0);
        full(hero, text("把这一刻，\n变成奶蛙。", 34, GREEN, true), -2, 22);
        full(hero, text("还是你的发型、穿搭和姿势。\n给照片里的自己，换一个可爱的身份。", 13, GREEN, false), -2, 12);
        full(hero, new PhotoArt(this), 148, 10);
        Button start = button("＋  选择照片或角色图片", true, () -> pick(false)); start.setEnabled(!state.busy); full(hero, start, 54, 6);
        TextView foot = text("JPG / PNG / WEBP / HEIC", 10, GREEN, false); foot.setGravity(Gravity.CENTER); full(hero, foot, -2, 10);
        full(content, hero, -2, 8);
        full(content, button(state.provider().equals("codex") ? state.account.loggedIn ? "Codex 已登录  ·  查看账号" : "登录 Codex  ·  使用自己的账号生成" : "当前使用 API  ·  切换生成方式", false, this::showAccount), 50, 12);
        LinearLayout notes = row();
        LinearLayout a = card(Color.WHITE), b = card(Color.WHITE);
        a.addView(text("01  保留这一刻", 14, INK, true)); full(a, text("选区外画面\n逐像素保留", 12, MUTED, false), -2, 8);
        b.addView(text("02  认准奶蛙", 14, INK, true)); full(b, text("以你的参考图\n确定角色外观", 12, MUTED, false), -2, 8);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1); left.rightMargin = dp(6); notes.addView(a, left);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1); right.leftMargin = dp(6); notes.addView(b, right); full(content, notes, -2, 14);
        title("准备", "你的奶蛙角色库", state.referenceFiles().isEmpty() ? "先导入一张你喜欢的奶蛙，之后就能重复使用。" : "已收录 " + state.referenceFiles().size() + " 张奶蛙参考图");
        full(content, button("导入奶蛙参考图  →", false, () -> pick(true)), 50, 12);
        statusBlock();
    }
    void editorPage() {
        LinearLayout heading = row(); heading.addView(text("创作一张奶蛙照片", 25, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button replace = button("换照片", false, () -> pick(false)); replace.setEnabled(!state.busy && !state.hasPending()); heading.addView(replace); full(content, heading, -2, 5);
        statusBlock();
        editor = retainedEditor != null ? retainedEditor : new EditCanvas(this);
        if (toolSource != state.original) { toolSource = state.original; initialToolChosen = false; }
        if (!state.detecting && !initialToolChosen) { tool = state.faces.isEmpty() ? 3 : 0; initialToolChosen = true; }
        if (retainedEditor == null) editor.setImage(state.original, state.selection);
        if (state.selection == null && !state.faces.isEmpty()) editor.selectFace(state.faces.get(state.selectedFace));
        state.selection = editor.getMask(); editor.mode = state.busy || state.hasPending() || state.detecting ? 0 : tool;
        editor.brushDp = brush; editor.onEdited = () -> state.selection = editor.getMask();
        editor.setBackground(shape(LINE, 20)); editor.setClipToOutline(true);
        full(content, editor, Math.min(420, Math.max(260, Math.round(320f * state.original.getHeight() / state.original.getWidth()))), 14);
        full(content, text("绿色区域会变成奶蛙 · 未选中的画面保留原样", 11, MUTED, false), -2, 8);
        if (state.faces.size() > 1) {
            title("人物", "选择要转换的人", "人物按照片从左到右排列");
            HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false); LinearLayout choices = row();
            for (int i = 0; i < state.faces.size(); i++) {
                final int index = i;
                Button face = button("人物 " + (i + 1), state.selectedFace == i, () -> {
                    state.selectedFace = index; editor.selectFace(state.faces.get(index)); state.selection = editor.getMask();
                    state.status = "检测到 " + state.faces.size() + " 位人物 · 已选第 " + (index + 1) + " 位"; render();
                }); face.setEnabled(!state.busy && !state.hasPending()); choices.addView(face);
            }
            hs.addView(choices); full(content, hs, -2, 10);
        }
        {
            title("01", "调整替换范围", state.faces.isEmpty() ? "拟人角色、卡通或漏检人像：拖动框选面部，再涂抹或擦除细调。" : "自动选中面部。也可框选、涂抹身体区域，擦除头发和衣服。");
            LinearLayout tools = row(); String[] names = {"查看", "框选", "涂抹", "擦除"}; int[] modes = {0, 3, 1, 2};
            for (int i = 0; i < names.length; i++) {
                final int mode = modes[i]; Button t = button(names[i], tool == mode, () -> { tool = mode; render(); });
                t.setEnabled(!state.busy && !state.hasPending() && !state.detecting); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1); p.setMargins(dp(3), 0, dp(3), 0); tools.addView(t, p);
            } full(content, tools, -2, 12);
            if (tool == 1 || tool == 2) {
                LinearLayout controls = row(); controls.addView(text("笔刷", 12, MUTED, false)); SeekBar size = new SeekBar(this); size.setMax(72); size.setProgress((int)brush - 8);
                size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar s, int p, boolean fromUser) { brush = p + 8; editor.brushDp = brush; }
                    public void onStartTrackingTouch(SeekBar s) {} public void onStopTrackingTouch(SeekBar s) {}
                }); controls.addView(size, new LinearLayout.LayoutParams(0, dp(48), 1)); full(content, controls, -2, 6);
            }
            LinearLayout actions = row(); Button undo = button("撤销", false, () -> editor.undo()); Button clear = button("清空选区", false, () -> editor.clearSelection());
            undo.setEnabled(!state.busy && !state.hasPending() && !state.detecting); clear.setEnabled(undo.isEnabled());
            actions.addView(undo, new LinearLayout.LayoutParams(0, dp(48), 1)); actions.addView(clear, new LinearLayout.LayoutParams(0, dp(48), 1));
            if (!state.faces.isEmpty()) {
                Button reset = button("重选面部", false, () -> editor.selectFace(state.faces.get(state.selectedFace))); reset.setEnabled(undo.isEnabled());
                actions.addView(reset, new LinearLayout.LayoutParams(0, dp(48), 1));
            }
            full(content, actions, -2, 7);
        }
        title("02", "选择奶蛙参考图", "参考图决定角色长相、颜色和面部特征。"); referenceStrip();
        title("03", "准备变身", "保留发型、服装、姿势、手势、背景与光照。");
        full(content, button(state.provider().equals("codex") ? "生成方式：Codex 账号  →" : "生成方式：可配置 API  →", false, this::showAccount), 48, 8);
        Button generate = button(state.busy ? "正在处理中…" : state.hasPending() ? "继续获取生成结果" : "✦  生成奶蛙照片", true, () -> {
            if (state.serviceUrl().isEmpty()) { settings(); return; }
            if (!state.hasPending() && (editor == null || !Images.hasSelection(editor.getMask()))) { toast("请先框选或涂抹需要转换的区域"); return; }
            if (!state.hasPending() && state.provider().equals("codex") && !state.account.loggedIn) { showAccount(); state.account.refresh(false); return; }
            if (editor != null) state.selection = editor.getMask(); state.generate();
        });
        generate.setEnabled(!state.busy && !state.detecting && (state.hasPending() || state.selectedReference != null));
        generate.setAlpha(generate.isEnabled() ? 1 : .5f); full(content, generate, 56, 16);
        if (state.hasPending()) full(content, button(state.cancelRequested ? "正在取消…" : "取消当前任务", false, state::discardPending), 48, 8);
        if (state.hasPending() && !state.busy && !state.error.isEmpty()) full(content, button("清除本地等待记录", false, state::forgetPending), 48, 8);
        if (state.selectedReference == null) full(content, text("导入奶蛙参考图后即可生成", 12, MUTED, false), -2, 8);
        if (state.serviceUrl().isEmpty()) full(content, button("连接生成服务  →", false, this::settings), 48, 8);
    }
    void showAccount() { tab = 3; render(); scroll.scrollTo(0, 0); }
    void accountPage() {
        full(content, text("你的创作账号", 27, INK, true), -2, 8);
        full(content, text("选择生成方式，继续你的奶蛙创作。", 13, MUTED, false), -2, 8);
        LinearLayout modes = row();
        String[] providers = {"codex", "api"}, labels = {"Codex 账号", "可配置 API"};
        for (int i = 0; i < providers.length; i++) {
            final String provider = providers[i];
            Button choice = button(labels[i], state.provider().equals(provider), () -> {
                state.preferences.edit().putString("provider", provider).apply(); render();
                if (provider.equals("codex")) state.account.refresh(true);
            });
            choice.setEnabled(!state.busy && !state.hasPending() && !state.account.busy);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(50), 1); p.setMargins(dp(3), 0, dp(3), 0); modes.addView(choice, p);
        }
        full(content, modes, -2, 22);
        if (state.provider().equals("codex")) {
            CodexAccount account = state.account;
            LinearLayout panel = card(Color.WHITE);
            full(panel, text(account.loggedIn ? "Codex 已连接" : "登录 Codex", 22, GREEN, true), -2, 0);
            full(panel, text(account.loggedIn ? account.email + (account.plan.isEmpty() ? "" : "\n" + account.plan.toUpperCase(Locale.ROOT)) : "使用你的 ChatGPT / Codex 账号。\n完成官方登录后，回到这里生成照片。", 13, MUTED, false), -2, 10);
            full(panel, text(account.status, 12, GREEN, false), -2, 16);
            if (!account.code.isEmpty() && !account.loggedIn) {
                TextView code = text(account.code, 28, GREEN, true); code.setGravity(Gravity.CENTER); code.setTextIsSelectable(true); code.setLetterSpacing(.12f);
                full(panel, code, -2, 18);
                full(panel, button("复制验证码并打开登录页", true, () -> openCodexLogin(account)), -2, 14);
                Button refresh = button("我已登录，刷新状态", false, () -> account.refresh(false)); refresh.setEnabled(!account.busy); full(panel, refresh, 48, 8);
            } else if (account.loggedIn) {
                full(panel, button("开始创作  →", true, () -> { tab = 0; render(); }), 50, 20);
                Button logout = button("退出 Codex 账号", false, account::logout); logout.setEnabled(!account.busy && !state.busy && !state.hasPending()); full(panel, logout, 48, 8);
            } else {
                Button login = button(account.busy ? "连接中…" : "登录 Codex", true, () -> {
                    if (state.serviceUrl().isEmpty()) { importConnection(""); return; }
                    account.startLogin();
                }); login.setEnabled(!account.busy); full(panel, login, 52, 20);
            }
            if (!account.error.isEmpty()) full(panel, text(account.error, 12, Color.rgb(158, 63, 39), false), -2, 14);
            full(content, panel, -2, 18);
            full(content, text("登录过程会打开官方页面。完成后返回奶蛙照相馆，账号状态会自动更新。", 12, MUTED, false), -2, 14);
        } else {
            LinearLayout panel = card(Color.WHITE);
            full(panel, text("使用图像 API", 22, GREEN, true), -2, 0);
            full(panel, text("连接已配置图像接口的生成服务，使用奶蛙参考图完成转换。", 13, MUTED, false), -2, 12);
            full(panel, button("设置生成服务", true, this::settings), 52, 20); full(content, panel, -2, 18);
        }
        full(content, button("连接服务 · 导入邀请链接", true, () -> importConnection("")), 50, 20);
        if (!state.serviceUrl().isEmpty()) full(content, button("邀请他人连接", false, this::inviteUsers), 50, 8);
        full(content, button("服务连接设置  →", false, this::settings), 50, 8);
    }
    void openCodexLogin(CodexAccount account) {
        if (!validUrl(account.verificationUrl)) { toast("登录地址未就绪，请重新获取验证码"); return; }
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Codex 验证码", account.code));
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(account.verificationUrl))); toast("验证码已复制"); }
        catch (ActivityNotFoundException e) { toast("请用浏览器打开：" + account.verificationUrl); }
    }
    void statusBlock() {
        if (state.busy || state.detecting) {
            LinearLayout busy = row(); busy.setPadding(dp(12), dp(12), dp(12), dp(12)); busy.setBackground(shape(LIME, 14));
            ProgressBar progress = new ProgressBar(this); busy.addView(progress, new LinearLayout.LayoutParams(dp(22), dp(22)));
            TextView s = text(state.status, 12, GREEN, false); s.setPadding(dp(12), 0, 0, 0); busy.addView(s, new LinearLayout.LayoutParams(0, -2, 1)); full(content, busy, -2, 12);
        } else if (state.original != null && tab == 0 && state.result == null) full(content, text(state.status, 12, GREEN, true), -2, 10);
        if (!state.error.isEmpty()) {
            TextView error = text(state.error, 13, Color.rgb(144, 74, 46), false); error.setPadding(dp(14), dp(12), dp(14), dp(12)); error.setBackground(shape(Color.rgb(253, 237, 220), 14)); full(content, error, -2, 12);
        }
    }
    void referenceStrip() {
        List<File> files = state.referenceFiles();
        if (files.isEmpty()) { full(content, button("＋  导入奶蛙形象图片", false, () -> pick(true)), 64, 12); return; }
        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false); LinearLayout strip = row();
        for (File f : files) {
            LinearLayout item = column(); item.setPadding(dp(5), dp(5), dp(5), dp(5));
            boolean selected = f.equals(state.selectedReference); item.setBackground(shape(selected ? LIME : Color.WHITE, 18));
            ImageView image = new ImageView(this); image.setImageBitmap(Images.thumbnail(f)); image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setContentDescription("奶蛙参考图 " + (files.indexOf(f) + 1));
            item.addView(image, new LinearLayout.LayoutParams(dp(88), dp(86)));
            TextView label = text(selected ? "✓ 已选择" : "选择这张", 10, GREEN, selected); label.setGravity(Gravity.CENTER); item.addView(label);
            item.setOnClickListener(v -> { if (!state.busy && !state.hasPending()) state.chooseReference(f); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.rightMargin = dp(10); strip.addView(item, p);
        }
        Button add = button("＋\n添加", false, () -> pick(true)); add.setEnabled(!state.busy && !state.hasPending()); strip.addView(add, new LinearLayout.LayoutParams(dp(72), dp(114)));
        hs.addView(strip); full(content, hs, -2, 12);
    }
    void libraryPage() {
        full(content, text("奶蛙的每一面", 29, INK, true), -2, 12);
        full(content, text("收好你的角色参考，下一次创作直接选用。", 13, MUTED, false), -2, 8);
        Button add = button("＋  批量导入参考图", true, () -> pick(true)); add.setEnabled(!state.busy && !state.hasPending()); full(content, add, 54, 20);
        statusBlock(); List<File> files = state.referenceFiles();
        if (files.isEmpty()) {
            LinearLayout empty = card(Color.WHITE); full(empty, text("角色库还空着", 23, GREEN, true), -2, 0);
            full(empty, text("把你的奶蛙形象图片放进来。\n每次创作选择一张，让角色外观保持一致。", 14, MUTED, false), -2, 12); full(content, empty, -2, 24);
        }
        for (File f : files) {
            LinearLayout c = card(Color.WHITE); ImageView image = new ImageView(this); image.setImageBitmap(Images.thumbnail(f)); image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setContentDescription("奶蛙角色参考"); full(c, image, 230, 0);
            String name = referenceName(f);
            full(c, text(name, 15, INK, true), -2, 12);
            LinearLayout controls = row(); Button choose = button(f.equals(state.selectedReference) ? "✓ 当前参考" : "使用这张", f.equals(state.selectedReference), () -> state.chooseReference(f));
            Button remove = button("移除", false, () -> new AlertDialog.Builder(this).setTitle("移除这张参考图？").setMessage("从应用角色库中移除，手机相册中的原图会保留。")
                    .setPositiveButton("移除", (d, w) -> { f.delete(); state.preferences.edit().putBoolean("removed_" + f.getName(), true).apply(); if (f.equals(state.selectedReference)) state.selectedReference = null; render(); }).setNegativeButton("保留", null).show());
            choose.setEnabled(!state.busy && !state.hasPending()); remove.setEnabled(choose.isEnabled());
            controls.addView(choose, new LinearLayout.LayoutParams(0, dp(48), 1)); controls.addView(remove, new LinearLayout.LayoutParams(dp(80), dp(48))); full(c, controls, -2, 14); full(content, c, -2, 18);
        }
    }
    void resultPage() {
        full(content, text("你好，奶蛙。", 31, GREEN, true), -2, 10);
        full(content, text("你的这一刻，多了一点可爱。", 14, MUTED, false), -2, 8);
        LinearLayout compare = row();
        compare.addView(button("生成结果", !showingOriginal, () -> { showingOriginal = false; render(); }), new LinearLayout.LayoutParams(0, dp(48), 1));
        if (state.original != null) compare.addView(button("查看原图", showingOriginal, () -> { showingOriginal = true; render(); }), new LinearLayout.LayoutParams(0, dp(48), 1));
        full(content, compare, -2, 20);
        ImageView photo = new ImageView(this); photo.setScaleType(ImageView.ScaleType.FIT_CENTER); photo.setImageBitmap(showingOriginal ? state.original : state.result); photo.setContentDescription(showingOriginal ? "转换前的原图" : "奶蛙转换结果"); photo.setBackground(shape(LINE, 20)); photo.setClipToOutline(true); full(content, photo, 440, 12);
        full(content, text(state.result.getWidth() + " × " + state.result.getHeight() + "  ·  PNG  ·  已存入作品", 11, MUTED, false), -2, 10);
        full(content, button("保存到相册", true, () -> saveToGallery(state.resultFile)), 54, 22);
        full(content, button("分享这张奶蛙", false, () -> share(state.resultFile)), 50, 10);
        full(content, button("调整选区再生成", false, () -> { state.result = null; showingOriginal = false; render(); }), 50, 10);
        full(content, button("开始新创作  →", false, () -> pick(false)), 50, 8);
    }
    void galleryPage() {
        full(content, text("我的奶蛙时刻", 29, INK, true), -2, 12);
        List<File> files = state.creations(); full(content, text(files.size() + " 张作品，收集每一次变身。", 13, MUTED, false), -2, 8);
        if (files.isEmpty()) {
            LinearLayout empty = card(LIME); full(empty, text("第一张，留给现在。", 25, GREEN, true), -2, 0);
            full(empty, text("完成一次创作，作品就会出现在这里。", 13, GREEN, false), -2, 14);
            full(empty, button("去创作", true, () -> { tab = 0; render(); }), 52, 26); full(content, empty, -2, 26);
        }
        for (File f : files) {
            LinearLayout c = card(Color.WHITE); ImageView photo = new ImageView(this); photo.setImageBitmap(Images.thumbnail(f)); photo.setScaleType(ImageView.ScaleType.FIT_CENTER); photo.setContentDescription("奶蛙作品"); full(c, photo, 280, 0);
            full(c, text(new SimpleDateFormat("yyyy.MM.dd  HH:mm", Locale.CHINA).format(new Date(f.lastModified())), 12, MUTED, false), -2, 12);
            LinearLayout actions = row(); actions.addView(button("保存", true, () -> saveToGallery(f)), new LinearLayout.LayoutParams(0, dp(48), 1));
            actions.addView(button("分享", false, () -> share(f)), new LinearLayout.LayoutParams(0, dp(48), 1));
            actions.addView(button("删除", false, () -> new AlertDialog.Builder(this).setTitle("删除这张作品？").setPositiveButton("删除", (d, w) -> { f.delete(); if (f.equals(state.resultFile)) { state.result = null; state.resultFile = null; } render(); }).setNegativeButton("保留", null).show()), new LinearLayout.LayoutParams(0, dp(48), 1));
            full(c, actions, -2, 12); full(content, c, -2, 18);
        }
    }
    void pick(boolean references) {
        if (state.busy || state.hasPending()) { toast("请先完成当前生成任务"); return; }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.setType("image/*"); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, references);
        startActivityForResult(intent, references ? PICK_REFERENCES : PICK_PHOTO);
    }
    @Override protected void onActivityResult(int request, int resultCode, Intent data) {
        super.onActivityResult(request, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (request == PICK_PHOTO && data.getData() != null) { tab = 0; state.importPhoto(data.getData()); }
        else if (request == PICK_REFERENCES) {
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) uris.add(data.getData()); state.importReferences(uris);
        } else if (request == EXPORT && data.getData() != null && exportFile != null) {
            final Uri destination = data.getData(); final File file = exportFile;
            state.control.execute(() -> { try (InputStream in = new FileInputStream(file); OutputStream out = getContentResolver().openOutputStream(destination)) { copy(in, out); runOnUiThread(() -> toast("已保存图片")); } catch (Exception e) { runOnUiThread(() -> toast("保存失败：" + e.getMessage())); } });
        }
    }
    void settings() {
        LinearLayout form = column(); form.setPadding(dp(24), dp(12), dp(24), dp(8));
        final AlertDialog[] activeDialog = new AlertDialog[1];
        full(form, button("导入邀请链接或连接码", true, () -> { activeDialog[0].dismiss(); importConnection(""); }), 50, 0);
        form.addView(text("生成服务地址", 13, GREEN, true));
        EditText url = new EditText(this); url.setSingleLine(true); url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI); url.setHint("例如 http://192.168.1.8:8787"); url.setText(state.serviceUrl()); form.addView(url);
        if (!BuildConfig.SERVICE_URL.isEmpty()) full(form, button("填入安装包预设地址", false, () -> url.setText(BuildConfig.SERVICE_URL)), 48, 4);
        full(form, text("自动连接版已预设 HTTPS 服务。电脑连接窗口保持运行，手机可使用 Wi-Fi、流量或 VPN。地址更新时导入新的电脑连接码。", 12, MUTED, false), -2, 8);
        full(form, text("连接码（由服务提供者设置）", 13, GREEN, true), -2, 14);
        EditText token = new EditText(this); token.setSingleLine(true); token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); token.setText(state.preferences.getString("token", "")); token.setHint("未设置时留空"); form.addView(token);
        TextView connectionResult = text("", 13, GREEN, false); full(form, connectionResult, -2, 8);
        full(form, text("奶蛙照相馆 " + BuildConfig.VERSION_NAME + "\n支持 Codex 账号与图像 API\n照片处理长边最高 2048 像素", 12, MUTED, false), -2, 20);
        ScrollView settingsScroll = new ScrollView(this); settingsScroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("连接生成服务").setView(settingsScroll).setPositiveButton("保存", null).setNeutralButton("测试连接", null).setNegativeButton("关闭", null).create();
        activeDialog[0] = dialog;
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String address = url.getText().toString().trim().replaceAll("/+$", "");
                if (!validUrl(address)) { url.setError("请输入完整的 http:// 或 https:// 地址"); return; }
                String connectionCode = token.getText().toString().trim();
                String previousUrl = state.serviceUrl();
                if (!previousUrl.equals(address)) state.connection.clearProfile();
                SharedPreferences.Editor changes = state.preferences.edit().putString("url", address).putString("token", connectionCode);
                if (state.hasPending() && !state.busy && address.equals(state.preferences.getString("jobUrl", ""))) changes.putString("jobToken", connectionCode);
                changes.apply(); if (!previousUrl.equals(address)) state.account.changedService(); dialog.dismiss(); render(); toast("生成服务已保存");
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                String address = url.getText().toString().trim().replaceAll("/+$", "");
                if (!validUrl(address)) { url.setError("请输入完整服务地址"); return; }
                if (state.busy) { toast("当前任务结束后可测试连接"); return; }
                Button test = dialog.getButton(AlertDialog.BUTTON_NEUTRAL); test.setEnabled(false); test.setText("连接中…");
                connectionResult.setText("正在连接：" + address);
                final String connectionCode = token.getText().toString().trim();
                state.io.execute(() -> {
                    String message;
                    try {
                        var health = StudioState.jsonRequest(address + "/v1/status", "GET", null, connectionCode);
                        var providers = health.optJSONObject("providers");
                        message = providers != null && providers.optBoolean("codex") ? "连接成功，支持 Codex 账号登录" : health.optBoolean("ready") ? "连接成功，API 生成服务已就绪" : "连接成功，请在服务端启用 Codex 或配置图像 API";
                    }
                    catch (Exception e) { message = e.getMessage(); }
                    final String result = message; runOnUiThread(() -> { test.setEnabled(true); test.setText("测试连接"); connectionResult.setText(result); });
                });
            });
        }); dialog.show();
    }
    static boolean validUrl(String value) { try { java.net.URI uri = java.net.URI.create(value); return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null && uri.getQuery() == null && uri.getFragment() == null; } catch (Exception e) { return false; } }
    void share(File file) {
        if (file == null || !file.exists()) return;
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
        Intent intent = new Intent(Intent.ACTION_SEND); intent.setType("image/png"); intent.putExtra(Intent.EXTRA_STREAM, uri); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); intent.setClipData(ClipData.newRawUri("奶蛙照片", uri)); startActivity(Intent.createChooser(intent, "分享奶蛙照片"));
    }
    void saveToGallery(File file) {
        if (file == null || !file.exists()) return;
        if (Build.VERSION.SDK_INT < 29) { exportFile = file; Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT); create.addCategory(Intent.CATEGORY_OPENABLE); create.setType("image/png"); create.putExtra(Intent.EXTRA_TITLE, file.getName()); startActivityForResult(create, EXPORT); return; }
        state.control.execute(() -> {
            Uri uri = null;
            try {
                ContentValues values = new ContentValues(); values.put(MediaStore.Images.Media.DISPLAY_NAME, file.getName()); values.put(MediaStore.Images.Media.MIME_TYPE, "image/png"); values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/奶蛙照相馆"); values.put(MediaStore.Images.Media.IS_PENDING, 1);
                uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("无法创建相册文件");
                try (InputStream in = new FileInputStream(file); OutputStream out = getContentResolver().openOutputStream(uri)) { copy(in, out); }
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0); getContentResolver().update(uri, values, null, null); runOnUiThread(() -> toast("已保存到相册 / 奶蛙照相馆"));
            } catch (Exception e) { if (uri != null) getContentResolver().delete(uri, null, null); runOnUiThread(() -> toast("保存失败：" + e.getMessage())); }
        });
    }
    static void copy(InputStream in, OutputStream out) throws IOException { if (out == null) throw new IOException("无法写入图片"); byte[] bytes = new byte[16384]; int n; while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n); }
    void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    static String referenceName(File file) {
        String[] names = {"正面", "经典", "向右", "向左", "挥手", "大笑", "合手", "思考", "叉腰", "侧身", "转身", "抬头"};
        if (file.getName().matches("\\d{2}_[a-z_]+\\.png")) {
            int index = Integer.parseInt(file.getName().substring(0, 2)) - 1;
            if (index >= 0 && index < names.length) return names[index];
        }
        return file.getName().replaceFirst("\\.[^.]+$", "");
    }

    static class PhotoArt extends View {
        final Paint p = new Paint(3);
        final Bitmap character;
        final RectF characterRect = new RectF(170, 17, 265, 120);
        final Path star = new Path();
        PhotoArt(Context context) {
            super(context); character = BitmapFactory.decodeResource(getResources(), R.drawable.naifrog); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            star.moveTo(169, 0); star.lineTo(173, 12); star.lineTo(186, 16); star.lineTo(173, 20); star.lineTo(169, 32); star.lineTo(165, 20); star.lineTo(153, 16); star.lineTo(165, 12); star.close();
        }
        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth() / 300f, getHeight() / 148f); c.save(); c.translate((getWidth() - 300 * s) / 2, 0); c.scale(s, s);
            c.save(); c.rotate(-9, 102, 72); p.setColor(Color.rgb(249, 251, 244)); c.drawRoundRect(37, 8, 158, 133, 12, 12, p);
            p.setColor(Color.rgb(208, 222, 193)); c.drawRoundRect(45, 16, 150, 108, 7, 7, p);
            p.setColor(Color.rgb(113, 139, 107)); c.drawCircle(98, 49, 17, p); c.drawRoundRect(67, 72, 129, 108, 24, 24, p);
            p.setColor(GREEN); p.setTextSize(8); c.drawText("YOUR MOMENT", 64, 122, p); c.restore();
            c.save(); c.rotate(10, 214, 80); p.setColor(GREEN); c.drawRoundRect(165, 18, 268, 129, 18, 18, p);
            p.setColor(LIME); p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); p.setTextSize(67);
            c.drawBitmap(character, null, characterRect, p);
            p.setTextSize(8); p.setColor(Color.WHITE); c.drawText("NAIFROG STUDIO", 182, 115, p); c.restore();
            p.setColor(GREEN); c.drawPath(star, p); c.restore();
        }
    }
}
