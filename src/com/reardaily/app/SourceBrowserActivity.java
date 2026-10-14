package com.reardaily.app;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.view.inputmethod.*;
import android.webkit.*;
import android.widget.*;

/**
 * The original source website, with a native action to cache the selected artwork. No JS bridge.
 */
public class SourceBrowserActivity extends Activity {
  WebView web;
  EditText search;
  TextView message, title;
  Button use;
  boolean video, motionSite;
  final Handler handler = new Handler(Looper.getMainLooper());
  final Runnable ticker =
      new Runnable() {
        public void run() {
          updateAction();
          if (Updater.BUSY.get())
            message.setText(Store.prefs(SourceBrowserActivity.this).getString("status", "正在缓存…"));
          else if (waiting) {
            waiting = false;
            message.setText(Store.prefs(SourceBrowserActivity.this).getString("status", "已完成"));
          }
          handler.postDelayed(this, 1000);
        }
      };
  boolean waiting, loading;
  final Runnable loadTimeout =
      () -> {
        if (loading) message.setText("网站暂未响应，可重新搜索" + (video ? "" : "或点击右上角切换图片站"));
      };

  int dp(int n) {
    return (int) (getResources().getDisplayMetrics().density * n);
  }

  Button button(String text, Runnable action) {
    Button b = new Button(this);
    b.setText(text);
    Ui.styleButton(b, false);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setStatusBarColor(Ui.PAPER);
    getWindow().setNavigationBarColor(Ui.PAPER);
    getWindow().getDecorView();
    if (getWindow().getInsetsController() != null)
      getWindow()
          .getInsetsController()
          .setSystemBarsAppearance(
              WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                  | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
              WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                  | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    video = getIntent().getBooleanExtra("video", false);
    motionSite =
        state == null
            ? video
                || Store.prefs(this)
                    .getString("imageBrowserSource", "wallhaven")
                    .equals("motionbgs")
            : state.getBoolean("motionSite", video);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(1);
    root.setBackgroundColor(Ui.PAPER);
    Ui.insets(this, root, 12);
    LinearLayout top = new LinearLayout(this);
    top.addView(button("‹", () -> goBack()), new LinearLayout.LayoutParams(dp(48), dp(48)));
    title = new TextView(this);
    updateTitle();
    title.setTextSize(18);
    title.setTextColor(Ui.INK);
    title.setPadding(dp(12), 0, 0, 0);
    title.setGravity(Gravity.CENTER_VERTICAL);
    top.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
    if (!video)
      top.addView(
          button(
              "换站",
              () ->
                  Ui.show(
                      new AlertDialog.Builder(this)
                          .setTitle("选择图片网站")
                          .setItems(
                              new String[] {"Wallhaven", "MotionBGS"},
                              (d, n) -> {
                                motionSite = n == 1;
                                Store.prefs(this)
                                    .edit()
                                    .putString(
                                        "imageBrowserSource",
                                        motionSite ? "motionbgs" : "wallhaven")
                                    .apply();
                                updateTitle();
                                web.loadUrl(
                                    MediaSearch.browse(motionSite, MediaSearch.keyword(this)));
                              }))),
          new LinearLayout.LayoutParams(dp(68), dp(48)));
    root.addView(top);
    LinearLayout find = new LinearLayout(this);
    search = new EditText(this);
    Ui.field(search);
    search.setSingleLine(true);
    search.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
    search.setTextSize(14);
    search.setHint("搜索关键词或粘贴作品链接");
    search.setText(MediaSearch.keyword(this));
    search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
    find.addView(search, new LinearLayout.LayoutParams(0, dp(52), 1));
    find.addView(button("搜索", () -> search()), new LinearLayout.LayoutParams(dp(76), dp(52)));
    root.addView(find);
    search.setOnEditorActionListener(
        (v, action, event) -> {
          if (action == EditorInfo.IME_ACTION_SEARCH) {
            search();
            return true;
          }
          return false;
        });
    message = new TextView(this);
    message.setText("先挑选作品，再应用到背屏。网页预览不经过滤。");
    message.setTextSize(12);
    message.setTextColor(Ui.MUTED);
    message.setPadding(dp(4), dp(5), dp(4), dp(5));
    root.addView(message);
    web = new WebView(this);
    WebSettings settings = web.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    settings.setSupportMultipleWindows(false);
    settings.setMediaPlaybackRequiresUserGesture(true);
    settings.setBuiltInZoomControls(true);
    settings.setDisplayZoomControls(false);
    web.setWebViewClient(
        new WebViewClient() {
          @Override
          public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest r) {
            if (!r.isForMainFrame()) return false;
            if (MediaSearch.allowed(r.getUrl().toString(), motionSite)) return false;
            message.setText("请在当前素材网站内选择作品");
            return true;
          }

          @Override
          public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
            loading = true;
            handler.removeCallbacks(loadTimeout);
            handler.postDelayed(loadTimeout, 20000);
            message.setText("正在加载原站…");
            use.setEnabled(false);
          }

          @Override
          public void onPageFinished(WebView v, String url) {
            loading = false;
            handler.removeCallbacks(loadTimeout);
            if (!waiting) message.setText("先挑选作品，再应用到背屏。网页预览不经过滤。");
            updateAction();
          }

          @Override
          public void onPageCommitVisible(WebView v, String url) {
            loading = false;
            if (!waiting) message.setText("先挑选作品，再应用到背屏。网页预览不经过滤。");
            handler.removeCallbacks(loadTimeout);
            updateAction();
          }

          @Override
          public void onReceivedHttpError(
              WebView v, WebResourceRequest r, WebResourceResponse error) {
            if (r.isForMainFrame()) {
              loading = false;
              message.setText("网站返回 " + error.getStatusCode() + "，可重试" + (video ? "" : "或换站"));
              updateAction();
            }
          }

          @Override
          public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
            if (r.isForMainFrame()) message.setText("网页加载失败，可重试搜索或粘贴该站作品链接");
          }
        });
    // Website download buttons do not download untracked files; users choose via our native action.
    web.setDownloadListener(
        (url, agent, disposition, mime, length) -> message.setText("请使用底部按钮，下载当前作品并应用到背屏"));
    root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
    use = button(video ? "使用这段视频" : "使用这张图片", () -> applySelected());
    Ui.styleButton(use, true);
    use.setEnabled(false);
    root.addView(use, new LinearLayout.LayoutParams(-1, dp(54)));
    setContentView(root);
    if (state == null || web.restoreState(state) == null)
      web.loadUrl(MediaSearch.browse(motionSite, MediaSearch.keyword(this)));
  }

  void updateTitle() {
    title.setText((motionSite ? "MotionBGS" : "Wallhaven") + (video ? " · 视频" : " · 图片"));
  }

  void search() {
    String value = search.getText().toString().trim();
    if (value.startsWith("https://")) {
      if (!MediaSearch.allowed(value, motionSite)) {
        message.setText("请使用当前网站的 HTTPS 链接");
        return;
      }
      web.loadUrl(value);
    } else {
      try {
        web.loadUrl(MediaSearch.browse(motionSite, MediaSearch.clean(value)));
      } catch (Exception e) {
        message.setText(e.getMessage());
        return;
      }
    }
    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(search.getWindowToken(), 0);
    web.requestFocus();
    message.setText("点开作品详情后，可应用到背屏");
  }

  void updateAction() {
    if (use != null && web != null) {
      boolean enabled =
          !loading
              && !Updater.BUSY.get()
              && !MediaSearch.selected(web.getUrl(), motionSite).isEmpty();
      use.setEnabled(enabled);
      use.setAlpha(enabled ? 1f : 0.4f);
    }
  }

  void applySelected() {
    if (Updater.BUSY.get()) return;
    String selected = MediaSearch.selected(web.getUrl(), motionSite);
    if (selected.isEmpty()) {
      message.setText("请先点开一件作品的详情页");
      return;
    }
    waiting = true;
    message.setText("正在读取作品并检查排除词…");
    UpdateService.startSelected(this, selected, video);
    use.setEnabled(false);
  }

  void goBack() {
    if (web.canGoBack()) web.goBack();
    else finish();
  }

  @Override
  public void onBackPressed() {
    goBack();
  }

  @Override
  protected void onResume() {
    super.onResume();
    web.onResume();
    handler.post(ticker);
  }

  @Override
  protected void onPause() {
    handler.removeCallbacks(ticker);
    web.onPause();
    super.onPause();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    out.putBoolean("motionSite", motionSite);
    web.saveState(out);
    super.onSaveInstanceState(out);
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacks(ticker);
    handler.removeCallbacks(loadTimeout);
    web.destroy();
    super.onDestroy();
  }
}
