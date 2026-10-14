package com.reardaily.app;

import android.content.Context;
import java.util.*;
import java.util.regex.*;
import org.json.*;

final class PopularSource {
  static final Random RANDOM = new java.security.SecureRandom();

  static Set<String> used(Context c) {
    Set<String> out = new HashSet<>();
    JSONArray h = Bailian.history(c);
    for (int i = 0; i < h.length(); i++) {
      JSONObject e = h.optJSONObject(i);
      if (e != null) out.add(e.optString("sourceId"));
    }
    return out;
  }

  static JSONObject image(Context c, Set<String> rejected) throws Exception {
    Set<String> used = used(c);
    used.addAll(rejected);
    String keyword = MediaSearch.keyword(c);
    for (String range : keyword.isEmpty() ? new String[] {"1d", "1w"} : new String[] {"search"}) {
      try {
        String query = MediaSearch.wallhaven(keyword, true, range);
        JSONArray data = new JSONObject(Net.text(query, 1024 * 1024)).getJSONArray("data");
        List<JSONObject> candidates = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
          JSONObject w = data.getJSONObject(i);
          if (!used.contains("wallhaven:" + w.optString("id")) && wallhavenUsable(w))
            candidates.add(w);
        }
        Collections.shuffle(candidates, RANDOM);
        int attempts = 0;
        for (JSONObject candidate : candidates) {
          if (++attempts > 8) break;
          String id = candidate.getString("id");
          used.add("wallhaven:" + id);
          try {
            // Search responses omit tags. Read the artwork's own tags before downloading.
            JSONObject w =
                new JSONObject(Net.text("https://wallhaven.cc/api/v1/w/" + id, 512 * 1024))
                    .getJSONObject("data");
            if (!wallhavenUsable(w) || w.optJSONArray("tags") == null) continue;
            JSONObject media = wallhavenMedia(w, range);
            if (!keyword.isEmpty())
              media.put("searchKeyword", keyword).put("hotspot", "Wallhaven 关键词搜索 · " + keyword);
            if (!KeywordFilter.match(c, media).isEmpty()) continue;
            return media.put("keywordChecked", true);
          } catch (Exception ignored) {
            /* Missing details are skipped, never treated as reviewed. */
          }
        }
      } catch (Exception ignored) {
        /* Try the week list and then the alternate source. */
      }
    }
    return motion(c, false, used);
  }

  static JSONObject selected(Context c, String url, boolean video) throws Exception {
    boolean motion = MediaSearch.allowed(url, true);
    if (video && !motion) throw new java.io.IOException("视频请选择 MotionBGS 作品");
    String canonical = MediaSearch.selected(url, motion);
    if (canonical.isEmpty()) throw new java.io.IOException("请先打开该站作品详情页");
    JSONObject media;
    if (motion) {
      String page = android.net.Uri.parse(canonical).getPath();
      media = motionMedia(page, Net.text(canonical, 1024 * 1024), video, "manual");
    } else {
      String id = android.net.Uri.parse(canonical).getLastPathSegment();
      JSONObject w =
          new JSONObject(Net.text("https://wallhaven.cc/api/v1/w/" + id, 512 * 1024))
              .getJSONObject("data");
      if (!w.optString("purity").equals("sfw")
          || w.optLong("file_size", Long.MAX_VALUE) >= 20 * 1024 * 1024
          || w.optJSONArray("tags") == null)
        throw new java.io.IOException("该图片不是 SFW、超过 20 MB 或缺少作品标签");
      media = wallhavenMedia(w, "manual");
    }
    KeywordFilter.requireAllowed(c, media);
    return media
        .put("keywordChecked", true)
        .put("selectedByUser", true)
        .put("hotspot", "原站手动选择")
        .put("rankingWindow", "manual");
  }

  static boolean wallhavenUsable(JSONObject w) {
    return w.optString("purity").equals("sfw")
        && w.optLong("file_size", Long.MAX_VALUE) < 20 * 1024 * 1024
        && w.optInt("dimension_x") >= w.optInt("dimension_y");
  }

  static JSONObject wallhavenMedia(JSONObject w, String range) throws Exception {
    JSONArray tags = new JSONArray(), raw = w.optJSONArray("tags");
    if (raw != null)
      for (int i = 0; i < raw.length(); i++) {
        JSONObject tag = raw.optJSONObject(i);
        if (tag != null) tags.put(tag.optString("name"));
      }
    return new JSONObject()
        .put("sourceId", "wallhaven:" + w.getString("id"))
        .put("source", "wallhaven")
        .put("sourceUrl", w.getString("url"))
        .put("mediaUrl", w.getString("path"))
        .put("kind", "image")
        .put("ai", false)
        .put("date", Store.today())
        .put("title", "动漫壁纸 · " + w.getString("id"))
        .put("tags", tags)
        .put("purity", w.optString("purity"))
        .put("hotspot", range.equals("1d") ? "Wallhaven 动漫日榜" : "Wallhaven 动漫周榜（当日日榜不足）")
        .put("rankingWindow", range);
  }

  static JSONObject video(Context c, Set<String> rejected) throws Exception {
    Set<String> used = used(c);
    used.addAll(rejected);
    return motion(c, true, used);
  }

  static String attr(String tag, String name) {
    Matcher m =
        Pattern.compile(
                "(?:^|\\s)"
                    + Pattern.quote(name)
                    + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
                Pattern.CASE_INSENSITIVE)
            .matcher(tag);
    if (!m.find()) return "";
    return m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
  }

  static String og(String html, String property) {
    Matcher m = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
    while (m.find()) {
      String tag = m.group();
      if (attr(tag, "property").equals(property)) return KeywordFilter.plain(attr(tag, "content"));
    }
    return "";
  }

  static List<String> pages(String html) {
    List<String> out = new ArrayList<>();
    Matcher m = Pattern.compile("<a\\b([^>]+)>\\s*<figure", Pattern.CASE_INSENSITIVE).matcher(html);
    while (m.find()) {
      String href = attr(m.group(1), "href");
      if (href.matches("/[a-z0-9-]+") && !out.contains(href)) out.add(href);
      if (out.size() >= 48) break;
    }
    return out;
  }

  static String ownTags(String html) {
    // Only the artwork's subtags; site-wide menus contain unrelated horror/other categories.
    Matcher lists =
        Pattern.compile("<ul\\b([^>]*)>(.*?)</ul>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
            .matcher(html);
    StringBuilder out = new StringBuilder();
    while (lists.find())
      if (Arrays.asList(attr(lists.group(1), "class").split("\\s+")).contains("subtags")) {
        Matcher links =
            Pattern.compile("<a\\b([^>]*)>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(lists.group(2));
        while (links.find()) {
          String href = attr(links.group(1), "href");
          if (href.startsWith("/tag:"))
            out.append(href.substring(5))
                .append(" ")
                .append(KeywordFilter.plain(links.group(2)))
                .append("\n");
        }
      }
    return out.toString().trim();
  }

  static JSONObject motionMedia(String page, String detail, boolean video, String category)
      throws Exception {
    String title =
        og(detail, "og:title").replace(" Live Wallpaper", "").replace(" live wallpaper", "");
    String media = og(detail, video ? "og:video" : "og:image");
    if (!media.startsWith("https://motionbgs.com/media/") || title.isEmpty())
      throw new java.io.IOException("素材页面缺少有效媒体或标题");
    return new JSONObject()
        .put("sourceId", "motionbgs:" + page)
        .put("source", "motionbgs")
        .put("sourceUrl", "https://motionbgs.com" + page)
        .put("mediaUrl", media)
        .put("kind", video ? "video" : "image")
        .put("ai", false)
        .put("title", title)
        .put("description", og(detail, "og:description"))
        .put("tags", ownTags(detail))
        .put("hotspot", "今日发现 · MotionBGS " + (category.equals("anime") ? "动漫" : "游戏") + "分类推荐")
        .put("date", Store.today())
        .put("rankingWindow", "category-featured");
  }

  static JSONObject motion(Context c, boolean video, Set<String> used) throws Exception {
    String first = RANDOM.nextBoolean() ? "anime" : "games";
    String keyword = MediaSearch.keyword(c);
    for (String category :
        keyword.isEmpty()
            ? new String[] {first, first.equals("anime") ? "games" : "anime"}
            : new String[] {"search"}) {
      try {
        String html =
            Net.text(
                keyword.isEmpty()
                    ? "https://motionbgs.com/tag:" + category + "/"
                    : MediaSearch.motion(keyword),
                2 * 1024 * 1024);
        List<String> pages = pages(html);
        Collections.shuffle(pages, RANDOM);
        int attempts = 0;
        for (String page : pages) {
          String id = "motionbgs:" + page;
          if (used.contains(id)
              || !KeywordFilter.match(KeywordFilter.configured(c), page).isEmpty()) continue;
          used.add(id);
          if (++attempts > 8) break;
          try {
            String detail = Net.text("https://motionbgs.com" + page, 1024 * 1024);
            JSONObject media = motionMedia(page, detail, video, category);
            if (!keyword.isEmpty())
              media
                  .put("searchKeyword", keyword)
                  .put("hotspot", "MotionBGS 关键词搜索 · " + keyword)
                  .put("rankingWindow", "search");
            if (!KeywordFilter.match(c, media).isEmpty()) continue;
            return media.put("keywordChecked", true);
          } catch (Exception ignored) {
          }
        }
      } catch (Exception ignored) {
      }
    }
    throw new java.io.IOException("没有找到符合关键词与排除词的可用素材，保留现有缓存");
  }
}
