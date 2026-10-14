package com.reardaily.app;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.json.*;

public class DailyInstrumentation extends Instrumentation {
  Bundle args;
  StringBuilder log = new StringBuilder();

  void ok(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
    log.append("PASS ").append(label).append('\n');
  }

  public void onCreate(Bundle b) {
    args = b;
    start();
  }

  public void onStart() {
    Context c = getTargetContext();
    Bundle result = new Bundle();
    int code = Activity.RESULT_OK;
    try {
      Store.ensure(c);
      String action = args.getString("action", "unit");
      if (action.equals("shizuku")) {
        log.append(ShizukuTests.run(c));
      } else if (action.equals("unit")) unit(c);
      else if(action.equals("ui")) log.append(UiTests.run(this));
      else if(action.equals("setup")) log.append(SetupTests.run(c));
      else if(action.equals("v05")) log.append(V05Tests.run(c));
      else if(action.equals("v05-inspect")) {
        JSONObject current=Library.current(c);
        ok(SecretVault.configured(c,AIConfig.ALI),"existing Alibaba key remains configured");
        ok(Library.available(c,current),"current media archived and available");
        log.append("HISTORY ").append(Bailian.history(c).length()).append("\n");
        log.append("FAVORITES ").append(Library.favorites(c).length()).append("\n");
        for(int j=0;j<Bailian.history(c).length();j++){
          JSONObject item=Bailian.history(c).getJSONObject(j);
          log.append(item.optString("kind")).append(" ").append(Library.available(c,item)?"cached":"expired").append(" ").append(item.optString("title")).append("\n");
        }
      }
      else if (action.equals("configure")) {
        File f = new File(c.getFilesDir(), "bootstrap-key");
        try {
          String key =
              new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)
                  .trim();
          SecretVault.save(c, key);
          ok(
              SecretVault.configured(c) && SecretVault.read(c).equals(key),
              "key encrypted and readable");
          ok(
              !c.getSharedPreferences("vault", 0).getAll().toString().contains(key),
              "no plaintext key in preferences");
        } finally {
          f.delete();
        }
        Store.prefs(c)
            .edit()
            .putString("source", "daily")
            .putBoolean("auto", false)
            .putBoolean("aiEnabled", true)
            .commit();
        ok(!f.exists(), "bootstrap credential deleted");
      } else if (action.equals("draft")) {
        JSONObject d = Bailian.generateDraft(c);
        ok(d.getString("prompt").contains(d.getString("topicKey")), "prompt contains chosen topic");
        ok(d.getString("prompt").contains(Store.today()), "prompt contains current date");
        ok(!d.getString("hotspot").isEmpty(), "hotspot returned");
        log.append("TOPIC ").append(d.getString("topicKey")).append('\n');
        log.append("EVIDENCE ").append(d.optString("evidence")).append('\n');
      } else if (action.equals("image")) {
        JSONObject d = Bailian.draft(c);
        ok(!d.optString("prompt").isEmpty(), "saved prompt loaded");
        JSONObject image = Bailian.generateImage(c, d, true);
        DailyEngine.apply(c, image, true);
        Store.prefs(c).edit().remove("pendingAi").commit();
        ok(Store.state(c).optInt("mode") == 1, "AI image published and selected");
        ok(
            Store.state(c)
                .getJSONObject("currentMetadata")
                .getString("imageModel")
                .equals("qwen-image-3.0"),
            "exact requested image model used");
      } else if (action.equals("popular-image") || action.equals("popular-video")) {
        boolean video = action.endsWith("video");
        JSONObject m =
            video
                ? PopularSource.video(c, new HashSet<String>())
                : PopularSource.image(c, new HashSet<String>());
        DailyEngine.apply(c, m, true);
        ok(
            Store.state(c).optInt("mode") == (video ? 2 : 1),
            "online media published with correct mode");
        log.append("SOURCE ").append(m.optString("source")).append('\n');
        log.append("TITLE ").append(m.optString("title")).append('\n');
      } else if (action.equals("v04-keyword")) {
        MediaSearch.save(c, args.getString("keyword", ""));
        ok(true, "test search keyword configured");
      } else if (action.equals("v04-search")) {
        boolean had = Store.prefs(c).contains(MediaSearch.PREF);
        String old = MediaSearch.keyword(c);
        try {
          MediaSearch.save(c, "gojo");
          JSONObject v = PopularSource.video(c, new HashSet<String>());
          ok(
              v.optString("searchKeyword").equals("gojo")
                  && v.optString("rankingWindow").equals("search"),
              "real MotionBGS keyword search returns a candidate");
          JSONObject i = PopularSource.image(c, new HashSet<String>());
          ok(
              i.optString("searchKeyword").equals("gojo"),
              "real image keyword search retains requested topic");
          log.append("VIDEO ").append(v.optString("title")).append('\n');
          log.append("IMAGE ").append(i.optString("sourceId")).append('\n');
        } finally {
          if (had) MediaSearch.save(c, old);
          else Store.prefs(c).edit().remove(MediaSearch.PREF).commit();
        }
      } else if (action.equals("camera")) {
        String filename = args.getString("file");
        File original = Store.imageFile(c, filename);
        JSONArray h = Bailian.history(c);
        JSONObject meta = null;
        for (int j = 0; j < h.length(); j++)
          if (h.getJSONObject(j).optBoolean("ai")) {
            meta = h.getJSONObject(j);
            break;
          }
        if (meta == null) throw new Exception("No AI history");
        meta.put("cameraLayout", "right-fit-v1");
        Store.publish(c, original, null, meta.optString("title"), "bailian", "camera-fit", meta);
        ok(
            Store.state(c).optInt("mode") == 1,
            "AI artwork reframed into right safe area without another API call");
      } else if (action.equals("restore-full-image")) {
        File f = new File(c.getCacheDir(), "restore-ai.jpg");
        JSONObject meta = null;
        JSONArray h = Bailian.history(c);
        for (int j = 0; j < h.length(); j++)
          if (h.getJSONObject(j).optBoolean("ai")) {
            meta = new JSONObject(h.getJSONObject(j).toString());
            break;
          }
        if (meta == null) throw new Exception("Missing AI metadata");
        meta.remove("cameraLayout");
        meta.remove("llmResponse");
        Store.publish(c, f, null, meta.optString("title"), "bailian", "full-screen", meta);
        f.delete();
        Store.selectMode(c, 2);
        JSONObject draft = Bailian.draft(c);
        draft.put("prompt", Bailian.withContext(draft, draft.getString("prompt")));
        Bailian.saveDraft(c, draft);
        ok(true, "full-screen image cache restored and camera prompt saved");
      } else if (action.equals("icon")) {
        android.graphics.drawable.Drawable icon =
            c.getApplicationInfo().loadIcon(c.getPackageManager());
        android.graphics.Bitmap bitmap =
            android.graphics.Bitmap.createBitmap(
                432, 432, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        icon.setBounds(0, 0, 432, 432);
        icon.draw(canvas);
        try (FileOutputStream output =
            new FileOutputStream(new File(c.getFilesDir(), "icon.png"))) {
          bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        }
        ok(true, "launcher icon renders");
      } else if (action.equals("activate")) {
        Store.prefs(c)
            .edit()
            .putString("source", "daily")
            .putBoolean("auto", true)
            .putBoolean("aiEnabled", true)
            .putString("status", "每日随机更新已开启")
            .commit();
        DailyJob.schedule(c);
        ok(!DailyEngine.due(c), "today already cached, no duplicate automatic call");
      }
      File report = new File(c.getFilesDir(), "v03-" + action + ".json");
      JSONObject evidence =
          new JSONObject()
              .put("action", action)
              .put("at", System.currentTimeMillis())
              .put("results", log.toString())
              .put("state", Store.state(c));
      if (action.equals("draft")) evidence.put("draft", Bailian.draft(c));
      java.nio.file.Files.write(
          report.toPath(), evidence.toString(2).getBytes(StandardCharsets.UTF_8));
    } catch (Throwable e) {
      code = Activity.RESULT_CANCELED;
      log.append("FAIL ").append(Net.safeError(e)).append('\n');
    }
    result.putString("stream", log.toString());
    finish(code, result);
  }

