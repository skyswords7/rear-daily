package com.reardaily.app;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Two pages in one dialog; file preparation runs outside the UI thread. */
final class RearSetupDialog {
  final MainActivity activity;
  final AlertDialog dialog;
  final LinearLayout content;
  final TextView summary;
  final RadioGroup tabs;
  final ScrollView scroll;
  boolean closed, busy, details, adbExpanded, shizukuHelp;
  final ShizukuSetup shizuku;
  final Runnable shizukuChanged;
  String message = "";
  EditText manualFolder;
  String manualName;

  RearSetupDialog(MainActivity activity) {
    this.activity = activity;
    shizuku = ShizukuSetup.get(activity);
    shizukuChanged = () -> { message = shizuku.message; refresh(); };
    manualName = Store.prefs(activity).getString("setup.manualName", "");
    LinearLayout panel = new LinearLayout(activity);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(activity.dp(20), activity.dp(4), activity.dp(20), 0);
    summary = new TextView(activity);
    summary.setTextColor(activity.MUTED);
    summary.setTextSize(13);
    summary.setPadding(0, activity.dp(8), 0, activity.dp(12));
    panel.addView(summary);
    tabs = new RadioGroup(activity);
    tabs.setOrientation(RadioGroup.HORIZONTAL);
    for (int i = 0; i < 2; i++) {
      RadioButton tab = new RadioButton(activity);
      tab.setId(7100 + i);
      tab.setText(i == 0 ? "自动设置" : "手动设置");
      Ui.segment(tab);
      tabs.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));
    }
    tabs.setPadding(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4));
    tabs.setBackground(Ui.shape(activity, Ui.PAPER, 14, false));
    panel.addView(tabs);
    scroll = new ScrollView(activity);
    content = new LinearLayout(activity);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(0, activity.dp(8), 0, activity.dp(16));
    scroll.addView(content);
    panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    dialog =
        new AlertDialog.Builder(activity)
            .setTitle("设置背屏")
            .setView(panel)
            .setNegativeButton("关闭", null)
            .setPositiveButton("检测连接", null)
            .create();
    dialog.setOnDismissListener(
        d -> {
          if (manualFolder != null) manualName = manualFolder.getText().toString();
          Store.prefs(activity).edit().putString("setup.manualName", manualName).apply();
          closed = true;
          shizuku.removeListener(shizukuChanged);
          if (activity.page.equals("welcome")
              && Store.prefs(activity).getLong("setup.rearReadAt", 0) > 0) activity.show("today");
        });
    tabs.setOnCheckedChangeListener(
        (group, id) -> {
          details = false;
          render();
          scroll.scrollTo(0, 0);
        });
    tabs.check(7100);
  }

  void show(boolean openPreview) {
    dialog.show();
    shizuku.addListener(shizukuChanged);
    if (shizuku.busy()) message = shizuku.message;
    Ui.finishDialog(dialog, true);
    dialog
        .getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(
            v -> {
              if (Store.prefs(activity).getLong("setup.rearReadAt", 0) > 0) dialog.dismiss();
              else inspect();
            });
    refresh();
    if (openPreview && RearSetup.receipt(activity).has("path")) launchPreview();
    else if (!RearSetup.unsupported(activity).isEmpty()) render();
    else if (!shizuku.busy()) prepare();
  }

  void refresh() {
    if (closed) return;
    shizuku.reconnect();
    boolean connected = Store.prefs(activity).getLong("setup.rearReadAt", 0) > 0;
    summary.setText(connected ? "已连接 · 背屏已读取当前模板" : "只需设置一次，以后在 App 中换图即可。");
    summary.setTextColor(connected ? Ui.ACCENT : Ui.MUTED);
    if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null)
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(connected ? "完成" : "检测连接");
    if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null)
      dialog
          .getButton(AlertDialog.BUTTON_NEGATIVE)
          .setVisibility(connected ? View.GONE : View.VISIBLE);
    if (shizuku.busy()) message = shizuku.message;
    render();
    scroll.postDelayed(() -> {
      if (!closed && !busy && !activity.isFinishing() && !activity.isDestroyed()
          && dialog.isShowing() && dialog.getWindow().getDecorView().hasWindowFocus()
          && shizuku.takePreview()) launchPreview();
    }, 250);
  }

  void text(String value, boolean heading) {
    TextView t = new TextView(activity);
    t.setText(value);
    t.setTextSize(heading ? 17 : 14);
    t.setTextColor(heading ? activity.INK : activity.MUTED);
    t.setPadding(0, activity.dp(heading ? 14 : 6), 0, activity.dp(6));
    t.setLineSpacing(activity.dp(4), 1f);
    t.setTextIsSelectable(true);
    if (heading) t.setTypeface(null, Typeface.BOLD);
    content.addView(t);
  }

  Button button(String label, Runnable action, boolean enabled) {
    Button b = new Button(activity);
    b.setText(label);
    boolean primary = label.equals("授权并自动设置") || label.equals("自动设置背屏")
        || label.equals("安装 Shizuku") || label.equals("打开 Shizuku")
        || label.equals("复制安装命令") || label.equals("复制替换命令");
    Ui.styleButton(b, primary);
    if (!primary)
      b.setBackground(Ui.touch(activity, Ui.PAPER, 12));
    b.setEnabled(enabled && !busy && !shizuku.busy());
    b.setAlpha(enabled && !busy && !shizuku.busy() ? 1f : .45f);
    b.setOnClickListener(
        v -> {
          try {
            action.run();
          } catch (Exception e) {
            message = "操作未完成：" + Net.safeError(e);
            render();
          }
        });
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = activity.dp(8);
    content.addView(b, lp);
    return b;
  }

  void render() {
    if (closed) return;
    if (manualFolder != null) manualName = manualFolder.getText().toString();
    manualFolder = null;
    content.removeAllViews();
    if (!message.isEmpty()) text(message, false);
    if (busy || shizuku.busy()) {
      ProgressBar p = new ProgressBar(activity);
      content.addView(p, new LinearLayout.LayoutParams(activity.dp(24), activity.dp(24)));
    }
    boolean supported = RearSetup.unsupported(activity).isEmpty(), prepared = false;
    try {
      File ready = new File(RearSetup.stage(activity), "ready");
      prepared =
          supported
              && ready.length() == 32
              && RearSetup.id(activity)
                  .equals(new String(Files.readAllBytes(ready.toPath()), StandardCharsets.UTF_8));
    } catch (Exception ignored) {
    }
    if (!supported) text(RearSetup.unsupported(activity), false);
    if (tabs.getCheckedRadioButtonId() == 7100) {
      String autoReason = RearSetup.automaticUnsupported(activity);
      if (supported && !autoReason.isEmpty()) text(autoReason, false);
      renderShizuku(autoReason.isEmpty());
      button(adbExpanded ? "收起电脑 ADB 方式" : "使用电脑 ADB 设置", () -> {
        adbExpanded = !adbExpanded;
        render();
      }, true);
      if (adbExpanded) {
        text("使用电脑安装", true);
        text("电脑安装 ADB，手机开启并允许 USB 调试。连接后，在电脑终端执行下面的安装命令。", false);
        button("下载 ADB 工具", () -> activity.startActivity(new Intent(Intent.ACTION_VIEW,
            Uri.parse("https://developer.android.com/tools/releases/platform-tools"))), true);
        button("复制安装命令", () -> copyCommand(""), prepared);
        button("分享命令到电脑", () -> {
          try {
            activity.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, RearSetup.command(activity, "")),
                "分享安装命令"));
          } catch (Exception e) { activity.toast(Net.safeError(e)); }
        }, prepared);
        if (!prepared) button("准备安装文件", this::prepare, supported);
      }
      text("2  应用到背屏", true);
      text("安装完成后，在系统预览中点“应用”，再唤醒背屏。若已添加过，选用列表中的“每日背屏”即可。", false);
      button(
          "打开系统预览",
          this::launchPreview,
          autoReason.isEmpty()
              && RearSetup.target(activity).equals(RearSetup.receipt(activity).optString("path")));
    } else {
      text("自动设置不适用时，按这四步操作。", false);
      text("1  创建一张背屏壁纸", true);
      text("进入系统背屏设置 → 背屏个性化 → AI 壁纸。用已有图片或模板保存一张专用壁纸，记住名称与预览。无需付费生成图片。", false);
      button(
          "打开背屏设置",
          () -> {
            try {
              activity.startActivity(RearSetup.settingsIntent());
            } catch (Exception e) {
              activity.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
            }
          },
          supported);
      text("2  找到壁纸目录", true);
      text("用 USB 连接已安装 ADB 的电脑，开启并允许 USB 调试。在电脑执行查询命令，对照预览确认刚创建的壁纸。多个结果时不要直接选最新目录。", false);
      button("复制查询命令", () -> copyCommand("list"), prepared);
      if (!prepared) button("准备安装文件", this::prepare, supported);
      text("3  备份并替换", true);
      text("填写确认后的目录名，只需最后一段。脚本会先备份原文件，再安装模板；换机后请重新查询。", false);
      EditText folder = new EditText(activity);
      Ui.field(folder);
      folder.setHint("壁纸目录名");
      folder.setSingleLine(true);
      folder.setText(manualName);
      manualFolder = folder;
      content.addView(folder);
      button("复制替换命令", () -> manualCommand(folder, "manual"), prepared);
      text("4  重新应用", true);
      text("回到系统设置，退出旧预览，再选中这张壁纸并点“应用”。唤醒背屏后，点下方“检测连接”。", false);
      if (details) {
        text("恢复原壁纸", true);
        text("执行恢复命令后，在系统里重新应用这张壁纸。App 中的图片、视频和收藏会保留。", false);
        button("复制恢复命令", () -> manualCommand(folder, "restore"), prepared);
      }
    }
    button(
        details ? "收起帮助" : "遇到问题？",
        () -> {
          details = !details;
          render();
        },
        true);
    if (details) {
      text("Shizuku 连接", true);
      text("若 Shizuku 已显示运行但这里仍未连接，请从最近任务划掉每日背屏后重新打开。未授权时，打开 Shizuku → 已授权应用，允许每日背屏。连接中断时先重新启动 Shizuku；超时后可重试。手机重启后，若要重新安装模板，需要再次启动 Shizuku。已设置好的背屏日常换图不依赖它常驻。", false);
      button("打开 Shizuku 管理", () -> shizuku.openManager(activity), true);
      text("电脑连接与安装", true);
      text(
          "• 找不到设备：运行 adb devices，并在手机上允许调试。\n"
              + "• Windows：终端位于工具目录时，可将 adb 改为 .\\adb.exe。\n"
              + "• 多台设备：在 adb 后添加 -s 设备序列号。\n"
              + "• 权限不足：请使用 Shizuku 或 adb shell，普通文件管理器无法直接写入系统主题目录。\n"
              + "• 手动查询无结果：先创建兼容的 AI 壁纸模板，目录内应有 rearscreen 和 description.xml。",
          false);
      text("背屏没有变化", true);
      text("重新选择并应用正确的壁纸，再唤醒背屏。系统升级后失效时可重新准备模板；自动设置不兼容时切换“手动设置”。", false);
      button("重新准备安装文件", this::prepare, supported);
      text(RearSetup.status(activity), false);
    }
  }

  void renderShizuku(boolean supported) {
    text("1  通过 Shizuku 安装", true);
    ShizukuSetup.Availability status = shizuku.availability();
    switch (status) {
      case MISSING:
        text("无需电脑。首次安装并启动 Shizuku，再返回这里授权即可。", false);
        button("安装 Shizuku", () -> shizuku.openManager(activity), supported);
        break;
      case STOPPED:
        text("Shizuku 尚未连接。打开它完成启动，再回到这里继续。", false);
        button("打开 Shizuku", () -> shizuku.openManager(activity), supported);
        break;
      case OLD:
        text("请先更新 Shizuku，自动设置需要 v13 或更高版本。", false);
        button("更新 Shizuku", () -> activity.startActivity(new Intent(Intent.ACTION_VIEW,
            Uri.parse("https://shizuku.rikka.app/download/"))), true);
        break;
      case DENIED:
        text("Shizuku 已连接。授权后将自动准备并安装背屏模板。", false);
        button("授权并自动设置", shizuku::start, supported);
        break;
      case READY:
        text("Shizuku 已就绪，可以直接安装背屏模板。", false);
        button("自动设置背屏", shizuku::start, supported);
        break;
    }
    button(shizukuHelp ? "收起启动说明" : "如何启动 Shizuku", () -> {
      shizukuHelp = !shizukuHelp;
      render();
    }, true);
    if (shizukuHelp) {
      text("在手机上完成启动", true);
      text("① 连接 Wi-Fi，在开发者选项中开启无线调试。\n"
          + "② 在 Shizuku 中选择配对，按它的提示输入无线调试配对码。\n"
          + "③ 返回 Shizuku 点启动，显示正在运行后回到本页。", false);
      button("查看官方启动指南", () -> activity.startActivity(new Intent(Intent.ACTION_VIEW,
          Uri.parse("https://shizuku.rikka.app/zh-hans/guide/setup/"))), true);
    }
  }

  void manualCommand(EditText folder, String mode) {
    String name = folder.getText().toString().trim();
    if (!name.matches("[a-zA-Z0-9_-]{1,128}")) {
      folder.setError("请只填写已核对的目录名");
      return;
    }
    Store.prefs(activity).edit().putString("setup.manualName", name).apply();
    copyCommand(mode + " " + RearSetup.quote(name));
  }

  void copyCommand(String mode) {
    try {
      ((android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE))
          .setPrimaryClip(ClipData.newPlainText("每日背屏安装命令", RearSetup.command(activity, mode)));
      activity.toast("命令已复制，请在电脑终端执行");
    } catch (Exception e) {
      activity.toast(Net.safeError(e));
    }
  }

  void prepare() {
    if (busy || shizuku.busy()) return;
    busy = true;
    message = "正在准备安装文件…";
    render();
    RearSetup.EXEC.execute(
        () -> {
          String result;
          try {
            RearSetup.prepare(activity.getApplicationContext());
            result = "";
          } catch (Exception e) {
            result = "准备未完成：" + Net.safeError(e);
          }
          final String outcome = result;
          activity.runOnUiThread(
              () -> {
                busy = false;
                message = outcome;
                refresh();
              });
        });
  }

  void launchPreview() {
    if (busy) return;
    busy = true;
    message = "正在打开系统预览…";
    render();
    RearSetup.EXEC.execute(
        () -> {
          try {
            Intent intent = RearSetup.previewIntent(activity);
            activity.runOnUiThread(
                () -> {
                  busy = false;
                  if (closed || activity.isFinishing() || activity.isDestroyed()) return;
                  try {
                    activity.startActivity(intent);
                    message =
                        intent.getComponent() == null
                            ? "选用系统列表中的“每日背屏”，然后唤醒背屏。"
                            : "请在系统预览中点击应用，然后唤醒背屏。";
                  } catch (Exception e) {
                    message = "无法打开预览，请切换手动设置：" + Net.safeError(e);
                  }
                  refresh();
                });
          } catch (Exception | LinkageError e) {
            activity.runOnUiThread(
                () -> {
                  busy = false;
                  message = "当前系统不支持自动预览，请切换手动设置。";
                  refresh();
                });
          }
        });
  }

  void inspect() {
    message =
        Store.prefs(activity).getLong("setup.rearReadAt", 0) > 0
            ? "连接成功，现在可以自由换图了。"
            : "暂未检测到背屏连接。请先应用模板并唤醒背屏，再试一次。";
    try {
      File result = new File(RearSetup.stage(activity), "result");
      if (details && result.isFile() && result.length() < 4096)
        message +=
            "\n安装反馈："
                + new String(Files.readAllBytes(result.toPath()), StandardCharsets.UTF_8).trim();
    } catch (Exception ignored) {
    }
    refresh();
  }
}
