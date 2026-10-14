package com.reardaily.app;

import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import java.io.*;
import java.util.*;
import org.json.*;

final class V05Tests {
  static StringBuilder report = new StringBuilder();

  static void ok(boolean yes, String message) throws Exception {
    if (!yes) throw new Exception(message);
    report.append("PASS ").append(message).append('\n');
  }

  static JSONObject event(String title, String kind) throws Exception {
    return new JSONObject()
        .put("title", title)
        .put("kind", kind)
        .put("date", Store.today())
        .put("source", "local");
  }

  static String run(Context real) throws Exception {
    String ns = "v05_" + System.currentTimeMillis();
    File sandbox = new File(real.getCacheDir(), ns);
    sandbox.mkdirs();
    Context c =
        new ContextWrapper(real) {
          public File getFilesDir() {
            File f = new File(sandbox, "files");
            f.mkdirs();
            return f;
          }

          public File getCacheDir() {
            File f = new File(sandbox, "cache");
            f.mkdirs();
            return f;
          }

          public SharedPreferences getSharedPreferences(String name, int mode) {
            return real.getSharedPreferences(ns + "_" + name, mode);
          }
        };
    File image = Store.asset(c, "welcome.jpg"), video = Store.asset(c, "welcome.mp4");
    Set<String> testVideos = new HashSet<>();
    try {
      Random random = new Random(19);
      int imageCount = 0, aiCount = 0;
      for (int i = 0; i < 100000; i++) {
        DailyPolicy.Choice p = DailyPolicy.choose(random, true, 80, 20, "", "", "2026-10-13");
        if (p.kind.equals("image")) imageCount++;
        if (p.ai) aiCount++;
      }
      ok(
          Math.abs(imageCount / 100000.0 - .8) < .01 && Math.abs(aiCount / 100000.0 - .2) < .01,
          "configured 80% image / 20% overall AI distribution");
      boolean boundaries = true;
      for (int i = 0; i < 1000; i++) {
        DailyPolicy.Choice p = DailyPolicy.choose(random, true, 0, 100, "", "", "2026-10-13");
        boundaries &= p.kind.equals("video") && !p.ai;
        p = DailyPolicy.choose(random, true, 100, 100, "", "", "2026-10-13");
        boundaries &= p.kind.equals("image") && p.ai;
        p = DailyPolicy.choose(random, false, 100, 100, "", "", "2026-10-13");
        boundaries &= !p.ai;
      }
      ok(boundaries, "0/100 bounds and AI disable");
      ok(
          DailyPolicy.choose(random, true, 100, 100, "image", "2026-10-10", "2026-10-13")
              .kind
              .equals("video"),
          "three-day cap takes priority over 100% image");
      Store.prefs(c).edit().putInt("imagePercent", 20).putInt("aiPercent", 80).commit();
      ok(
          AIConfig.aiPercent(c) == 20 && AIConfig.step(76) == 80,
          "AI probability capped to image probability; ten-percent steps");
      ok(
          AIConfig.validateBase(" https://example.org/custom/v1/ ")
              .equals("https://example.org/custom/v1"),
          "custom compatible Base URL normalized without forcing official host");
      boolean reject = true;
      for (String url :
          new String[] {
            "http://example.org/v1",
            "https://user:pass@example.org/v1",
            "https://example.org/v1?k=secret",
            "https://example.org/v1/chat/completions"
          }) {
        try {
          AIConfig.validateBase(url);
          reject = false;
        } catch (Exception expected) {
        }
      }
      ok(reject, "invalid/credential-bearing API endpoints rejected");
      boolean controlsRejected = false;
      try {
        SecretVault.save(c, AIConfig.COMPAT, "test-first\r\nsecond\nthird");
      } catch (Exception expected) {
        controlsRejected = true;
      }
      ok(
          controlsRejected && !SecretVault.configured(c, AIConfig.COMPAT),
          "multiline credential rejected before persistence");
      String a = "test-ali-" + UUID.randomUUID(), b = "test-compatible-" + UUID.randomUUID();
      SecretVault.save(c, AIConfig.ALI, a);
      SecretVault.save(c, AIConfig.COMPAT, b);
      ok(
          SecretVault.read(c, AIConfig.ALI).equals(a)
              && SecretVault.read(c, AIConfig.COMPAT).equals(b),
          "provider credentials independent and decryptable");
      ok(
          !SecretVault.vault(c, AIConfig.ALI).getAll().toString().contains(a)
              && !SecretVault.vault(c, AIConfig.COMPAT).getAll().toString().contains(b),
          "provider credentials encrypted at rest");
      AIConfig.save(
          c,
          AIConfig.COMPAT,
          "https://example.org/gateway/v1",
          "custom/topic-model",
          "custom-image");
      JSONObject request = Bailian.draftRequest(c, Store.today(), "水彩");
      ok(
          request.getString("model").equals("custom/topic-model")
              && !request.has("enable_search")
              && !request.has("reasoning_effort"),
          "compatible topic request uses entered model without Alibaba-only parameters");
      ok(
          request
              .getJSONArray("messages")
              .getJSONObject(1)
              .getString("content")
              .contains("evergreen"),
          "non-search API does not claim current trends");
      JSONObject draft =
          new JSONObject()
              .put("prompt", "日光照亮远处层叠山脉与空中城堡，右侧人物穿着完整的斗篷站在石桥边，画面用沉稳蓝灰配少量金色，光影清楚。")
              .put("topicKey", "天空城")
              .put("date", Store.today());
      JSONObject fresh =
          Bailian.freshGeneration(
              new JSONObject(draft.toString())
                  .put("mediaId", "old-image")
                  .put("imageFile", "old.jpg")
                  .put("sha256", "old-hash"));
      ok(
          !fresh.has("mediaId")
              && !fresh.has("imageFile")
              && !fresh.has("sha256")
              && fresh.has("prompt"),
          "regenerating edited history creates a new asset without replacing original favorite");
      JSONObject imgRequest =
          CompatibleAI.imageRequest(AIConfig.imageModel(c, AIConfig.COMPAT), draft);
      ok(
          imgRequest.getString("model").equals("custom-image")
              && imgRequest.getInt("n") == 1
              && imgRequest.getString("prompt").contains(Bailian.COMPOSITION),
          "compatible image request uses configured model and camera composition");
      JSONObject decoded = new JSONObject();
      CompatibleAI.imageResult(
          c,
          new JSONObject()
              .put(
                  "data",
                  new JSONArray()
                      .put(
                          new JSONObject()
                              .put(
                                  "b64_json",
                                  android.util.Base64.encodeToString(
                                      java.nio.file.Files.readAllBytes(image.toPath()),
                                      android.util.Base64.NO_WRAP)))),
          decoded);
      ok(
          CompatibleAI.pendingFile(c, decoded.getString("localMedia")).isFile()
              && !decoded.has("b64_json"),
          "base64 generation persisted as private file, not history JSON");
      decoded.put("kind", "image").put("source", AIConfig.COMPAT).put("ai", true);
      DailyEngine.apply(c, decoded, false);
      ok(
          Store.state(c).optInt("mode") == 1
              && !new File(c.getFilesDir(), decoded.getString("localMedia")).exists(),
          "base64 media publishes successfully and pending file removed");
      JSONObject url = new JSONObject();
      CompatibleAI.imageResult(
          c,
          new JSONObject()
              .put(
                  "data",
                  new JSONArray()
                      .put(new JSONObject().put("url", "https://example.org/image.png"))),
          url);
      ok(
          url.optString("mediaUrl").equals("https://example.org/image.png"),
          "compatible URL response supported");
      boolean bad = false;
      try {
        CompatibleAI.imageResult(
            c, new JSONObject().put("data", new JSONArray()), new JSONObject());
      } catch (Exception e) {
        bad = true;
      }
      ok(bad, "empty image response rejected");
      SecretVault.clear(c, AIConfig.COMPAT);
      ok(
          SecretVault.configured(c, AIConfig.ALI) && !SecretVault.configured(c, AIConfig.COMPAT),
          "removing compatible key preserves Alibaba key");

      Store.publish(c, image, null, "收藏图片", "local", "", event("收藏图片", "image"));
      JSONObject favorite = Library.current(c);
      String first = Library.id(favorite);
      Library.toggle(c, favorite);
      ok(Library.favorite(c, favorite), "favorite toggled on");
      Store.publish(c, null, video, "收藏视频", "local", "", event("收藏视频", "video"));
      JSONObject movie = Library.current(c);
      testVideos.add(movie.getString("videoUri"));
      Library.toggle(c, movie);
      Bitmap thumb = Library.thumbnail(c, movie);
      ok(thumb != null && thumb.getWidth() > 0, "archived video produces actual frame thumbnail");
      if (thumb != null) thumb.recycle();
      for (int i = 0; i < 33; i++)
        Store.publish(c, image, null, "历史-" + i, "local", "", event("历史-" + i, "image"));
      JSONArray h = Bailian.history(c);
      boolean inHistory = false;
      for (int i = 0; i < h.length(); i++)
        if (first.equals(Library.id(h.getJSONObject(i)))) inHistory = true;
      ok(h.length() == 30 && !inHistory, "history limited to thirty after rollover");
      ok(
          Library.favorites(c).length() == 2
              && Library.available(c, favorite)
              && Library.available(c, movie),
          "favorites retain image and video beyond history limit");
      String oldVersion = Store.state(c).getString("version");
      Library.reuse(c, favorite);
      JSONObject applied = Store.state(c);
      ok(
          applied.getString("imageFile").equals(favorite.getString("imageFile"))
              && !oldVersion.equals(applied.getString("version")),
          "offline image apply reuses exact cached file and refreshes MAML version");
      Library.reuse(c, movie);
      applied = Store.state(c);
      ok(
          applied.getString("videoUri").equals(movie.getString("videoUri"))
              && applied.getInt("mode") == 2,
          "offline video apply reuses exact MediaStore file");
      ok(
          Store.today().equals(applied.optString("dailyDay")),
          "manual library selection protected from immediate automatic replacement");
      Library.reuse(c, favorite);
      Library.reuse(c, favorite);
      int same = 0;
      h = Bailian.history(c);
      for (int i = 0; i < h.length(); i++) if (first.equals(Library.id(h.getJSONObject(i)))) same++;
      ok(
          same == 1 && Library.favorites(c).length() == 2,
          "repeated apply deduplicates history without duplicating favorites");
      Library.toggle(c, favorite);
      ok(
          !Library.favorite(c, favorite) && Library.available(c, favorite),
          "unfavorite preserves media still in active/history use");
      JSONObject stateBefore = Store.state(c),
          missing = event("丢失", "image").put("imageFile", "0-00000000.jpg");
      bad = false;
      try {
        Library.reuse(c, missing);
      } catch (Exception expected) {
        bad = true;
      }
      ok(
          bad && stateBefore.toString().equals(Store.state(c).toString()),
          "missing cached media leaves active state unchanged");
      JSONObject release = Library.current(c);
      for (int i = 0; i < 34; i++)
        Store.publish(c, image, null, "清理-" + i, "local", "", event("清理-" + i, "image"));
      ok(
          !Library.available(c, release),
          "unreferenced image reclaimed after leaving history and playback");
      Store.publish(c, null, video, "新视频", "local", "", event("新视频", "video"));
      testVideos.add(Store.state(c).getString("videoUri"));
      Library.toggle(c, movie);
      for (int i = 0; i < 34; i++)
        Store.publish(c, image, null, "视频清理-" + i, "local", "", event("视频清理-" + i, "image"));
      ok(
          !Library.available(c, movie),
          "unreferenced MediaStore video reclaimed; current video retained");
      return report.toString();
    } finally {
      for (String uri : testVideos)
        try {
          real.getContentResolver().delete(Uri.parse(uri), null, null);
        } catch (Exception ignored) {
        }
      SecretVault.vault(c, AIConfig.ALI).edit().clear().commit();
      SecretVault.vault(c, AIConfig.COMPAT).edit().clear().commit();
      Store.prefs(c).edit().clear().commit();
      remove(sandbox, sandbox.getCanonicalPath());
    }
  }

  static void remove(File f, String root) throws Exception {
    if (!f.getCanonicalPath().equals(root)
        && !f.getCanonicalPath().startsWith(root + File.separator))
      throw new IOException("test cleanup outside sandbox");
    File[] children = f.listFiles();
    if (children != null) for (File child : children) remove(child, root);
    f.delete();
  }
}