  void unit(Context c) throws Exception {
    Random r = new Random(521);
    int images = 0, ais = 0;
    for (int i = 0; i < 200000; i++) {
      DailyPolicy.Choice p = DailyPolicy.choose(r, true, "", "", "2026-10-12");
      if (p.kind.equals("image")) images++;
      if (p.ai) ais++;
    }
    ok(Math.abs(images / 200000.0 - .5) < .005, "200000 samples: image/video 50 percent");
    ok(Math.abs(ais / 200000.0 - .3) < .005, "200000 samples: AI 30 percent overall");
    for (int i = 0; i < 1000; i++)
      ok(!DailyPolicy.choose(r, false, "", "", "2026-10-12").ai, "AI disabled");
    // Collapse repetitive assertions in the report.
    int pos = log.indexOf("PASS AI disabled");
    log.setLength(pos);
    log.append("PASS AI disabled in 1000 samples\n");
    ok(
        DailyPolicy.choose(r, true, "image", "2026-10-09", "2026-10-12").kind.equals("video"),
        "fourth image day switches to video");
    ok(
        DailyPolicy.choose(r, true, "video", "2026-10-09", "2026-10-12").kind.equals("image"),
        "fourth video day switches to image");
    String kind = "", since = "";
    LocalDate day = LocalDate.of(2020, 1, 1);
    int streak = 0, max = 0;
    for (int i = 0; i < 20000; i++) {
      String d = day.plusDays(i).toString();
      DailyPolicy.Choice p = DailyPolicy.choose(r, true, kind, since, d);
      if (!kind.equals(p.kind)) {
        since = d;
        streak = 0;
      }
      kind = p.kind;
      max = Math.max(max, ++streak);
    }
    ok(max == 3, "20000 simulated days never exceed three-day streak");
    ok(DailyPolicy.repeated("原神！", Arrays.asList("原神")), "normalised topic deduplication");
    ok(
        DailyPolicy.repeated("Frieren moonlight", Arrays.asList("Frieren")),
        "topic-family duplicate rejection");
    ok(!DailyPolicy.repeated("星际机甲", Arrays.asList("芙莉莲")), "distinct topic accepted");
    JSONObject d =
        new JSONObject()
            .put("date", Store.today())
            .put("topicKey", "测试机甲")
            .put("hotspot", "轨道机甲视觉设计")
            .put("prompt", "以巨大轨道机甲为主体，侧向阳光照亮拉丝金属结构，远处行星形成尺度对比。构图清楚，银灰与少量深红，背景简洁。");
    JSONObject request = Bailian.imageRequest(d);
    ok(request.getString("model").equals("qwen-image-3.0"), "image model not silently substituted");
    ok(request.getJSONObject("parameters").getInt("n") == 1, "one image per paid request");
    ok(
        request.getJSONObject("parameters").getString("size").equals("1472*896"),
        "DashScope size uses star format");
    ok(
        Bailian.withContext(d, d.getString("prompt")).contains(Store.today()),
        "edited prompt retains date and topic");
    String response =
        "{\"output\":{\"choices\":[{\"message\":{\"content\":[{\"text\":\"ok\"},{\"image\":\"https://example.org/a.png\"}]}}]}}";
    ok(
        Bailian.imageUrl(new JSONObject(response)).equals("https://example.org/a.png"),
        "image parser finds image after text");
    boolean rejected = false;
    try {
      Bailian.imageUrl(new JSONObject("{\"output\":{}}"));
    } catch (Exception expected) {
      rejected = true;
    }
    ok(rejected, "missing image response rejected");
    String html =
        "<meta content=https://motionbgs.com/media/1/a.mp4 property=og:video><a title='A'"
            + " href=/anime-a> <figure>";
    ok(PopularSource.og(html, "og:video").endsWith(".mp4"), "unquoted OpenGraph attributes parsed");
    ok(PopularSource.pages(html).equals(Arrays.asList("/anime-a")), "video candidate links parsed");

    ok(
        KeywordFilter.match(KeywordFilter.DEFAULTS, "Anime girl in BIKINI").equals("bikini"),
        "default revealing term excluded case-insensitively");
    ok(
        !KeywordFilter.match(KeywordFilter.DEFAULTS, "恐怖游戏 / 血腥场景").isEmpty(),
        "Chinese horror metadata excluded");
    ok(
        KeywordFilter.match("bra", "Library mecha illustration").isEmpty(),
        "English word boundaries avoid library false positive");
    ok(
        KeywordFilter.match("body horror", "body-horror wallpaper").equals("body horror"),
        "multiword term matches URL punctuation");
    ok(KeywordFilter.match("nsfw", "ＮＳＦＷ anime").equals("nsfw"), "Unicode width normalized");
    ok(
        KeywordFilter.match("swimsuit", "https://example.org/Swim%73uit-art").equals("swimsuit"),
        "URL-encoded terms screened");
    ok(
        KeywordFilter.match("bikini", "bik&#105;ni").equals("bikini"),
        "HTML-encoded metadata screened");
    ok(
        KeywordFilter.match("", "Any horror image").isEmpty(),
        "empty custom list disables keyword exclusions");
    ok(
        KeywordFilter.validate("露臀，NSFW;血腥\nNSFW").equals("露臀\nNSFW\n血腥"),
        "editable comma/newline list deduplicated");
    boolean had = Store.prefs(c).contains(KeywordFilter.PREF);
    String before = KeywordFilter.configured(c);
    try {
      KeywordFilter.save(c, "云朵；cloud");
      ok(KeywordFilter.configured(c).equals("云朵\ncloud"), "custom keyword list persisted");
      JSONObject candidate =
          new JSONObject()
              .put("source", "motionbgs")
              .put("title", "Cloud castle")
              .put("kind", "video")
              .put("mediaUrl", "not-a-url");
      String stateBefore = Store.state(c).toString();
      boolean blocked = false;
      try {
        DailyEngine.apply(c, candidate, true);
      } catch (IOException ex) {
        blocked = ex.getMessage().contains("排除词");
      }
      ok(
          blocked && stateBefore.equals(Store.state(c).toString()),
          "blocked metadata rejected before download, cache untouched");
      KeywordFilter.save(c, "castle");
      ok(KeywordFilter.match(c, candidate).equals("castle"), "changed words used immediately");
    } finally {
      if (had) Store.prefs(c).edit().putString(KeywordFilter.PREF, before).commit();
      else Store.prefs(c).edit().remove(KeywordFilter.PREF).commit();
    }
    String tagsHtml =
        "<nav><a href=/tag:horror/>Horror</a></nav><ul class='subtags mb-8'><li><a"
            + " href=/tag:mecha/><span>Mecha</span></a></li></ul>";
    ok(
        PopularSource.ownTags(tagsHtml).contains("Mecha")
            && !PopularSource.ownTags(tagsHtml).contains("Horror"),
        "only artwork tags screened, unrelated site menus ignored");
    String ownHorror = "<ul class=subtags><li><a href=/tag:horror/>Horror</a></li></ul>";
    ok(
        !KeywordFilter.match(KeywordFilter.DEFAULTS, PopularSource.ownTags(ownHorror)).isEmpty(),
        "artwork horror tag excluded");
    JSONObject wall =
        new JSONObject()
            .put("id", "test")
            .put("url", "https://wallhaven.cc/w/test")
            .put("path", "https://w.wallhaven.cc/test.jpg")
            .put("purity", "sfw")
            .put("tags", new JSONArray().put(new JSONObject().put("name", "lingerie")));
    ok(
        !KeywordFilter.match(c, PopularSource.wallhavenMedia(wall, "1d")).isEmpty(),
        "SFW source label does not bypass tagged exclusions");

    ok(
        !MediaSearch.wallhaven("  ", true, "1d").contains("q="),
        "empty keyword uses image recommendations without search");
    ok(
        MediaSearch.motion("").equals("https://motionbgs.com/tag:anime/"),
        "empty video keyword uses category");
    String encoded = MediaSearch.wallhaven("原神 &purity=111", true, "1d");
    ok(
        android.net.Uri.parse(encoded).getQueryParameter("q").equals("原神 &purity=111")
            && android.net.Uri.parse(encoded).getQueryParameter("purity").equals("100"),
        "keyword encoded without overriding source filters");
    ok(
        MediaSearch.motion("gojo satoru").contains("q=gojo+satoru"),
        "video keyword uses source search endpoint");
    ok(
        MediaSearch.selected("https://wallhaven.cc/w/w53jo6?ref=source", false)
            .equals("https://wallhaven.cc/w/w53jo6"),
        "selected image URL canonicalized");
    ok(
        MediaSearch.selected("https://motionbgs.com/gojo-satoru-beyond-infinity/", true)
            .equals("https://motionbgs.com/gojo-satoru-beyond-infinity"),
        "selected video URL canonicalized");
    ok(
        MediaSearch.selected("https://motionbgs.com/search?q=gojo", true).isEmpty(),
        "search page cannot be applied as video");
    ok(
        MediaSearch.selected("https://motionbgs.com/tag:anime/", true).isEmpty(),
        "category page cannot be applied as video");
    ok(
        !MediaSearch.allowed("https://motionbgs.com.evil.example/a", true),
        "lookalike source host rejected");
    ok(
        !MediaSearch.allowed("https://user@motionbgs.com/a", true),
        "credential-bearing URL rejected");
    ok(!MediaSearch.allowed("file:///sdcard/local.html", true), "local URL rejected");
    ok(
        !MediaSearch.allowed("https://motionbgs.com:8443/a", true),
        "unexpected source port rejected");
    String originalKeyword = MediaSearch.keyword(c);
    boolean hadKeyword = Store.prefs(c).contains(MediaSearch.PREF);
    String originalTopic = Bailian.topicPrompt(c);
    boolean hadTopic = Store.prefs(c).contains("topicPrompt");
    try {
      MediaSearch.save(c, "  gojo  ");
      ok(MediaSearch.keyword(c).equals("gojo"), "search keyword persisted trimmed");
      MediaSearch.save(c, "");
      ok(MediaSearch.keyword(c).isEmpty(), "clearing keyword removes search restriction");
      Bailian.saveTopicPrompt(c, "只选择风景与建筑，优先空中花园，不要人像。");
      JSONObject custom = Bailian.draftRequest(c, Store.today(), "水彩");
      String sys = custom.getJSONArray("messages").getJSONObject(0).getString("content");
      ok(
          sys.startsWith("只选择风景与建筑")
              && sys.contains("candidates")
              && sys.contains(Bailian.COMPOSITION),
          "edited topic prompt reaches actual request with required format");
      ok(
          custom
              .getJSONArray("messages")
              .getJSONObject(1)
              .getString("content")
              .contains(Store.today()),
          "custom topic prompt still gets date context");
    } finally {
      android.content.SharedPreferences.Editor edit = Store.prefs(c).edit();
      if (hadKeyword) edit.putString(MediaSearch.PREF, originalKeyword);
      else edit.remove(MediaSearch.PREF);
      if (hadTopic) edit.putString("topicPrompt", originalTopic);
      else edit.remove("topicPrompt");
      edit.commit();
    }
    JSONArray forty = new JSONArray();
    for (int n = 0; n < 40; n++) forty.put(new JSONObject().put("title", "record-" + n));
    JSONArray limited = Store.limitHistory(forty);
    ok(
        limited.length() == 30
            && limited.getJSONObject(0).getString("title").equals("record-0")
            && limited.getJSONObject(29).getString("title").equals("record-29"),
        "history keeps newest 30 in order");
    synchronized (Store.LOCK) {
      JSONObject saved = Store.state(c);
      try {
        JSONObject old = new JSONObject(saved.toString());
        old.put("dailyHistory", forty);
        Store.write(c, old);
        Store.trimHistory(c);
        JSONObject migrated = Store.state(c);
        ok(
            migrated.getJSONArray("dailyHistory").length() == 30,
            "existing stored 40-record history migrates to 30");
        ok(
            migrated.optString("videoPath").equals(saved.optString("videoPath"))
                && migrated.optString("version").equals(saved.optString("version")),
            "history migration preserves active media and version");
      } finally {
        Store.write(c, saved);
      }
    }

    JSONObject previous = Store.state(c);
    File good = Store.asset(c, "welcome.jpg"),
        bad = File.createTempFile("bad-", ".mp4", c.getCacheDir());
    try {
      rejected = false;
      try {
        Store.publish(c, good, bad, "bad", "test", "", d.put("kind", "video"));
      } catch (Exception expected) {
        rejected = true;
      }
      ok(
          rejected && previous.toString().equals(Store.state(c).toString()),
          "invalid media rolls back history, mode and files");
    } finally {
      good.delete();
      bad.delete();
    }
    ok(
        !Net.safeError(new IOException("token sk-unit-only-test-secret")).contains("sk-unit"),
        "diagnostics redact credential patterns");
  }
}
