package com.reardaily.app;

import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.*;
import java.util.*;
import org.json.*;

/** Archived media references are owned by history, favorites, and active playback together. */
final class Library {
  static String id(JSONObject e) {
    String id = e.optString("mediaId");
    if (!id.isEmpty()) return id;
    String hash = e.optString("sha256");
    return e.optString("kind", "image") + ":" + (hash.isEmpty() ? e.optString("version") : hash);
  }

  static void refs(JSONObject e, JSONObject s) throws Exception {
    if ("video".equals(e.optString("kind"))) {
      e.put("videoUri", s.optString("videoUri"))
          .put("videoPath", s.optString("videoPath"))
          .put("videoAspect", s.optDouble("videoAspect", 1.64));
    } else e.put("imageFile", s.optString("imageFile"));
    e.put("mediaId", id(e));
  }

  static JSONObject current(Context c) {
    JSONObject s = Store.state(c);
    String kind = s.optInt("mode", 1) == 2 ? "video" : "image";
    JSONObject e = s.optJSONObject(kind + "Metadata");
    try {
      e =
          e == null
              ? new JSONObject()
                  .put("kind", kind)
                  .put("version", s.optString("version"))
                  .put("title", s.optString("title", "缓存素材"))
                  .put("source", s.optString("source"))
              : new JSONObject(e.toString());
      refs(e, s);
      return e;
    } catch (Exception ex) {
      return new JSONObject();
    }
  }

  static JSONArray favorites(Context c) {
    JSONArray a = Store.state(c).optJSONArray("favorites");
    return a == null ? new JSONArray() : a;
  }

  static boolean favorite(Context c, JSONObject e) {
    JSONArray a = favorites(c);
    for (int i = 0; i < a.length(); i++) if (id(e).equals(id(a.optJSONObject(i)))) return true;
    return false;
  }

  static boolean available(Context c, JSONObject e) {
    if (e == null) return false;
    try {
      if (!"video".equals(e.optString("kind")))
        return Store.imageFile(c, e.optString("imageFile")).isFile();
      String u = e.optString("videoUri");
      if (!u.startsWith("content://media/")) return false;
      try (android.os.ParcelFileDescriptor fd =
          c.getContentResolver().openFileDescriptor(Uri.parse(u), "r")) {
        return fd != null;
      }
    } catch (Exception ex) {
      return false;
    }
  }

  static JSONObject find(Context c, String id) throws Exception {
    JSONObject current = current(c);
    if (id.equals(id(current))) return current;
    for (JSONArray a : new JSONArray[] {favorites(c), Bailian.history(c)})
      for (int i = 0; i < a.length(); i++) {
        JSONObject e = a.optJSONObject(i);
        if (e != null && id.equals(id(e))) return new JSONObject(e.toString());
      }
    throw new IOException("这条记录已不在收藏或历史中");
  }

  static void toggle(Context c, JSONObject e) throws Exception {
    synchronized (Store.LOCK) {
      JSONObject s = Store.state(c), before = new JSONObject(s.toString());
      JSONArray a = favorites(c), out = new JSONArray();
      boolean found = false;
      for (int i = 0; i < a.length(); i++) {
        JSONObject old = a.getJSONObject(i);
        if (id(e).equals(id(old))) found = true;
        else out.put(old);
      }
      if (!found) {
        if (!available(c, e)) throw new IOException("旧缓存已清理，请先从来源恢复并应用，再收藏");
        JSONObject copy =
            new JSONObject(e.toString())
                .put("mediaId", id(e))
                .put("favoriteAt", System.currentTimeMillis());
        JSONArray added = new JSONArray().put(copy);
        for (int i = 0; i < out.length(); i++) added.put(out.get(i));
        out = added;
      }
      s.put("favorites", out);
      Store.write(c, s);
      Store.clean(c, s, before);
    }
    DailyEngine.notifyState(c);
  }

  static JSONArray prepend(JSONObject e, JSONArray old) {
    JSONArray out = new JSONArray().put(e);
    if (old != null)
      for (int i = 0; i < old.length() && out.length() < Store.HISTORY_LIMIT; i++) {
        JSONObject item = old.optJSONObject(i);
        if (item != null && !id(item).equals(id(e))) out.put(item);
      }
    return out;
  }

