package com.reardaily.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;

public class MainActivity extends Activity {
  final int INK = Ui.INK, MUTED = Ui.MUTED, ACCENT = Ui.ACCENT, PAPER = Ui.PAPER;
  final Handler handler = new Handler(Looper.getMainLooper());
  LinearLayout shell, body, nav;
  RearSetupDialog rearSetupDialog;
  TextView status, topicView, mediaTitle, mediaCaption, promptSummary, topicSummary;
  Button generateButton;
  AlertDialog activeDialog;
  EditText editorInput;
  String editorKind = "";
  ImageView preview;
  ImageButton heart;
  int pageGeneration;
  int libraryPageIndex;
  final java.util.concurrent.ExecutorService thumbnails =
      java.util.concurrent.Executors.newFixedThreadPool(2);
  EditText apiKey;
  Switch auto, ai;
  String page = "today", draftLoaded = "", previewVersion = "";
  final Runnable ticker =
      new Runnable() {
        public void run() {
          refresh();
          handler.postDelayed(this, 1800);
        }
      };

  int dp(int n) {
    return (int) (n * getResources().getDisplayMetrics().density);
  }

  GradientDrawable background(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  TextView text(String value, int size) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(INK);
    t.setPadding(0, dp(8), 0, dp(8));
    body.addView(t);
    return t;
  }

  TextView hint(String value) {
    TextView t = text(value, 13);
    t.setTextColor(MUTED);
    return t;
  }

  Button button(String title, Runnable action) {
    Button b = new Button(this);
    b.setText(title);
    Ui.styleButton(b, false);
    b.setOnClickListener(
        v -> {
          try {
            action.run();
          } catch (Exception e) {
            toast(Net.safeError(e));
          }
        });
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp(8);
    body.addView(b, lp);
    return b;
  }

  Button primary(String title, Runnable action) {
    Button b = button(title, action);
    Ui.styleButton(b, true);
    return b;
  }

  void section(String title) {
    TextView t = text(title, 18);
    t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    t.setPadding(0, dp(24), 0, dp(10));
  }

  EditText edit(String hint, int lines) {
    EditText e = new EditText(this);
    Ui.field(e);
    e.setHint(hint);
    e.setMinLines(lines);
    e.setGravity(Gravity.TOP);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp(6);
    lp.bottomMargin = dp(8);
    body.addView(e, lp);
    return e;
  }

