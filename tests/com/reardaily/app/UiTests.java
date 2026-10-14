package com.reardaily.app;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.File;
import org.json.JSONObject;

final class UiTests {
  static void onMain(Instrumentation test, Runnable task) {
    final Throwable[] error = {null};
    test.runOnMainSync(
        () -> {
          try {
            task.run();
          } catch (Throwable t) {
            error[0] = t;
          }
        });
    if (error[0] != null) throw new AssertionError("UI assertion failed", error[0]);
  }

  static View find(View v, String value) {
    if (v instanceof TextView && value.contentEquals(((TextView) v).getText())) return v;
    if (value.contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription()))
      return v;
    if (v instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
        View found = find(((ViewGroup) v).getChildAt(i), value);
        if (found != null) return found;
      }
    return null;
  }

  static void click(View v, String value) {
    View target = find(v, value);
    if (target == null) throw new AssertionError("Missing control: " + value);
    target.performClick();
  }

  static void check(StringBuilder report, boolean value, String label) {
    if (!value) throw new AssertionError(label);
    report.append("PASS ").append(label).append('\n');
  }

  static String run(Instrumentation test) throws Exception {
    Context real = test.getTargetContext();
    String beforeState = Store.state(real).toString();
    String beforeDraft = Store.prefs(real).getString("aiDraft", "");
    boolean beforeKey = SecretVault.configured(real);
    StringBuilder report = new StringBuilder();
    Instrumentation.ActivityMonitor launch =
        test.addMonitor(UiTestActivity.class.getName(), null, false);
    try (ParcelFileDescriptor descriptor =
        test.getUiAutomation()
            .executeShellCommand("am start -n com.reardaily.app/.UiTestActivity --ez reset true")) {
      try (java.io.FileInputStream in =
          new java.io.FileInputStream(descriptor.getFileDescriptor())) {
        byte[] bytes = new byte[1024];
        while (in.read(bytes) != -1) {}
      }
    }
    Activity launched = test.waitForMonitorWithTimeout(launch, 10000);
    test.removeMonitor(launch);
    if (launched == null) throw new AssertionError("Test activity launch timed out");
    final UiTestActivity[] current = {(UiTestActivity) launched};
    try {
      test.waitForIdleSync();
      onMain(
          test,
          () -> {
            UiTestActivity a = current[0];
            check(
                report,
                a.page.equals("welcome") && MainActivity.needsWelcome(a),
                "fresh install starts at rear-screen setup choices");
            click(a.shell, "手动设置背屏");
            check(
                report,
                a.rearSetupDialog.tabs.getCheckedRadioButtonId() == 7101,
                "manual entry opens the manual guide tab");
            a.rearSetupDialog.dialog.dismiss();
            check(
                report,
                MainActivity.needsWelcome(a),
                "opening or dismissing a guide does not mark setup complete");
            click(a.shell, "自动设置背屏");
            check(
                report,
                a.rearSetupDialog.tabs.getCheckedRadioButtonId() == 7100,
                "automatic entry opens the automatic guide tab");
            RearSetupDialog guide = a.rearSetupDialog;
            check(report, find(guide.content, "1  通过 Shizuku 安装") != null
                && find(guide.content, "复制安装命令") == null,
                "Shizuku is primary and computer commands are collapsed");
            click(guide.content, "使用电脑 ADB 设置");
            check(report, find(guide.content, "复制安装命令") != null,
                "ADB fallback remains available on demand");
            click(guide.content, "如何启动 Shizuku");
            check(report, find(guide.content, "查看官方启动指南") != null,
                "in-app wireless startup help includes official guide");
            a.rearSetupDialog.dialog.dismiss();
            click(a.shell, "稍后设置，先看看");
            check(
                report,
                a.page.equals("today") && !MainActivity.needsWelcome(a),
                "explicit skip persists and opens the home page");
            a.show("create");
            check(
                report,
                a.topicSummary.getMaxLines() == 4 && !(a.topicSummary instanceof EditText),
                "prompt preview is read-only and capped at four lines");
            String original = Bailian.topicPrompt(a);
            a.openEditor("topic", null);
            check(
                report,
                a.editorInput.getText().toString().equals(original),
                "modify opens the complete prompt without truncating it");
            a.editorInput.setText("Cancel this unsaved test edit");
            a.activeDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            check(
                report,
                Bailian.topicPrompt(a).equals(original),
                "cancel preserves the previously saved prompt");
            a.openEditor("topic", null);
            a.editorInput.setText("为背屏选择动漫山水与建筑主题，使用柔和自然光，横向构图并留出镜头区域。");
          });
      Instrumentation.ActivityMonitor monitor =
          test.addMonitor(UiTestActivity.class.getName(), null, false);
      onMain(test, () -> current[0].recreate());
      Activity recreated = test.waitForMonitorWithTimeout(monitor, 10000);
      test.removeMonitor(monitor);
      if (recreated == null) throw new AssertionError("Activity recreation timeout");
      current[0] = (UiTestActivity) recreated;
      test.waitForIdleSync();
      onMain(
          test,
          () -> {
            UiTestActivity a = current[0];
            check(
                report,
                a.editorInput != null && a.editorInput.getText().toString().contains("动漫山水"),
                "unsaved prompt survives activity recreation");
            a.activeDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            check(
                report,
                Bailian.topicPrompt(a).contains("动漫山水")
                    && (a.activeDialog == null || !a.activeDialog.isShowing()),
                "save commits the prompt and returns to its collapsed preview");
            check(
                report,
                a.topicSummary.getText().toString().contains("动漫山水"),
                "collapsed preview reflects the saved edit");
            a.openEditor("topic", null);
            a.activeDialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            check(
                report,
                a.editorInput.getText().toString().equals(Bailian.DEFAULT_TOPIC_PROMPT),
                "restore default is available inside the editor");
            a.activeDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            check(
                report,
                Bailian.topicPrompt(a).contains("动漫山水"),
                "cancel also cancels a default reset");
            try {
              JSONObject draft =
                  new JSONObject()
                      .put("date", Store.today())
                      .put("topicKey", "ui-test")
                      .put("title", "测试灵感")
                      .put("hotspot", "测试背景");
              String visual = "水墨风格的远山与湖面，温暖的晨光照亮右侧建筑，左侧保留干净的天空与水面，构图舒展，画面不放文字。";
              draft.put("prompt", Bailian.withContext(draft, visual));
              Bailian.saveDraft(a, draft);
              a.show("create");
              check(
                  report,
                  a.promptSummary.getText().toString().startsWith(visual),
                  "four-line image preview begins with the visual description");
              a.openEditor("image", null);
              check(
                  report,
                  a.editorInput.getText().toString().contains("今日选题 ")
                      && a.editorInput.getText().toString().contains(visual),
                  "full editor retains generation context and all prompt content");
              a.editorInput.setText("too short");
              a.activeDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
              check(
                  report,
                  a.activeDialog.isShowing() && a.editorInput.getError() != null,
                  "invalid prompt remains editable without overwriting the draft");
              a.activeDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
              check(
                  report,
                  Bailian.draft(a).getString("prompt").contains(visual),
                  "validation failure preserves saved generation content");
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
            Store.prefs(a)
                .edit()
                .remove("onboarding.dismissed")
                .putLong("setup.rearReadAt", 1)
                .commit();
            check(
                report,
                !MainActivity.needsWelcome(a),
                "previously connected users bypass onboarding after an upgrade");
            a.show("welcome");
            a.refresh();
            check(
                report, a.page.equals("today"), "confirmed setup transitions from welcome to home");
          });
      onMain(
          test,
          () -> {
            UiTestActivity a = current[0];
            a.show("settings");
            a.dailySettings();
            View decor = a.activeDialog.getWindow().getDecorView();
            SeekBar image = (SeekBar) find(decor, "图片占比");
            SeekBar ai = (SeekBar) find(decor, "AI 图片占全部内容的比例");
            image.setProgress(2);
            ai.setProgress(8);
            check(report, ai.getProgress() == 2, "AI share stays within the selected image share");
            click(decor, "保存");
            check(
                report,
                AIConfig.imagePercent(a) == 20 && AIConfig.aiPercent(a) == 20,
                "simplified probability controls save the chosen proportions");
            a.dailySettings();
            ((SeekBar) find(a.activeDialog.getWindow().getDecorView(), "图片占比")).setProgress(9);
            a.activeDialog.dismiss();
            check(
                report,
                AIConfig.imagePercent(a) == 20,
                "closing settings without saving leaves daily preferences unchanged");
          });
      check(
          report,
          beforeState.equals(Store.state(real).toString())
              && beforeDraft.equals(Store.prefs(real).getString("aiDraft", ""))
              && beforeKey == SecretVault.configured(real),
          "UI regression leaves the user's wallpaper, draft and API configuration intact");
      return report.toString();
    } finally {
      onMain(
          test,
          () -> {
            if (!current[0].isDestroyed()) current[0].finish();
          });
      // Wait for local file preparation before removing the isolated test storage.
      RearSetup.EXEC.submit(() -> {}).get();
      real.getSharedPreferences("ui_test_settings", 0).edit().clear().commit();
      real.getSharedPreferences("ui_test_vault", 0).edit().clear().commit();
      File internal = new File(real.getFilesDir(), "ui-test"),
          external = new File(real.getExternalFilesDir(null), "ui-test");
      V05Tests.remove(internal, internal.getCanonicalPath());
      V05Tests.remove(external, external.getCanonicalPath());
      DailyJob.schedule(real);
    }
  }
}
