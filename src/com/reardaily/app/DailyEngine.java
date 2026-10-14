package com.reardaily.app;

import android.content.*;
import java.io.*;
import java.security.*;
import java.util.*;
import org.json.*;

final class DailyEngine {
  static final Random RANDOM = new SecureRandom();

  static boolean due(Context c) {
    android.content.SharedPreferences p = Store.prefs(c);
    return p.getBoolean("auto", true)
        && p.getString("source", "demo").equals("daily")
        && !Store.today().equals(Store.state(c).optString("dailyDay"))
        && System.currentTimeMillis() - p.getLong("lastAttempt", 0) >= 3600000;
  }

  static void status(Context c, String s) {
    Store.prefs(c).edit().putString("status", s).apply();
  }

  static boolean check(Context c, boolean force, Runnable done) {
    if (!force && !due(c)) {
      if (done != null) done.run();
      return false;
    }
    if (!Updater.BUSY.compareAndSet(false, true)) {
      if (done != null) done.run();
      return false;
    }
    Store.prefs(c).edit().putLong("lastAttempt", System.currentTimeMillis()).apply();
    Updater.EXEC.execute(
        () -> {
          try {
            runDaily(c);
          } catch (Exception e) {
            status(c, "更新未完成：" + Net.safeError(e));
          } finally {
            Updater.BUSY.set(false);
            notifyState(c);
            if (done != null) done.run();
          }
        });
    return true;
  }

  static JSONObject plan(Context c) throws Exception {
    JSONObject old = Store.state(c);
    String day = Store.today();
    JSONObject p;
    try {
      p = new JSONObject(Store.prefs(c).getString("dayPlan", "{}"));
    } catch (Exception e) {
      p = new JSONObject();
    }
    boolean ai = Store.prefs(c).getBoolean("aiEnabled", true) && SecretVault.configured(c);
    if (!day.equals(p.optString("date"))) {
      DailyPolicy.Choice choice =
          DailyPolicy.choose(
              RANDOM,
              ai,
              AIConfig.imagePercent(c),
              AIConfig.aiPercent(c),
              old.optString("activeKind"),
              old.optString("kindSince"),
              day);
      p =
          new JSONObject()
              .put("date", day)
              .put("kind", choice.kind)
              .put("ai", choice.ai)
              .put("forced", choice.forced);
      if (!Store.prefs(c).edit().putString("dayPlan", p.toString()).commit())
        throw new IOException("每日计划保存失败");
    }
    if (!ai) p.put("ai", false);
    return p;
  }

