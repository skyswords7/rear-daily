package com.reardaily.app;

import android.content.Context;
import android.net.Uri;
import java.net.URLEncoder;

final class MediaSearch {
  static final String PREF = "mediaKeyword";

  static String keyword(Context c) {
    return Store.prefs(c).getString(PREF, "").trim();
  }

  static String clean(String text) {
    String value = text.trim();
    if (value.length() > 100) throw new IllegalArgumentException("搜索关键词最多 100 字");
    return value;
  }

  static void save(Context c, String value) {
    if (!Store.prefs(c).edit().putString(PREF, clean(value)).commit())
      throw new IllegalStateException("关键词保存失败");
  }

  static String encode(String text) {
    try {
      return URLEncoder.encode(text, "UTF-8");
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  static String wallhaven(String keyword, boolean api, String range) {
    String base = "https://wallhaven.cc/" + (api ? "api/v1/search" : "search");
    if (keyword.trim().isEmpty())
      return base
          + "?categories=010&purity=100&sorting=toplist&topRange="
          + range
          + "&atleast=1280x720";
    return base
        + "?categories=010&purity=100&sorting=relevance&atleast=1280x720&q="
        + encode(keyword.trim());
  }

  static String motion(String keyword) {
    return keyword.trim().isEmpty()
        ? "https://motionbgs.com/tag:anime/"
        : "https://motionbgs.com/search?q=" + encode(keyword.trim());
  }

  static String browse(boolean video, String keyword) {
    return video ? motion(keyword) : wallhaven(keyword, false, "1d");
  }

  static boolean allowed(String url, boolean video) {
    if (url == null) return false;
    try {
      Uri u = Uri.parse(url);
      return "https".equals(u.getScheme())
          && u.getUserInfo() == null
          && (u.getPort() == -1 || u.getPort() == 443)
          && (video ? "motionbgs.com" : "wallhaven.cc").equalsIgnoreCase(u.getHost());
    } catch (Exception e) {
      return false;
    }
  }

  static String selected(String url, boolean video) {
    if (!allowed(url, video)) return "";
    String p = Uri.parse(url).getPath();
    if (p == null) return "";
    if (!video)
      return p.matches("/w/[a-z0-9]{6}/?") ? "https://wallhaven.cc" + p.replaceAll("/$", "") : "";
    if (!p.matches("/[a-z0-9]+(?:-[a-z0-9]+)*/?")) return "";
    String slug = p.replaceAll("^/|/$", "");
    if (java.util.Arrays.asList(
            "search", "mobile", "gifs", "4k", "login", "register", "contact", "latest")
        .contains(slug)) return "";
    return "https://motionbgs.com/" + slug;
  }
}
