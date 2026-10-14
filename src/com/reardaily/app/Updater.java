package com.reardaily.app;

import android.content.*;
import android.net.Uri;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

final class Updater {
  static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
  static final AtomicBoolean BUSY = new AtomicBoolean();

  static String endpoint(Context c) {
    android.content.SharedPreferences p = Store.prefs(c);
    return p.getString("endpoint." + p.getString("source", "demo"), "").trim();
  }

  static boolean check(Context ctx, boolean force, Runnable done) {
    Context c = ctx.getApplicationContext();
    android.content.SharedPreferences p = Store.prefs(c);
    String source = p.getString("source", "demo"), url = endpoint(c);
    if (source.equals("daily")) return DailyEngine.check(c, force, done);
    if (source.equals("demo")
        || url.isEmpty()
        || (!force
            && (!p.getBoolean("auto", true)
                || Store.today().equals(p.getString("successDay." + source, ""))
                || System.currentTimeMillis() - p.getLong("lastAttempt", 0) < 3600000))) {
      if (done != null) done.run();
      return false;
    }
    if (!BUSY.compareAndSet(false, true)) {
      if (done != null) done.run();
      return false;
    }
    p.edit()
        .putLong("lastAttempt", System.currentTimeMillis())
        .putString("status", "正在检查今日素材…")
        .apply();
    EXEC.execute(
        () -> {
          File image = null, video = null;
          try {
            JSONObject manifest =
                new JSONObject(new String(fetchSmall(url), StandardCharsets.UTF_8));
            String iv = manifest.optString("imageUrl", "").trim(),
                vv = manifest.optString("videoUrl", "").trim(),
                version = manifest.optString("version", Store.today());
            if (iv.isEmpty() && vv.isEmpty()) throw new IOException("素材源未返回图片或视频");
            JSONObject old = Store.state(c);
            if (force
                || !source.equals(old.optString("source"))
                || !version.equals(old.optString("sourceVersion"))) {
              if (!iv.isEmpty()) image = download(c, iv, 20L * 1024 * 1024);
              if (!vv.isEmpty()) video = download(c, vv, 45L * 1024 * 1024);
              Store.publish(c, image, video, manifest.optString("title", "每日素材"), source, version);
            }
            p.edit()
                .putString("successDay." + source, Store.today())
                .putLong("lastSuccess", System.currentTimeMillis())
                .putString("status", "今日素材已缓存")
                .apply();
          } catch (Exception e) {
            p.edit().putString("status", "更新未完成，继续使用缓存：" + Net.safeError(e)).apply();
            android.util.Log.w("RearDaily", "Update failed: " + e.getClass().getSimpleName());
          } finally {
            if (image != null) image.delete();
            if (video != null) video.delete();
            BUSY.set(false);
            try {
              c.getContentResolver().notifyChange(Store.STATE, null);
            } catch (Exception ignored) {
            }
            if (done != null) done.run();
          }
        });
    return true;
  }

  static HttpURLConnection connect(String url) throws IOException {
    URL u = new URL(url);
    for (int i = 0; i < 6; i++) {
      String scheme = u.getProtocol();
      if (!scheme.equals("https")
          && !(scheme.equals("http")
              && (u.getHost().equals("127.0.0.1") || u.getHost().equals("localhost"))))
        throw new IOException("素材源需要 HTTPS 地址");
      if (u.getUserInfo() != null) throw new IOException("地址中不能包含账号密码");
      HttpURLConnection cn = (HttpURLConnection) u.openConnection();
      cn.setConnectTimeout(15000);
      cn.setReadTimeout(25000);
      cn.setInstanceFollowRedirects(false);
      cn.setRequestProperty("User-Agent", "RearDaily/0.3");
      cn.setRequestProperty("Cache-Control", "no-cache");
      int code = cn.getResponseCode();
      if (code >= 300 && code < 400) {
        String loc = cn.getHeaderField("Location");
        cn.disconnect();
        if (loc == null) throw new IOException("无效重定向");
        u = new URL(u, loc);
        continue;
      }
      if (code != 200) {
        cn.disconnect();
        throw new IOException("HTTP " + code);
      }
      return cn;
    }
    throw new IOException("重定向过多");
  }

  static byte[] fetchSmall(String url) throws IOException {
    HttpURLConnection cn = connect(url);
    try (InputStream in = cn.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Store.copy(in, out, 256 * 1024);
      return out.toByteArray();
    } finally {
      cn.disconnect();
    }
  }

  static File download(Context c, String url, long max) throws IOException {
    File f = File.createTempFile("daily-download-", ".tmp", c.getCacheDir());
    boolean ok = false;
    HttpURLConnection cn = null;
    try {
      cn = connect(url);
      if (cn.getContentLengthLong() > max) throw new IOException("素材文件过大");
      try (InputStream in = cn.getInputStream();
          OutputStream out = new FileOutputStream(f)) {
        Store.copy(in, out, max);
      }
      ok = true;
      return f;
    } finally {
      if (cn != null) cn.disconnect();
      if (!ok) f.delete();
    }
  }

  static void importMedia(Context c, Uri uri, boolean video, Runnable done) {
    EXEC.execute(
        () -> {
          File f = null;
          try {
            f = File.createTempFile("daily-import-", ".tmp", c.getCacheDir());
            try (InputStream in = c.getContentResolver().openInputStream(uri);
                OutputStream out = new FileOutputStream(f)) {
              if (in == null) throw new IOException("无法读取素材");
              Store.copy(in, out, 45L * 1024 * 1024);
            }
            Store.publish(
                c,
                video ? null : f,
                video ? f : null,
                video ? "导入的视频" : "导入的图片",
                "local",
                "",
                new JSONObject()
                    .put("kind", video ? "video" : "image")
                    .put("date", Store.today())
                    .put("title", video ? "导入的视频" : "导入的图片")
                    .put("source", "local"));
            Store.prefs(c).edit().putString("status", "素材已缓存，唤醒背屏即可查看").apply();
          } catch (Exception e) {
            Store.prefs(c).edit().putString("status", "导入失败：" + Net.safeError(e)).apply();
          } finally {
            if (f != null) f.delete();
            if (done != null) done.run();
          }
        });
  }
}