  static void runDaily(Context c) throws Exception {
    JSONObject p = plan(c);
    String kind = p.getString("kind"), note = "";
    if (p.optBoolean("ai")) {
      try {
        status(c, "正在准备今日 AI 选题…");
        JSONObject d = Bailian.draft(c), pending;
        try {
          pending = new JSONObject(Store.prefs(c).getString("pendingAi", "{}"));
        } catch (Exception ex) {
          pending = new JSONObject();
        }
        if (Store.today().equals(pending.optString("date"))
            && (!pending.optString("mediaUrl").isEmpty()
                || !pending.optString("localMedia").isEmpty())) {
          status(c, "正在下载已生成图片…");
          apply(c, pending, true);
          Store.prefs(c).edit().remove("pendingAi").apply();
          status(c, "今日 AI 图片已缓存");
          return;
        }
        if (Store.today().equals(Store.prefs(c).getString("aiImageAttemptDay", "")))
          throw new IOException("今日生图已提交，不自动重复扣费");
        if (!Store.today().equals(d.optString("date")) || d.optString("prompt").isEmpty())
          d = Bailian.generateDraft(c);
        status(c, "正在生成 1 张图片…");
        JSONObject media = Bailian.generateImage(c, d, false);
        apply(c, media, true);
        Store.prefs(c).edit().remove("pendingAi").apply();
        status(c, "今日 AI 图片已缓存");
        return;
      } catch (Exception e) {
        note = "AI 未完成（" + Net.safeError(e) + "），";
      }
    }
    Set<String> rejected = new HashSet<>();
    Exception last = null;
    for (int i = 0; i < 3; i++) {
      JSONObject media = null;
      try {
        status(c, note + "正在寻找" + (kind.equals("video") ? "动漫／游戏视频" : "动漫日榜图片") + "…");
        media =
            kind.equals("video")
                ? PopularSource.video(c, rejected)
                : PopularSource.image(c, rejected);
        status(c, note + "正在缓存：" + media.optString("title"));
        apply(c, media, true);
        status(c, note + "今日素材已缓存，唤醒背屏即可显示");
        return;
      } catch (Exception e) {
        last = e;
        if (media != null) rejected.add(media.optString("sourceId"));
      }
    }
    // Even without Internet, switch to the other already-cached type on the fourth day.
    JSONObject s = Store.state(c);
    if (p.optBoolean("forced") && !kind.equals(s.optString("activeKind"))) {
      boolean exists =
          kind.equals("image")
              ? new File(Store.root(c), s.optString("imageFile")).isFile()
              : !s.optString("videoUri").isEmpty();
      JSONObject cachedMeta = s.optJSONObject(kind + "Metadata");
      if (cachedMeta != null
          && (cachedMeta.optString("source").equals("wallhaven")
              || cachedMeta.optString("source").equals("motionbgs"))) {
        if (!cachedMeta.optBoolean("keywordChecked")
            || !KeywordFilter.match(c, cachedMeta).isEmpty()) exists = false;
      }
      if (exists) {
        JSONObject meta = cachedMeta;
        meta = meta == null ? new JSONObject() : new JSONObject(meta.toString());
        meta.put("kind", kind)
            .put("date", Store.today())
            .put("source", "offline-cache")
            .put("daily", false)
            .put(
                "title",
                "离线轮换 · " + meta.optString("title", kind.equals("video") ? "缓存视频" : "缓存图片"));
        Store.publish(c, null, null, meta.getString("title"), "offline-cache", Store.today(), meta);
        status(c, note + "网络不可用，已切换为另一类型的缓存素材；稍后重试");
        return;
      }
    }
    throw last == null ? new IOException("素材源暂不可用，保留原缓存") : last;
  }