  static void reuse(Context c, JSONObject item) throws Exception {
    synchronized (Store.LOCK) {
      if (!available(c, item)) throw new IOException("缓存不可用，请从原站恢复");
      if ("wallhaven".equals(item.optString("source"))
          || "motionbgs".equals(item.optString("source"))) KeywordFilter.requireAllowed(c, item);
      JSONObject old = Store.state(c),
          s = new JSONObject(old.toString()),
          e = new JSONObject(item.toString());
      String kind = e.optString("kind", "image");
      if (kind.equals("video"))
        s.put("videoUri", e.getString("videoUri"))
            .put("videoPath", e.getString("videoPath"))
            .put("videoAspect", e.optDouble("videoAspect", 1.64));
      else s.put("imageFile", e.getString("imageFile"));
      e.put("mediaId", id(item)).put("at", System.currentTimeMillis()).put("reused", true);
      s.put(
              "version",
              System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8))
          .put("activeKind", kind)
          .put("mode", kind.equals("video") ? 2 : 1)
          .put("updatedAt", System.currentTimeMillis())
          .put("title", e.optString("title", "收藏素材"))
          .put("source", e.optString("source"))
          .put("currentMetadata", e)
          .put(kind + "Metadata", e)
          .put("dailyDay", Store.today())
          .put("dailyHistory", prepend(e, old.optJSONArray("dailyHistory")));
      if (!kind.equals(old.optString("activeKind"))) s.put("kindSince", Store.today());
      Store.grant(c, s);
      Store.write(c, s);
      Store.clean(c, s, old);
    }
    DailyEngine.notifyState(c);
  }

  static void collect(JSONObject s, Set<String> images, Set<String> videos) {
    if (s == null) return;
    images.add(s.optString("imageFile"));
    videos.add(s.optString("videoUri"));
    for (String key : new String[] {"currentMetadata", "imageMetadata", "videoMetadata"}) {
      JSONObject e = s.optJSONObject(key);
      if (e != null) {
        images.add(e.optString("imageFile"));
        videos.add(e.optString("videoUri"));
      }
    }
    for (String key : new String[] {"history", "dailyHistory", "favorites"}) {
      JSONArray a = s.optJSONArray(key);
      if (a == null) continue;
      for (int i = 0; i < a.length(); i++) {
        JSONObject e = a.optJSONObject(i);
        if (e != null) {
          images.add(e.optString("imageFile"));
          videos.add(e.optString("videoUri"));
        }
      }
    }
  }

  static void migrate(Context c) throws Exception {
    synchronized (Store.LOCK) {
      JSONObject s = Store.state(c);
      if (s.optInt("librarySchema") >= 1) return;
      Map<String, JSONObject> videos = new HashMap<>();
      Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
      try (Cursor cur =
          c.getContentResolver()
              .query(
                  collection,
                  new String[] {"_id", "_display_name", "_data"},
                  "relative_path = ?",
                  new String[] {"Movies/RearDaily/"},
                  null)) {
        if (cur != null)
          while (cur.moveToNext()) {
            String name = cur.getString(1);
            if (name != null && name.startsWith("daily-") && name.endsWith(".mp4"))
              videos.put(
                  name.substring(6, name.length() - 4),
                  new JSONObject()
                      .put(
                          "videoUri",
                          ContentUris.withAppendedId(collection, cur.getLong(0)).toString())
                      .put("videoPath", cur.getString(2)));
          }
      } catch (Exception ignored) {
      }
      JSONArray h = s.optJSONArray("dailyHistory");
      if (h != null)
        for (int i = 0; i < h.length(); i++) {
          JSONObject e = h.optJSONObject(i);
          if (e == null) continue;
          e.put("mediaId", id(e));
          if (e.optString("kind").equals("video")) {
            JSONObject v = videos.get(e.optString("version"));
            if (v != null) refs(e, v);
          } else e.put("imageFile", e.optString("version") + ".jpg");
        }
      for (String kind : new String[] {"image", "video"}) {
        JSONObject e = s.optJSONObject(kind + "Metadata");
        if (e == null)
          e =
              new JSONObject()
                  .put("kind", kind)
                  .put("title", kind.equals("video") ? "缓存视频" : "缓存图片")
                  .put("source", s.optString("source"))
                  .put("version", s.optString("version"));
        refs(e, s);
        s.put(kind + "Metadata", e);
      }
      s.put(
              "currentMetadata",
              s.getJSONObject(s.optInt("mode", 1) == 2 ? "videoMetadata" : "imageMetadata"))
          .put("dailyHistory", Store.limitHistory(h))
          .put("librarySchema", 1);
      Store.write(c, s);
    }
  }

  static Bitmap thumbnail(Context c, JSONObject e) throws Exception {
    if (!available(c, e)) return null;
    if (!"video".equals(e.optString("kind"))) {
      BitmapFactory.Options opt = new BitmapFactory.Options();
      opt.inSampleSize = 2;
      return BitmapFactory.decodeFile(Store.imageFile(c, e.getString("imageFile")).toString(), opt);
    }
    MediaMetadataRetriever m = new MediaMetadataRetriever();
    try {
      m.setDataSource(c, Uri.parse(e.getString("videoUri")));
      return m.getScaledFrameAtTime(0, 2, 488, 298);
    } finally {
      m.release();
    }
  }
}
