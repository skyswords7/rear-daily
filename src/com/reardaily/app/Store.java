package com.reardaily.app;

import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.AtomicFile;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;

final class Store {
  static final int HISTORY_LIMIT = 30;
  static final String AUTH = "com.reardaily.media";
  static final Uri STATE = Uri.parse("content://" + AUTH + "/state");
  static final Object LOCK = new Object();

  static File root(Context c) {
    File f = new File(c.getFilesDir(), "daily");
    f.mkdirs();
    return f;
  }

  static AtomicFile stateFile(Context c) {
    return new AtomicFile(new File(root(c), "state.json"));
  }

  static String today() {
    return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
  }

  static android.content.SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("settings", 0);
  }

  static JSONObject state(Context c) {
    synchronized (LOCK) {
      try {
        return new JSONObject(
            new String(stateFile(c).readFully(), java.nio.charset.StandardCharsets.UTF_8));
      } catch (Exception e) {
        return new JSONObject();
      }
    }
  }

  static void write(Context c, JSONObject v) throws IOException {
    AtomicFile a = stateFile(c);
    FileOutputStream out = null;
    try {
      out = a.startWrite();
      out.write(v.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      a.finishWrite(out);
    } catch (Exception e) {
      if (out != null) a.failWrite(out);
      throw new IOException(e);
    }
  }

  static void copy(InputStream in, OutputStream out, long max) throws IOException {
    byte[] b = new byte[65536];
    long size = 0;
    int n;
    while ((n = in.read(b)) != -1) {
      size += n;
      if (size > max) throw new IOException("素材文件过大");
      out.write(b, 0, n);
    }
  }

  static File asset(Context c, String name) throws IOException {
    File f = File.createTempFile("seed-", ".tmp", c.getCacheDir());
    try (InputStream in = c.getAssets().open(name);
        OutputStream out = new FileOutputStream(f)) {
      copy(in, out, 45L * 1024 * 1024);
    }
    return f;
  }

  static JSONArray limitHistory(JSONArray records) {
    JSONArray out = new JSONArray();
    if (records != null)
      for (int i = 0; i < records.length() && out.length() < HISTORY_LIMIT; i++)
        if (records.optJSONObject(i) != null) out.put(records.optJSONObject(i));
    return out;
  }

  static void trimHistory(Context c) throws Exception {
    synchronized (LOCK) {
      JSONObject state = state(c);
      JSONArray records = state.optJSONArray("dailyHistory");
      if (records != null && records.length() > HISTORY_LIMIT) {
        state.put("dailyHistory", limitHistory(records));
        write(c, state);
      }
    }
  }

  static void ensure(Context c) throws Exception {
    synchronized (LOCK) {
      if (!state(c).has("version")) {
        File i = asset(c, "welcome.jpg"), v = asset(c, "welcome.mp4");
        try {
          publish(c, i, v, "每日背屏 · 从这里开始", "demo", "");
        } finally {
          i.delete();
          v.delete();
        }
      }
      trimHistory(c);
      Library.migrate(c);
      grant(c, state(c));
    }
  }

  static void grant(Context c, JSONObject s) {
    String text = s.optString("videoUri");
    if (text.isEmpty()) return;
    for (String pkg : new String[] {"com.android.thememanager", "com.xiaomi.subscreencenter"})
      try {
        c.grantUriPermission(pkg, Uri.parse(text), Intent.FLAG_GRANT_READ_URI_PERMISSION);
      } catch (Exception ignored) {
      }
  }

  static void normaliseImage(File src, File dst) throws IOException {
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(src.toString(), bounds);
    if (bounds.outWidth < 1
        || bounds.outHeight < 1
        || ((long) bounds.outWidth * bounds.outHeight) > 100000000L)
      throw new IOException("图片无法识别或尺寸过大");
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inSampleSize = 1;
    while (bounds.outWidth / o.inSampleSize > 2400 || bounds.outHeight / o.inSampleSize > 1600)
      o.inSampleSize *= 2;
    Bitmap original = BitmapFactory.decodeFile(src.toString(), o);
    if (original == null) throw new IOException("图片解码失败");
    Bitmap result = CameraLayout.compose(original);
    try (OutputStream out = new FileOutputStream(dst)) {
      if (!result.compress(Bitmap.CompressFormat.JPEG, 92, out)) throw new IOException("图片缓存失败");
    } finally {
      original.recycle();
      result.recycle();
    }
  }

  static void validateVideo(File f) throws IOException {
    if (f.length() < 32 || f.length() > 45L * 1024 * 1024) throw new IOException("视频需小于 45 MB");
    MediaMetadataRetriever m = new MediaMetadataRetriever();
    try {
      m.setDataSource(f.toString());
      int width =
          Integer.parseInt(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
      int height =
          Integer.parseInt(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
      long duration =
          Long.parseLong(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
      if (width < 1
          || height < 1
          || width > 3840
          || height > 3840
          || duration < 1
          || duration > 60000) throw new IOException("视频需在 60 秒以内，尺寸不超过 4K");
    } catch (Exception e) {
      throw new IOException("视频校验失败：" + e.getMessage(), e);
    } finally {
      m.release();
    }
  }

  static JSONObject publish(
      Context c, File image, File video, String title, String source, String sourceVersion)
      throws Exception {
    return publish(c, image, video, title, source, sourceVersion, null);
  }

  static JSONObject publish(
      Context c,
      File image,
      File video,
      String title,
      String source,
      String sourceVersion,
      JSONObject metadata)
      throws Exception {
    synchronized (LOCK) {
      JSONObject previous = state(c);
      String gen = System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
      File imageOut = null;
      Uri newVideo = null;
      try {
        if (image != null) {
          imageOut = new File(root(c), gen + ".jpg");
          normaliseImage(image, imageOut);
        }
        if (video != null) validateVideo(video);
        JSONObject next = new JSONObject(previous.toString());
        next.put("version", gen);
        next.put("title", title);
        next.put("source", source);
        next.put("sourceVersion", sourceVersion);
        next.put("updatedAt", System.currentTimeMillis());
        if (metadata != null) {
          JSONObject event = new JSONObject(metadata.toString());
          event.put("at", System.currentTimeMillis());
          event.put("version", gen);
          String kind = event.optString("kind", "image");
          next.put("activeKind", kind).put("mode", kind.equals("video") ? 2 : 1);
          if (!kind.equals(previous.optString("activeKind"))
              || previous.optString("kindSince").isEmpty()) next.put("kindSince", today());
          next.put("currentMetadata", event);
          if (image != null) next.put("imageMetadata", event);
          if (video != null) next.put("videoMetadata", event);
          JSONArray events = new JSONArray().put(event),
              oldEvents = previous.optJSONArray("dailyHistory");
          if (oldEvents != null)
            for (int j = 0; j < oldEvents.length() && j < HISTORY_LIMIT - 1; j++)
              events.put(oldEvents.getJSONObject(j));
          next.put("dailyHistory", events);
          if (event.optBoolean("daily", false)) next.put("dailyDay", today());
        }
        if (imageOut != null) next.put("imageFile", imageOut.getName());
        if (video != null) {
          ContentValues cv = new ContentValues();
          cv.put(MediaStore.Video.Media.DISPLAY_NAME, "daily-" + gen + ".mp4");
          cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
          cv.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RearDaily");
          cv.put(MediaStore.Video.Media.IS_PENDING, 1);
          newVideo =
              c.getContentResolver()
                  .insert(
                      MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
          if (newVideo == null) throw new IOException("视频发布失败");
          try (InputStream in = new FileInputStream(video);
              OutputStream out = c.getContentResolver().openOutputStream(newVideo)) {
            if (out == null) throw new IOException("视频无法写入");
            copy(in, out, 45L * 1024 * 1024);
          }
          cv.clear();
          cv.put(MediaStore.Video.Media.IS_PENDING, 0);
          c.getContentResolver().update(newVideo, cv, null, null);
          String path = "";
          try (Cursor cur =
              c.getContentResolver()
                  .query(newVideo, new String[] {MediaStore.Video.Media.DATA}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) path = cur.getString(0);
          }
          if (path.isEmpty()) throw new IOException("系统未返回视频路径");
          next.put("videoUri", newVideo.toString());
          next.put("videoPath", path);
          MediaMetadataRetriever dimensions = new MediaMetadataRetriever();
          try {
            dimensions.setDataSource(video.toString());
            double
                vw =
                    Double.parseDouble(
                        dimensions.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)),
                vh =
                    Double.parseDouble(
                        dimensions.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            String rotation =
                dimensions.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            next.put(
                "videoAspect",
                ("90".equals(rotation) || "270".equals(rotation)) ? vh / vw : vw / vh);
          } finally {
            dimensions.release();
          }
        }
        JSONObject event = next.optJSONObject("currentMetadata");
        if (metadata != null && event != null) {
          Library.refs(event, next);
          next.put(event.optString("kind", "image") + "Metadata", event);
          next.put("dailyHistory", Library.prepend(event, previous.optJSONArray("dailyHistory")));
          JSONArray favorites = next.optJSONArray("favorites");
          if (favorites != null)
            for (int i = 0; i < favorites.length(); i++) {
              JSONObject favorite = favorites.optJSONObject(i);
              if (favorite != null && Library.id(favorite).equals(Library.id(event)))
                favorites.put(
                    i,
                    new JSONObject(event.toString())
                        .put("favoriteAt", favorite.optLong("favoriteAt")));
            }
        }
        JSONArray history = new JSONArray();
        if (previous.has("version")) {
          JSONObject old = new JSONObject();
          old.put("imageFile", previous.optString("imageFile"));
          old.put("videoUri", previous.optString("videoUri"));
          history.put(old);
        }
        JSONArray before = previous.optJSONArray("history");
        if (before != null && before.length() > 0) history.put(before.getJSONObject(0));
        next.put("history", history);
        grant(c, next);
        write(c, next);
        clean(c, next, previous);
        try {
          c.getContentResolver().notifyChange(STATE, null);
        } catch (Exception ignored) {
        }
        android.util.Log.i("RearDaily", "PUBLISHED version=" + gen + " source=" + source);
        return next;
      } catch (Exception e) {
        if (imageOut != null) imageOut.delete();
        if (newVideo != null)
          try {
            c.getContentResolver().delete(newVideo, null, null);
          } catch (Exception ignored) {
          }
        throw e;
      }
    }
  }

  static void clean(Context c, JSONObject next, JSONObject previous) {
    Set<String> images = new HashSet<>(),
        uris = new HashSet<>(),
        oldImages = new HashSet<>(),
        oldUris = new HashSet<>();
    Library.collect(next, images, uris);
    Library.collect(previous, oldImages, oldUris);
    File[] files = root(c).listFiles();
    if (files != null)
      for (File f : files)
        if (f.getName().matches("[0-9]+-[a-f0-9]{8}\\.jpg") && !images.contains(f.getName()))
          f.delete();
    for (String u : oldUris)
      if (u.startsWith("content://media/") && !uris.contains(u))
        try {
          c.revokeUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION);
          c.getContentResolver().delete(Uri.parse(u), null, null);
        } catch (Exception ignored) {
        }
  }

  static File imageFile(Context c, String name) throws FileNotFoundException {
    if (!name.matches("[0-9]+-[a-f0-9]{8}\\.jpg")) throw new FileNotFoundException("Unknown image");
    File f = new File(root(c), name);
    if (!f.isFile()) throw new FileNotFoundException("Expired image");
    return f;
  }

  static String imageUri(JSONObject s) {
    return "content://" + AUTH + "/image/" + s.optString("imageFile");
  }

  static void selectMode(Context c, int mode) throws Exception {
    synchronized (LOCK) {
      JSONObject s = state(c);
      String kind = mode == 2 ? "video" : "image";
      if (mode != 3) {
        if (!kind.equals(s.optString("activeKind"))) s.put("kindSince", today());
        s.put("activeKind", kind);
      }
      s.put("mode", mode);
      JSONObject selected = s.optJSONObject(kind + "Metadata");
      if (selected != null && mode != 3) {
        s.put("currentMetadata", selected)
            .put("title", selected.optString("title", s.optString("title")))
            .put("source", selected.optString("source", s.optString("source")));
      }
      write(c, s);
      prefs(c).edit().putInt("mode", mode).apply();
    }
    c.getContentResolver().notifyChange(STATE, null);
  }

  static void demo(Context c, boolean red) throws Exception {
    File i = asset(c, red ? "welcome-alt.jpg" : "welcome.jpg"), v = asset(c, "welcome.mp4");
    try {
      publish(c, i, v, red ? "每日背屏 · 暖色山景" : "每日背屏 · 从这里开始", "demo", red ? "red" : "blue");
    } finally {
      i.delete();
      v.delete();
    }
  }
}