  static String hash(File file) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(file)) {
      byte[] b = new byte[65536];
      int n;
      while ((n = in.read(b)) != -1) md.update(b, 0, n);
    }
    StringBuilder s = new StringBuilder();
    for (byte b : md.digest()) s.append(String.format(Locale.ROOT, "%02x", b & 255));
    return s.toString();
  }

  static void apply(Context c, JSONObject info, boolean daily) throws Exception {
    File f = null;
    JSONObject meta = new JSONObject(info.toString());
    boolean video = meta.optString("kind").equals("video");
    boolean local = !meta.optString("localMedia").isEmpty(), published = false;
    try {
      boolean web =
          meta.optString("source").equals("wallhaven")
              || meta.optString("source").equals("motionbgs");
      if (web) KeywordFilter.requireAllowed(c, meta);
      f =
          local
              ? CompatibleAI.pendingFile(c, meta.getString("localMedia"))
              : Updater.download(c, meta.getString("mediaUrl"), (video ? 45L : 20L) * 1024 * 1024);
      if (web)
        KeywordFilter.requireAllowed(c, meta); // Recheck if settings changed while downloading.
      String hash = hash(f);
      JSONArray h = Bailian.history(c);
      if (daily && !meta.optBoolean("selectedByUser"))
        for (int i = 0; i < h.length(); i++)
          if (hash.equals(h.getJSONObject(i).optString("sha256")))
            throw new IOException("该素材近期已使用，换一个候选");
      meta.put("sha256", hash).put("daily", daily).put("date", Store.today());
      // Signed image download URLs are transient. History keeps provenance and parameters, not
      // credentials.
      meta.remove("mediaUrl");
      meta.remove("localMedia");
      meta.remove("llmResponse");
      Store.publish(
          c,
          video ? null : f,
          video ? f : null,
          meta.optString("title", "每日素材"),
          meta.optString("source"),
          meta.optString("sourceId", hash),
          meta);
      published = true;
      if (daily) Store.prefs(c).edit().putLong("lastSuccess", System.currentTimeMillis()).apply();
    } finally {
      if (f != null && (!local || published)) f.delete();
    }
  }

  static void selected(Context c, String url, boolean video, Runnable done) {
    if (!Updater.BUSY.compareAndSet(false, true)) {
      if (done != null) done.run();
      return;
    }
    Updater.EXEC.execute(
        () -> {
          try {
            status(c, "正在读取选中的作品…");
            JSONObject media = PopularSource.selected(c, url, video);
            status(c, "正在缓存：" + media.optString("title"));
            apply(c, media, true);
            status(c, video ? "选中的视频已缓存并应用" : "选中的图片已缓存并应用");
          } catch (Exception e) {
            status(c, "应用未完成：" + Net.safeError(e));
          } finally {
            Updater.BUSY.set(false);
            notifyState(c);
            if (done != null) done.run();
          }
        });
  }

  static void manual(Context c, String action, Runnable done) {
    if (!Updater.BUSY.compareAndSet(false, true)) {
      if (done != null) done.run();
      return;
    }
    Updater.EXEC.execute(
        () -> {
          try {
            if (action.equals("popular-image") || action.equals("popular-video")) {
              boolean video = action.equals("popular-video");
              Set<String> excluded = new HashSet<>();
              Exception last = null;
              boolean complete = false;
              for (int i = 0; i < 3; i++) {
                JSONObject media = null;
                try {
                  status(c, video ? "正在寻找网络视频…" : "正在寻找网络图片…");
                  media =
                      video ? PopularSource.video(c, excluded) : PopularSource.image(c, excluded);
                  apply(c, media, true);
                  complete = true;
                  status(c, video ? "网络视频已缓存并全屏展示" : "网络图片已缓存并全屏展示");
                  break;
                } catch (Exception ex) {
                  last = ex;
                  if (media != null) excluded.add(media.optString("sourceId"));
                }
              }
              if (!complete) throw last == null ? new IOException("暂时没有可用素材") : last;
            } else if (action.equals("draft")) {
              status(c, "正在检索话题并撰写提示词…");
              Bailian.generateDraft(c);
              status(c, "选题已准备，可编辑提示词后生成");
            } else if (action.equals("image") || action.equals("ai-image")) {
              JSONObject d;
              if (action.equals("ai-image")) {
                status(c, "正在选题并撰写提示词…");
                d = Bailian.generateDraft(c);
              } else d = Bailian.draft(c);
              if (d.optString("prompt").isEmpty()) throw new IOException("请先获取今日选题");
              status(c, "正在生成 1 张图片，请稍候…");
              JSONObject media = Bailian.generateImage(c, d, true);
              apply(c, media, true);
              Store.prefs(c).edit().remove("pendingAi").apply();
              status(c, "AI 图片已缓存，唤醒背屏查看");
            }
          } catch (Exception e) {
            status(c, "操作未完成：" + Net.safeError(e));
          } finally {
            Updater.BUSY.set(false);
            notifyState(c);
            if (done != null) done.run();
          }
        });
  }

  static void library(Context c, String id, Runnable done) {
    if (!Updater.BUSY.compareAndSet(false, true)) {
      if (done != null) done.run();
      return;
    }
    Updater.EXEC.execute(
        () -> {
          try {
            JSONObject e = Library.find(c, id);
            if (Library.available(c, e)) {
              status(c, "正在应用本地缓存…");
              Library.reuse(c, e);
            } else {
              String source = e.optString("source");
              if (!source.equals("motionbgs") && !source.equals("wallhaven"))
                throw new IOException("旧版本已清理原文件，无法恢复此素材；新的历史与收藏会保留缓存");
              status(c, "正在从原站恢复历史素材…");
              JSONObject restored =
                  PopularSource.selected(
                      c, e.optString("sourceUrl"), e.optString("kind").equals("video"));
              restored.put("mediaId", id).put("restoredFromHistory", true);
              apply(c, restored, true);
            }
            status(c, "已应用，唤醒背屏即可显示");
          } catch (Exception e) {
            status(c, "应用未完成：" + Net.safeError(e));
          } finally {
            Updater.BUSY.set(false);
            notifyState(c);
            if (done != null) done.run();
          }
        });
  }

  static void notifyState(Context c) {
    try {
      c.getContentResolver().notifyChange(Store.STATE, null);
    } catch (Exception ignored) {
    }
  }
}