  void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }

  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().setStatusBarColor(PAPER);
    getWindow().setNavigationBarColor(PAPER);
    getWindow().getDecorView();
    if (getWindow().getInsetsController() != null)
      getWindow()
          .getInsetsController()
          .setSystemBarsAppearance(
              WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                  | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
              WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                  | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    if (saved != null) page = saved.getString("page", "today");
    try {
      Store.trimHistory(this);
      Library.migrate(this);
    } catch (Exception e) {
      toast(Net.safeError(e));
    }
    if (saved == null && needsWelcome(this)) page = "welcome";
    show(page);
    DailyJob.schedule(this);
    boolean setupCallback = RearSetup.id(this).equals(getIntent().getStringExtra("rear_setup"));
    getIntent().removeExtra("rear_setup");
    if (setupCallback || (saved != null && saved.getBoolean("setup_open", false))) {
      showRearSetup(setupCallback);
      if (saved != null && !setupCallback)
        rearSetupDialog.tabs.check(saved.getInt("setup_tab", 7100));
        rearSetupDialog.adbExpanded = saved.getBoolean("setup_adb");
        rearSetupDialog.shizukuHelp = saved.getBoolean("setup_shizuku_help");
        rearSetupDialog.refresh();
    } else if (saved != null && !saved.getString("editor_kind", "").isEmpty()) {
      openEditor(saved.getString("editor_kind"), saved.getString("editor_text", ""));
    }
  }

  static boolean needsWelcome(Context c) {
    return Store.prefs(c).getLong("setup.rearReadAt", 0) == 0
        && !Store.prefs(c).getBoolean("onboarding.dismissed", false);
  }

  void showRearSetup(boolean openPreview, boolean manual) {
    showRearSetup(openPreview);
    rearSetupDialog.tabs.check(manual ? 7101 : 7100);
  }

  void showRearSetup(boolean openPreview) {
    if (rearSetupDialog != null && !rearSetupDialog.closed) rearSetupDialog.dialog.dismiss();
    rearSetupDialog = new RearSetupDialog(this);
    rearSetupDialog.show(openPreview);
  }

  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    boolean setupCallback = RearSetup.id(this).equals(intent.getStringExtra("rear_setup"));
    intent.removeExtra("rear_setup");
    if (setupCallback) showRearSetup(true);
  }

  void show(String target) {
    if (!target.equals(page)) libraryPageIndex = 0;
    page = target;
    pageGeneration++;
    heart = null;
    status = null;
    preview = null;
    topicView = null;
    apiKey = null;
    mediaTitle = null;
    mediaCaption = null;
    promptSummary = null;
    topicSummary = null;
    generateButton = null;
    auto = null;
    ai = null;
    draftLoaded = "";
    previewVersion = "";
    shell = new LinearLayout(this);
    shell.setOrientation(1);
    shell.setBackgroundColor(PAPER);
    Ui.insets(this, shell, 0);
    String[] labels = {"背屏", "创作", "收藏", "历史", "设置"};
    String[] pages = {"today", "create", "favorites", "history", "settings"};
    String heading = "每日背屏";
    for (int i = 1; i < pages.length; i++) if (page.equals(pages[i])) heading = labels[i];
    TextView brand = Ui.label(this, heading, page.equals("today") ? 25 : 26, INK);
    brand.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    brand.setPadding(dp(24), dp(20), dp(24), dp(12));
    if (!page.equals("welcome")) shell.addView(brand);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(page.equals("welcome"));
    scroll.setClipToPadding(false);
    scroll.setVerticalScrollBarEnabled(false);
    LinearLayout center = new LinearLayout(this);
    center.setGravity(Gravity.CENTER_HORIZONTAL);
    body = new LinearLayout(this);
    body.setOrientation(1);
    body.setPadding(dp(24), dp(4), dp(24), dp(24));
    int available = getResources().getDisplayMetrics().widthPixels;
    center.addView(body, new LinearLayout.LayoutParams(Math.min(available, dp(600)), -2));
    scroll.addView(center);
    shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    if (!page.equals("welcome")) {
      nav = new LinearLayout(this);
      nav.setGravity(Gravity.CENTER);
      nav.setPadding(dp(12), dp(6), dp(12), dp(4));
      nav.setBackgroundColor(PAPER);
      for (int i = 0; i < labels.length; i++) {
        final String id = pages[i];
        boolean selected = id.equals(page);
        TextView t = Ui.label(this, labels[i], 11, selected ? ACCENT : MUTED);
        t.setGravity(Gravity.CENTER);
        t.setCompoundDrawablePadding(dp(4));
        Ui.NavIcon icon = new Ui.NavIcon(id, selected ? ACCENT : MUTED);
        icon.setBounds(0, 0, dp(22), dp(22));
        t.setCompoundDrawables(null, icon, null, null);
        t.setPadding(0, dp(7), 0, dp(5));
        t.setBackground(Ui.touch(this, selected ? Ui.TINT : Color.TRANSPARENT, 16));
        t.setSelected(selected);
        t.setContentDescription(labels[i]);
        nav.addView(t, new LinearLayout.LayoutParams(0, dp(60), 1));
        t.setOnClickListener(v -> show(id));
      }
      shell.addView(nav);
    }
    setContentView(shell);
    if (page.equals("welcome")) welcomePage();
    else if (page.equals("today")) todayPage();
    else if (page.equals("create")) createPage();
    else if (page.equals("history")) libraryPage(false);
    else if (page.equals("favorites")) libraryPage(true);
    else settingsPage();
    refresh();
  }

  void welcomePage() {
    body.setPadding(dp(28), dp(48), dp(28), dp(24));
    TextView mark = hint("每日背屏");
    mark.setTextColor(ACCENT);
    TextView title = text("给背屏，\n换一种心情。", 32);
    title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    title.setLineSpacing(dp(6), 1f);
    hint("喜欢的图片与视频，抬手就能看见。");
    Ui.RearIllustration drawing = new Ui.RearIllustration(this);
    LinearLayout.LayoutParams art = new LinearLayout.LayoutParams(-1, dp(194));
    art.topMargin = dp(28);
    art.bottomMargin = dp(22);
    body.addView(drawing, art);
    section("先连接你的背屏");
    hint("使用 Shizuku 可直接在手机上设置，也可通过电脑 ADB 完成。设置一次，以后自由换图。");
    primary("自动设置背屏", () -> showRearSetup(false, false));
    button("手动设置背屏", () -> showRearSetup(false, true));
    TextView note = hint("自动设置会准备好模板，按引导执行一次命令即可。\n适用于兼容的小米背屏设备。");
    note.setTextSize(12);
    Button later =
        button(
            "稍后设置，先看看",
            () -> {
              Store.prefs(this).edit().putBoolean("onboarding.dismissed", true).apply();
              show("today");
            });
    later.setBackground(Ui.touch(this, Color.TRANSPARENT, 12));
    later.setTextColor(MUTED);
  }

  void todayPage() {
    if (Store.prefs(this).getLong("setup.rearReadAt", 0) == 0)
      settingsRow("连接背屏", "完成首次设置，让喜欢的内容显示在背屏", () -> showRearSetup(false));
    preview = new CameraPreview(this);
    preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
    preview.setBackground(background(INK, 22));
    preview.setClipToOutline(true);
    FrameLayout previewFrame = new FrameLayout(this);
    previewFrame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
    heart = new ImageButton(this);
    heart.setBackground(Ui.touch(this, 0xF5FFFFFF, 24));
    heart.setPadding(dp(12), dp(12), dp(12), dp(12));
    FrameLayout.LayoutParams heartLp =
        new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.RIGHT | Gravity.BOTTOM);
    heartLp.setMargins(0, 0, dp(12), dp(12));
    previewFrame.addView(heart, heartLp);
    heart.setOnClickListener(v -> favoriteCurrent());
    updateHeart();
    int width = Math.min(getResources().getDisplayMetrics().widthPixels, dp(600)) - dp(48);
    LinearLayout.LayoutParams imageLp =
        new LinearLayout.LayoutParams(-1, Math.round(width * 596f / 976f));
    imageLp.topMargin = dp(10);
    body.addView(previewFrame, imageLp);
    preview.setContentDescription("预览当前背屏内容");
    preview.setOnClickListener(v -> previewMedia(Library.current(this)));
    mediaTitle = text("", 20);
    mediaTitle.setMaxLines(2);
    mediaTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    mediaTitle.setPadding(0, dp(18), 0, dp(2));
    mediaCaption = hint("");
    mediaCaption.setPadding(0, dp(4), 0, dp(6));
    status = hint("");
    status.setTextSize(12);
    primary("换张AI生成图片", () -> start("ai-image"));
    TextView cost = hint("自动选题并生成 1 张图片，按所选 AI 服务计费。");
    cost.setTextSize(12);
    cost.setPadding(0, dp(7), 0, dp(6));
    sourceRow(false);
    sourceRow(true);
    LinearLayout actions = new LinearLayout(this);
    Button local =
        button(
            "从相册导入",
            () ->
                Ui.show(
                    new AlertDialog.Builder(this)
                        .setTitle("从相册导入")
                        .setItems(new String[] {"选择图片", "选择视频"}, (d, n) -> pick(n == 1))
                        .setNegativeButton("取消", null)));
    Button options = button("显示选项", this::displayOptions);
    body.removeView(local);
    body.removeView(options);
    actions.addView(local, new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout.LayoutParams optionLp = new LinearLayout.LayoutParams(0, -2, 1);
    optionLp.leftMargin = dp(10);
    actions.addView(options, optionLp);
    LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2);
    actionsLp.topMargin = dp(12);
    body.addView(actions, actionsLp);
  }

  void displayOptions() {
    JSONObject s = Store.state(this);
    AlertDialog.Builder b =
        new AlertDialog.Builder(this)
            .setTitle("背屏显示")
            .setSingleChoiceItems(
                new String[] {"显示已保存的图片", "显示已保存的视频"},
                s.optInt("mode", 1) == 1 ? 0 : 1,
                (dialog, which) -> {
                  try {
                    Store.selectMode(this, which + 1);
                    previewVersion = "";
                    refresh();
                    dialog.dismiss();
                  } catch (Exception e) {
                    toast(Net.safeError(e));
                  }
                })
            .setNegativeButton("关闭", null);
    JSONObject meta = s.optJSONObject("currentMetadata");
    if (meta != null && !meta.optString("sourceUrl").isEmpty())
      b.setNeutralButton("查看来源", (d, n) -> openLink(meta.optString("sourceUrl")));
    Ui.show(b);
  }

  void sourceRow(boolean video) {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    Button main =
        button(
            video ? "挑选网络视频" : "挑选网络图片",
            () ->
                startActivity(
                    new Intent(this, SourceBrowserActivity.class).putExtra("video", video)));
    body.removeView(main);
    row.addView(main, new LinearLayout.LayoutParams(0, -2, 1));
    ImageButton random = new ImageButton(this);
    random.setImageDrawable(
        getDrawable(getResources().getIdentifier("ic_shuffle", "drawable", getPackageName())));
    random.setBackground(Ui.touch(this, Color.WHITE, 14));
    random.setPadding(dp(12), dp(12), dp(12), dp(12));
    random.setContentDescription(video ? "随机换段网络视频" : "随机换张网络图片");
    random.setTooltipText(random.getContentDescription());
    random.setOnClickListener(v -> start(video ? "popular-video" : "popular-image"));
    LinearLayout.LayoutParams icon = new LinearLayout.LayoutParams(dp(50), dp(50));
    icon.leftMargin = dp(8);
    row.addView(random, icon);
    LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, -2);
    line.topMargin = dp(8);
    body.addView(row, line);
  }

  TextView promptCard(String title, String value, String kind) {
    LinearLayout head = new LinearLayout(this);
    head.setGravity(Gravity.CENTER_VERTICAL);
    TextView label = Ui.label(this, title, 17, INK);
    label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    head.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    Button change = new Button(this);
    change.setText("修改");
    Ui.styleButton(change, false);
    change.setBackground(Ui.touch(this, Color.TRANSPARENT, 12));
    change.setContentDescription("修改" + title);
    change.setOnClickListener(v -> openEditor(kind, null));
    head.addView(change, new LinearLayout.LayoutParams(dp(64), dp(48)));
    LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(-1, -2);
    headLp.topMargin = dp(14);
    body.addView(head, headLp);
    TextView summary = Ui.label(this, value, 14, MUTED);
    summary.setMaxLines(4);
    summary.setEllipsize(TextUtils.TruncateAt.END);
    summary.setPadding(dp(16), dp(14), dp(16), dp(14));
    summary.setBackground(Ui.shape(this, Color.WHITE, 14, false));
    body.addView(summary, new LinearLayout.LayoutParams(-1, -2));
    return summary;
  }

  static String promptPreview(String full) {
    String marker = "（以上为创作语境，画面不排版日期或新闻文字。）";
    int end = full.indexOf(marker);
    return full.startsWith("今日选题 ") && end >= 0
        ? full.substring(end + marker.length()).trim()
        : full;
  }

  void createPage() {
    hint("从一个灵感，开始一张新壁纸。");
    topicSummary = promptCard("选题提示词", Bailian.topicPrompt(this), "topic");
    button("换个选题", () -> start("draft"));
    section("当前灵感");
    topicView = text("", 16);
    topicView.setMaxLines(3);
    topicView.setEllipsize(TextUtils.TruncateAt.END);
    topicView.setPadding(0, 0, 0, dp(4));
    promptSummary = promptCard("生图提示词", "先获取选题，AI 会为你写好画面描述。", "image");
    generateButton = primary("生成图片并应用", () -> start("image"));
    hint("生成 1 张图片，按所选 AI 服务计费。");
    status = hint("");
    status.setTextSize(12);
    promptCard("创作偏好", creativeBrief(), "brief");
    hint("影响之后的选题与画面风格。");
  }

  String creativeBrief() {
    return Store.prefs(this).getString("creativeBrief", "二次元、动漫、游戏；主体清晰，横向构图，酷炫有质感，避免刺眼色彩堆砌。");
  }

  void openEditor(String kind, String restored) {
    if (activeDialog != null) activeDialog.dismiss();
    String title, value, help;
    switch (kind) {
      case "topic":
        title = "选题提示词";
        value = Bailian.topicPrompt(this);
        help = "写下想关注的话题与方向，下次选题时使用。";
        break;
      case "image":
        title = "生图提示词";
        value = Bailian.draft(this).optString("prompt");
        if (Bailian.draft(this).optString("topicKey").isEmpty()) {
          toast("先点“换个选题”，再修改画面描述");
          return;
        }
        help = "修改画面描述，保存后即可用它生成图片。";
        break;
      case "brief":
        title = "创作偏好";
        value = creativeBrief();
        help = "喜欢的主题、风格与配色，会用于之后的创作。";
        break;
      case "keyword":
        title = "搜索关键词";
        value = MediaSearch.keyword(this);
        help = "例如 Frieren、Genshin Impact。留空则使用网站推荐。";
        break;
      default:
        title = "排除词";
        value = KeywordFilter.configured(this);
        help = "用逗号或换行分隔。仅匹配作品文字信息，无法识别画面内容；清空可关闭。";
    }
    editorKind = kind;
    LinearLayout panel = new LinearLayout(this);
    panel.setOrientation(1);
    panel.setPadding(dp(22), dp(4), dp(22), dp(8));
    TextView caption = Ui.label(this, help, 13, MUTED);
    caption.setPadding(0, dp(8), 0, dp(12));
    panel.addView(caption);
    editorInput = new EditText(this);
    Ui.field(editorInput);
    editorInput.setInputType(
        InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    editorInput.setGravity(Gravity.TOP);
    editorInput.setMinLines(6);
    editorInput.setText(restored == null ? value : restored);
    editorInput.setContentDescription(title + "完整内容");
    ScrollView sc = new ScrollView(this);
    sc.setFillViewport(true);
    sc.addView(editorInput, new ScrollView.LayoutParams(-1, -1));
    panel.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
    AlertDialog.Builder builder =
        new AlertDialog.Builder(this)
            .setTitle("修改" + title)
            .setView(panel)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null);
    if (kind.equals("topic") || kind.equals("excluded")) builder.setNeutralButton("恢复默认", null);
    final AlertDialog dialog = builder.create();
    activeDialog = dialog;
    dialog.setOnDismissListener(
        d -> {
          if (activeDialog == dialog) {
            editorInput = null;
            editorKind = "";
            activeDialog = null;
          }
        });
    dialog.show();
    Ui.finishDialog(dialog, true);
    dialog
        .getWindow()
        .setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    dialog
        .getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(
            v -> {
              if (Updater.BUSY.get()
                  && (kind.equals("topic") || kind.equals("image") || kind.equals("brief"))) {
                toast("请等当前创作完成后再保存");
                return;
              }
              try {
                saveEditor(kind, editorInput.getText().toString());
                dialog.dismiss();
                show(page);
                toast("已保存");
              } catch (Exception e) {
                editorInput.setError(Net.safeError(e));
              }
            });
    if (kind.equals("topic") || kind.equals("excluded"))
      dialog
          .getButton(AlertDialog.BUTTON_NEUTRAL)
          .setOnClickListener(
              v ->
                  editorInput.setText(
                      kind.equals("topic")
                          ? Bailian.DEFAULT_TOPIC_PROMPT
                          : KeywordFilter.DEFAULTS));
  }

  void saveEditor(String kind, String value) throws Exception {
    value = value.trim();
    if (kind.equals("topic")) Bailian.saveTopicPrompt(this, value);
    else if (kind.equals("image")) {
      if (value.length() < 40) throw new IOException("请保留至少 40 个字的画面描述");
      JSONObject draft = Bailian.draft(this);
      if (draft.optString("topicKey").isEmpty()) throw new IOException("请先获取选题");
      draft.put("prompt", Bailian.withContext(draft, value)).put("edited", true);
      Bailian.saveDraft(this, draft);
    } else if (kind.equals("brief"))
      Store.prefs(this).edit().putString("creativeBrief", value).apply();
    else if (kind.equals("keyword")) MediaSearch.save(this, value);
    else KeywordFilter.save(this, value);
  }

  void settingsRow(String title, String description, Runnable action) {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(16), dp(14), dp(14), dp(14));
    row.setBackground(Ui.touch(this, Color.WHITE, 14));
    LinearLayout copy = new LinearLayout(this);
    copy.setOrientation(1);
    TextView name = Ui.label(this, title, 16, INK);
    copy.addView(name);
    if (!description.isEmpty()) {
      TextView detail = Ui.label(this, description, 12, MUTED);
      detail.setMaxLines(2);
      detail.setEllipsize(TextUtils.TruncateAt.END);
      detail.setPadding(0, dp(4), dp(8), 0);
      copy.addView(detail);
    }
    row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
    TextView arrow = Ui.label(this, "›", 23, MUTED);
    row.addView(arrow);
    row.setMinimumHeight(dp(64));
    row.setOnClickListener(v -> action.run());
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp(8);
    body.addView(row, lp);
  }

  void settingsPage() {
    section("背屏");
    boolean connected = Store.prefs(this).getLong("setup.rearReadAt", 0) > 0;
    settingsRow(
        "背屏设置与使用指南", connected ? "已连接 · 查看自动设置与手动操作" : "首次使用，从这里连接背屏", () -> showRearSetup(false));
    section("内容偏好");
    settingsRow(
        "每日自动更换",
        Store.prefs(this).getBoolean("auto", true) ? "已开启 · 每天换一份新内容" : "已关闭",
        this::dailySettings);
    settingsRow(
        "AI 服务",
        SecretVault.configured(this)
            ? (AIConfig.COMPAT.equals(AIConfig.provider(this)) ? "OpenAI 兼容接口 · 已配置" : "阿里百炼 · 已配置")
            : "配置后即可生成 AI 图片",
        () -> showPanel("AI 服务", this::providerControls));
    settingsRow(
        "搜索关键词",
        MediaSearch.keyword(this).isEmpty() ? "使用网站推荐内容" : MediaSearch.keyword(this),
        () -> openEditor("keyword", null));
    settingsRow(
        "排除词",
        KeywordFilter.words(KeywordFilter.configured(this)).size() + " 个词 · 筛选网络图片与视频",
        () -> openEditor("excluded", null));
    section("关于");
    settingsRow(
        "素材来源与后台运行",
        "了解内容来源、缓存和自动更新",
        () ->
            Ui.show(
                new AlertDialog.Builder(this)
                    .setTitle("素材与更新")
                    .setMessage(
                        "图片来自 Wallhaven，视频来自 MotionBGS。作品版权归原作者所有。\n\n"
                            + "已下载的内容可离线显示，更新失败会保留当前内容。\n\n"
                            + "开启自动更换后，App 每隔约 6"
                            + " 小时检查一次，当天更新成功后不再自动下载。系统可能延迟后台运行，可在系统设置中允许自启动并放宽省电限制。")
                    .setPositiveButton("知道了", null)));
    settingsRow("开源与许可", "项目参考与第三方说明", this::showNotices);
    String versionName = "";
    try { versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
    catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
    TextView version = hint("每日背屏 " + versionName);
    version.setGravity(Gravity.CENTER);
    version.setPadding(0, dp(30), 0, dp(8));
  }

  void showPanel(String title, Runnable render) {
    if (activeDialog != null) activeDialog.dismiss();
    LinearLayout original = body;
    LinearLayout panel = new LinearLayout(this);
    panel.setOrientation(1);
    panel.setPadding(dp(22), 0, dp(22), dp(12));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(panel);
    body = panel;
    try {
      render.run();
    } finally {
      body = original;
    }
    final AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .create();
    activeDialog = dialog;
    dialog.setOnDismissListener(
        d -> {
          if (activeDialog == dialog) activeDialog = null;
        });
    dialog.show();
    Ui.finishDialog(dialog, !title.equals("每日自动更换"));
    dialog
        .getWindow()
        .setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
  }

  void finishPanel() {
    if (activeDialog != null) activeDialog.dismiss();
    if (page.equals("settings")) show("settings");
  }

  void dailySettings() {
    showPanel(
        "每日自动更换",
        () -> {
          auto = new Switch(this);
          auto.setText("每天自动更换");
          auto.setTextSize(16);
          auto.setTextColor(INK);
          auto.setPadding(0, dp(12), 0, dp(12));
          auto.setChecked(Store.prefs(this).getBoolean("auto", true));
          body.addView(auto);
          ai = new Switch(this);
          ai.setText("包含 AI 生成图片");
          ai.setTextSize(16);
          ai.setTextColor(INK);
          ai.setPadding(0, dp(12), 0, dp(12));
          ai.setChecked(Store.prefs(this).getBoolean("aiEnabled", true));
          body.addView(ai);
          hint("AI 生成会使用你配置的服务，并产生相应费用。");
          probabilityControls();
        });
  }

  void favoriteCurrent() {
    try {
      JSONObject e = Library.current(this);
      Library.toggle(this, e);
      updateHeart();
      toast(Library.favorite(this, e) ? "已收藏，可在收藏页离线应用" : "已取消收藏");
    } catch (Exception e) {
      toast(Net.safeError(e));
    }
  }

  void updateHeart() {
    if (heart == null) return;
    boolean selected = Library.favorite(this, Library.current(this));
    heart.setImageDrawable(
        getDrawable(
            getResources()
                .getIdentifier(
                    selected ? "ic_heart" : "ic_heart_outline", "drawable", getPackageName())));
    heart.setContentDescription(selected ? "取消收藏当前素材" : "收藏当前素材");
  }

  void probabilityControls() {
    section("内容比例");
    hint("AI 图片包含在图片比例中。同一类型连续 3 天后，会优先换一种。");
    TextView mixLabel = text("", 15);
    SeekBar image = new SeekBar(this);
    image.setMax(10);
    image.setContentDescription("图片占比");
    body.addView(image, new LinearLayout.LayoutParams(-1, dp(48)));
    TextView aiLabel = text("", 15);
    SeekBar aiBar = new SeekBar(this);
    aiBar.setMax(10);
    aiBar.setContentDescription("AI 图片占全部内容的比例");
    body.addView(aiBar, new LinearLayout.LayoutParams(-1, dp(48)));
    image.setProgress(AIConfig.imagePercent(this) / 10);
    aiBar.setProgress(AIConfig.aiPercent(this) / 10);
    Runnable labels =
        () -> {
          mixLabel.setText(
              "图片 "
                  + image.getProgress() * 10
                  + "%  ·  视频 "
                  + (100 - image.getProgress() * 10)
                  + "%");
          aiLabel.setText("AI 图片占全部内容 " + (ai.isChecked() ? aiBar.getProgress() * 10 : 0) + "%");
          aiBar.setEnabled(ai.isChecked());
          aiBar.setAlpha(ai.isChecked() ? 1f : .4f);
        };
    SeekBar.OnSeekBarChangeListener listener =
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar bar, int n, boolean user) {
            if (aiBar.getProgress() > image.getProgress()) aiBar.setProgress(image.getProgress());
            labels.run();
          }

          public void onStartTrackingTouch(SeekBar bar) {}

          public void onStopTrackingTouch(SeekBar bar) {}
        };
    image.setOnSeekBarChangeListener(listener);
    aiBar.setOnSeekBarChangeListener(listener);
    ai.setOnCheckedChangeListener((v, on) -> labels.run());
    labels.run();
    primary(
        "保存",
        () -> {
          Store.prefs(this)
              .edit()
              .putBoolean("auto", auto.isChecked())
              .putBoolean("aiEnabled", ai.isChecked())
              .putInt("imagePercent", image.getProgress() * 10)
              .putInt("aiPercent", aiBar.getProgress() * 10)
              .putString("source", "daily")
              .remove("dayPlan")
              .apply();
          DailyJob.schedule(this);
          finishPanel();
          toast("已保存，下次更新生效");
        });
  }

  void providerControls() {
    hint("连接你自己的 AI 服务，用于选题和图片生成。");
    Spinner selector = new Spinner(this);
    selector.setAdapter(
        new ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"阿里百炼", "OpenAI 兼容接口"}));
    selector.setContentDescription("模型提供商");
    body.addView(selector, new LinearLayout.LayoutParams(-1, dp(52)));
    TextView providerStatus = hint("");
    text("服务地址", 15);
    EditText base = edit("Base URL，例如 https://你的服务地址/v1", 1);
    base.setSingleLine(true);
    base.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    text("选题模型", 15);
    EditText textModel = edit("输入服务支持的文本模型名称", 1);
    textModel.setSingleLine(true);
    text("生图模型", 15);
    EditText imageModel = edit("输入服务支持的生图模型名称", 1);
    imageModel.setSingleLine(true);
    apiKey = edit("API Key（留空则保持原值）", 1);
    apiKey.setSingleLine(true);
    apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    apiKey.setSaveEnabled(false);
    apiKey.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    final String[] selected = {AIConfig.provider(this)};
    Runnable load =
        () -> {
          String p = selected[0];
          boolean compatible = AIConfig.COMPAT.equals(p);
          base.setText(AIConfig.base(this, p));
          base.setEnabled(compatible);
          textModel.setText(AIConfig.textModel(this, p));
          imageModel.setText(AIConfig.imageModel(this, p));
          apiKey.setText("");
          providerStatus.setText(
              (SecretVault.configured(this, p) ? "API Key 已保存" : "请填写 API Key")
                  + (compatible ? "\n" + "需支持文本对话与图片生成接口。" : "\n使用百炼北京地域的 API Key。"));
        };
    selector.setSelection(AIConfig.COMPAT.equals(selected[0]) ? 1 : 0);
    selector.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> p) {}

          public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
            selected[0] = pos == 1 ? AIConfig.COMPAT : AIConfig.ALI;
            load.run();
          }
        });
    load.run();
    primary(
        "保存",
        () -> {
          if (Updater.BUSY.get()) {
            toast("请等待当前任务完成后再修改模型配置");
            return;
          }
          try {
            String p = selected[0],
                url = base.getText().toString().trim(),
                key = apiKey.getText().toString().trim();
            String tm = AIConfig.model(textModel.getText().toString()),
                im = AIConfig.model(imageModel.getText().toString());
            if (AIConfig.COMPAT.equals(p)) {
              url = AIConfig.validateBase(url);
              String old = AIConfig.base(this, p);
              if (key.isEmpty()
                  && SecretVault.configured(this, p)
                  && !old.isEmpty()
                  && !new java.net.URI(old)
                      .getAuthority()
                      .equals(new java.net.URI(url).getAuthority()))
                throw new IOException("更换服务域名时请重新输入该服务的 API Key");
            }
            if (!key.isEmpty()) SecretVault.save(this, p, key);
            AIConfig.save(this, p, url, tm, im);
            apiKey.setText("");
            load.run();
            finishPanel();
            toast("AI 服务已保存");
          } catch (Exception e) {
            toast(Net.safeError(e));
          }
        });
    button(
        "移除 API Key",
        () -> {
          Ui.show(
              new AlertDialog.Builder(this)
                  .setTitle("移除 API Key？")
                  .setMessage("移除后，需重新配置才能生成 AI 图片。")
                  .setNegativeButton("取消", null)
                  .setPositiveButton(
                      "移除",
                      (d, n) -> {
                        if (Updater.BUSY.get()) {
                          toast("请等待当前任务完成");
                          return;
                        }
                        SecretVault.clear(this, selected[0]);
                        load.run();
                        toast("已移除 API Key");
                      }));
        });
    hint("API Key 加密保存在本机，仅发送给你选择的服务。");
  }

  void libraryPage(boolean favorites) {
    hint(favorites ? "喜欢的内容，随时换回来。" : "最近 30 次使用的内容，收藏后会长期保留。");
    JSONArray a = favorites ? Library.favorites(this) : Bailian.history(this);
    if (a.length() == 0) {
      text(favorites ? "还没有收藏，点背屏预览上的爱心试试。" : "还没有素材记录", 16);
      return;
    }
    libraryPageIndex = Math.min(libraryPageIndex, (a.length() - 1) / 12);
    final int startIndex = libraryPageIndex * 12, endIndex = Math.min(a.length(), startIndex + 12);
    if (a.length() > 12)
      hint(
          "第 "
              + (libraryPageIndex + 1)
              + " / "
              + ((a.length() + 11) / 12)
              + " 页 · 共 "
              + a.length()
              + " 条");
    if (libraryPageIndex > 0)
      button(
          "上一页",
          () -> {
            libraryPageIndex--;
            show(page);
          });
    for (int i = startIndex; i < endIndex; i++) {
      JSONObject e = a.optJSONObject(i);
      if (e == null) continue;
      LinearLayout container = new LinearLayout(this);
      container.setOrientation(1);
      container.setPadding(dp(12), dp(12), dp(12), dp(12));
      container.setBackground(background(Color.WHITE, 18));
      LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
      cp.bottomMargin = dp(14);
      body.addView(container, cp);
      FrameLayout frame = new FrameLayout(this);
      frame.setBackground(background(INK, 12));
      frame.setClipToOutline(true);
      ImageView cover = new ImageView(this);
      cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
      frame.addView(cover, new FrameLayout.LayoutParams(-1, -1));
      TextView badge = new TextView(this);
      badge.setTextColor(Color.WHITE);
      badge.setTextSize(14);
      badge.setPadding(dp(10), dp(6), dp(10), dp(6));
      badge.setBackground(background(0xB0000000, 8));
      badge.setText(e.optString("kind").equals("video") ? "▶ 视频预览" : "图片预览");
      FrameLayout.LayoutParams badgeLp =
          new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
      badgeLp.setMargins(dp(8), 0, 0, dp(8));
      frame.addView(badge, badgeLp);
      frame.setContentDescription("预览 " + e.optString("title"));
      frame.setOnClickListener(v -> previewMedia(e));
      container.addView(frame, new LinearLayout.LayoutParams(-1, dp(200)));
      TextView title = new TextView(this);
      title.setText(e.optString("title", "素材"));
      title.setTextSize(17);
      title.setTextColor(INK);
      title.setTypeface(null, Typeface.BOLD);
      title.setPadding(0, dp(12), 0, dp(5));
      container.addView(title);
      TextView date = new TextView(this);
      date.setTextSize(12);
      date.setTextColor(MUTED);
      date.setText(
          e.optString("date")
              + " · "
              + (e.optBoolean("ai") ? "AI 生成" : e.optString("source"))
              + " · "
              + (Library.available(this, e) ? "已缓存" : "旧缓存已清理"));
      container.addView(date);
      LinearLayout row = new LinearLayout(this);
      container.addView(row);
      Button apply = new Button(this);
      apply.setText(Library.available(this, e) ? "应用" : "恢复并应用");
      apply.setTextColor(ACCENT);
      apply.setTextSize(14);
      apply.setAllCaps(false);
      apply.setOnClickListener(v -> applyRecord(e));
      addCardAction(row, apply, 1, 0xFFEDF5F0);
      Button fav = new Button(this);
      fav.setText(Library.favorite(this, e) ? "♥ 已收藏" : "♡ 收藏");
      fav.setTextColor(0xFFD84357);
      fav.setTextSize(14);
      fav.setAllCaps(false);
      fav.setOnClickListener(
          v -> {
            try {
              Library.toggle(this, e);
              if (favorites) show("favorites");
              else fav.setText(Library.favorite(this, e) ? "♥ 已收藏" : "♡ 收藏");
            } catch (Exception ex) {
              toast(Net.safeError(ex));
            }
          });
      addCardAction(row, fav, 1, 0xFFFCF0F2);
      Button more = new Button(this);
      more.setText("详情");
      more.setTextSize(14);
      more.setTextColor(MUTED);
      more.setOnClickListener(v -> historyDetail(e));
      addCardAction(row, more, .7f, PAPER);
      final int generation = pageGeneration;
      thumbnails.execute(
          () -> {
            if (generation != pageGeneration) return;
            Bitmap b = null;
            try {
              b = Library.thumbnail(getApplicationContext(), e);
            } catch (Exception ignored) {
            }
            final Bitmap result = b;
            runOnUiThread(
                () -> {
                  if (generation == pageGeneration && !isFinishing()) {
                    cover.setImageBitmap(result);
                    if (result == null) badge.setText("旧缓存不可用 · 可尝试从来源恢复");
                  }
                });
          });
    }
    if (endIndex < a.length())
      button(
          "下一页",
          () -> {
            libraryPageIndex++;
            show(page);
          });
  }

  void addCardAction(LinearLayout row, Button button, float weight, int color) {
    button.setBackground(background(color, 10));
    button.setElevation(0);
    button.setStateListAnimator(null);
    button.setPadding(0, 0, 0, 0);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), weight);
    lp.topMargin = dp(10);
    lp.leftMargin = dp(3);
    lp.rightMargin = dp(3);
    row.addView(button, lp);
  }

  void applyRecord(JSONObject e) {
    if (Updater.BUSY.get()) {
      toast("正在处理，请稍候");
      return;
    }
    UpdateService.startLibrary(this, Library.id(e));
    show("today");
  }

  void previewMedia(JSONObject e) {
    if (!Library.available(this, e)) {
      toast("旧版本已清理这份缓存，可点击「恢复并应用」从来源重新获取");
      return;
    }
    if (e.optString("kind").equals("video")) {
      VideoView video = new VideoView(this);
      FrameLayout frame = new FrameLayout(this);
      frame.addView(video, new FrameLayout.LayoutParams(-1, dp(250), Gravity.CENTER));
      MediaController controls = new MediaController(this);
      video.setMediaController(controls);
      AlertDialog dialog =
          new AlertDialog.Builder(this)
              .setTitle(e.optString("title", "视频预览"))
              .setView(frame)
              .setPositiveButton("关闭", null)
              .create();
      video.setVideoURI(Uri.parse(e.optString("videoUri")));
      video.setOnPreparedListener(
          m -> {
            m.setLooping(true);
            m.setVolume(0, 0);
            video.start();
          });
      video.setOnErrorListener(
          (v, what, extra) -> {
            toast("视频缓存无法播放");
            return true;
          });
      dialog.setOnDismissListener(d -> video.stopPlayback());
      dialog.show();
      Ui.finishDialog(dialog, false);
    } else {
      ImageView image = new ImageView(this);
      image.setScaleType(ImageView.ScaleType.FIT_CENTER);
      FrameLayout frame = new FrameLayout(this);
      frame.addView(image, new FrameLayout.LayoutParams(-1, dp(280)));
      image.setImageBitmap(
          BitmapFactory.decodeFile(
              new File(Store.root(this), e.optString("imageFile")).toString()));
      Ui.show(
          new AlertDialog.Builder(this)
              .setTitle(e.optString("title", "图片预览"))
              .setView(frame)
              .setPositiveButton("关闭", null));
    }
  }

  void historyDetail(JSONObject e) {
    StringBuilder detail = new StringBuilder();
    for (String key : new String[] {"hotspot", "style", "prompt"})
      if (!e.optString(key).isEmpty()) detail.append(e.optString(key)).append("\n\n");
    String source = e.optString("sourceUrl", e.optString("source"));
    if (!source.isEmpty()) detail.append("来源：").append(source);
    if (!e.optString("imageModel").isEmpty())
      detail.append("\n生成模型：").append(e.optString("imageModel"));
    AlertDialog.Builder d =
        new AlertDialog.Builder(this)
            .setTitle(e.optString("title", "素材详情"))
            .setMessage(detail.toString().trim())
            .setPositiveButton("关闭", null);
    if (!e.optString("prompt").isEmpty())
      d.setNeutralButton(
          "用它创作",
          (a, b) -> {
            if (Updater.BUSY.get()) {
              toast("请等当前任务完成后再创作");
              return;
            }
            try {
              JSONObject copy = new JSONObject(e.toString());
              copy.put("date", Store.today());
              Bailian.saveDraft(this, copy);
              show("create");
            } catch (Exception ex) {
              toast(Net.safeError(ex));
            }
          });
    if (!e.optString("sourceUrl").isEmpty())
      d.setNegativeButton("查看来源", (a, b) -> openLink(e.optString("sourceUrl")));
    Ui.show(d);
  }

  void showNotices() {
    try {
      String s =
          new String(
              readAll(getAssets().open("references.txt")), java.nio.charset.StandardCharsets.UTF_8);
      Ui.show(
          new AlertDialog.Builder(this)
              .setTitle("开源与许可")
              .setMessage(s)
              .setPositiveButton("关闭", null));
    } catch (Exception e) {
      toast("无法读取许可说明");
    }
  }

  byte[] readAll(InputStream in) throws Exception {
    try (InputStream input = in;
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Store.copy(input, out, 256 * 1024);
      return out.toByteArray();
    }
  }

  void openLink(String url) {
    if (url.startsWith("https://"))
      try {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
      } catch (Exception e) {
        toast("没有可用浏览器");
      }
  }

  void start(String action) {
    if (Updater.BUSY.get()) {
      toast("正在处理，请稍候");
      return;
    }
    if ((action.equals("draft") || action.equals("image") || action.equals("ai-image"))
        && !SecretVault.configured(this)) {
      Ui.show(
          new AlertDialog.Builder(this)
              .setTitle("连接 AI 服务")
              .setMessage("填写自己的 API Key 后，即可选题与生成图片。")
              .setNegativeButton("稍后", null)
              .setPositiveButton("去配置", (d, n) -> showPanel("AI 服务", this::providerControls)));
      return;
    }
    if (action.equals("ai-image")) Store.prefs(this).edit().putString("source", "daily").apply();
    UpdateService.start(this, action);
    Store.prefs(this).edit().putString("status", "正在准备，请稍候…").apply();
    refresh();
  }

  void pick(boolean video) {
    Intent i =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType(video ? "video/*" : "image/*")
            .addCategory(Intent.CATEGORY_OPENABLE);
    startActivityForResult(i, video ? 12 : 11);
  }

  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result == RESULT_OK
        && data != null
        && data.getData() != null
        && (request == 11 || request == 12))
      Updater.importMedia(
          this,
          data.getData(),
          request == 12,
          () ->
              runOnUiThread(
                  () -> {
                    previewVersion = "";
                    refresh();
                  }));
  }

  void refresh() {
    JSONObject s = Store.state(this);
    updateHeart();
    if (mediaTitle != null) {
      mediaTitle.setText(s.optString("title", "你的背屏"));
      mediaCaption.setText(
          (s.optInt("mode", 1) == 2 ? "视频" : "图片")
              + " · "
              + new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                  .format(new Date(s.optLong("updatedAt", System.currentTimeMillis())))
              + " · 轻点预览");
    }
    if (status != null) {
      String value = Store.prefs(this).getString("status", "");
      boolean show =
          Updater.BUSY.get()
              || value.contains("未完成")
              || value.contains("失败")
              || value.equals("正在准备，请稍候…");
      status.setVisibility(show ? View.VISIBLE : View.GONE);
      status.setText(value);
    }
    if (preview != null) {
      String ver = s.optString("version") + ":" + s.optInt("mode");
      if (!ver.equals(previewVersion)) {
        previewVersion = ver;
        final ImageView target = preview;
        final JSONObject captured = s;
        Updater.EXEC.execute(
            () -> {
              if (preview != target) return;
              Bitmap b = null;
              try {
                if (captured.optInt("mode", 2) == 2) {
                  android.media.MediaMetadataRetriever m =
                      new android.media.MediaMetadataRetriever();
                  try {
                    m.setDataSource(MainActivity.this, Uri.parse(captured.optString("videoUri")));
                    Bitmap raw = m.getScaledFrameAtTime(0, 2, 976, 596);
                    if (raw != null) {
                      b = CameraLayout.compose(raw);
                      raw.recycle();
                    }
                  } finally {
                    m.release();
                  }
                } else
                  b =
                      BitmapFactory.decodeFile(
                          new File(Store.root(MainActivity.this), captured.optString("imageFile"))
                              .toString());
              } catch (Exception ignored) {
              }
              final Bitmap result = b;
              runOnUiThread(
                  () -> {
                    if (preview == target) target.setImageBitmap(result);
                  });
            });
      }
    }
    if (promptSummary != null) {
      String raw = Store.prefs(this).getString("aiDraft", "{}");
      JSONObject d = Bailian.draft(this);
      boolean ready = !d.optString("topicKey").isEmpty() && !d.optString("prompt").isEmpty();
      if (!raw.equals(draftLoaded)) {
        topicView.setText(
            ready ? d.optString("title") + "\n" + d.optString("hotspot") : "还没有灵感，先换个选题吧。");
        promptSummary.setText(
            ready ? promptPreview(d.optString("prompt")) : "AI 会为你写好画面描述，你也可以按喜好修改。");
        draftLoaded = raw;
      }
      generateButton.setEnabled(ready && !Updater.BUSY.get());
      generateButton.setAlpha(generateButton.isEnabled() ? 1f : .45f);
    }
    if (page.equals("welcome") && Store.prefs(this).getLong("setup.rearReadAt", 0) > 0)
      show("today");
  }

  protected void onResume() {
    super.onResume();
    handler.post(ticker);
    if (rearSetupDialog != null && !rearSetupDialog.closed) rearSetupDialog.refresh();
  }

  protected void onPause() {
    handler.removeCallbacks(ticker);
    super.onPause();
  }

  protected void onDestroy() {
    if (rearSetupDialog != null && !rearSetupDialog.closed) rearSetupDialog.dialog.dismiss();
    if (activeDialog != null) activeDialog.dismiss();
    handler.removeCallbacks(ticker);
    thumbnails.shutdownNow();
    super.onDestroy();
  }

  @Override
  public void onBackPressed() {
    if (!page.equals("today") && !page.equals("welcome")) show("today");
    else super.onBackPressed();
  }

  protected void onSaveInstanceState(Bundle out) {
    out.putString("page", page);
    if (editorInput != null && !editorKind.isEmpty()) {
      out.putString("editor_kind", editorKind);
      out.putString("editor_text", editorInput.getText().toString());
    }
    if (rearSetupDialog != null && !rearSetupDialog.closed) {
      out.putBoolean("setup_open", true);
      out.putInt("setup_tab", rearSetupDialog.tabs.getCheckedRadioButtonId());
      out.putBoolean("setup_adb", rearSetupDialog.adbExpanded);
      out.putBoolean("setup_shizuku_help", rearSetupDialog.shizukuHelp);
    }
    super.onSaveInstanceState(out);
  }
}
