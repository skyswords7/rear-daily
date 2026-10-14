package com.reardaily.app;

import android.content.*;
import java.io.*;
import java.net.URI;

final class AIConfig {
  static final String ALI = "bailian", COMPAT = "compatible";

  static String provider(Context c) {
    return Store.prefs(c).getString("aiProvider", ALI);
  }

  static String name(String p) {
    return COMPAT.equals(p) ? "OpenAI 兼容" : "阿里百炼";
  }

  static String textModel(Context c, String p) {
    return Store.prefs(c).getString("model.text." + p, ALI.equals(p) ? Bailian.TEXT_MODEL : "");
  }

  static String imageModel(Context c, String p) {
    return Store.prefs(c).getString("model.image." + p, ALI.equals(p) ? Bailian.IMAGE_MODEL : "");
  }

  static String base(Context c, String p) {
    return ALI.equals(p)
        ? "https://dashscope.aliyuncs.com"
        : Store.prefs(c).getString("base." + p, "");
  }

  static String validateBase(String value) throws Exception {
    value = value.trim();
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    URI u = new URI(value);
    if (!"https".equalsIgnoreCase(u.getScheme())
        || u.getHost() == null
        || u.getUserInfo() != null
        || u.getQuery() != null
        || u.getFragment() != null) throw new IOException("服务地址须为 HTTPS，不含账号、查询参数或片段；通常以 /v1 结尾");
    if (u.getPath().endsWith("/chat/completions") || u.getPath().endsWith("/images/generations"))
      throw new IOException("请填写 Base URL，而不是完整调用路径");
    return value;
  }

  static String model(String value) throws Exception {
    value = value.trim();
    if (value.isEmpty()
        || value.length() > 200
        || java.util.regex.Pattern.compile("[\\s\\p{Cntrl}]").matcher(value).find())
      throw new IOException("请填写有效模型名称");
    return value;
  }

  static void save(Context c, String p, String base, String text, String image) throws Exception {
    if (!ALI.equals(p) && !COMPAT.equals(p)) throw new IOException("未知提供商");
    text = model(text);
    image = model(image);
    if (COMPAT.equals(p)) base = validateBase(base);
    if (!Store.prefs(c)
        .edit()
        .putString("aiProvider", p)
        .putString("base." + p, base)
        .putString("model.text." + p, text)
        .putString("model.image." + p, image)
        .remove("dayPlan")
        .commit()) throw new IOException("配置保存失败");
  }

  static int imagePercent(Context c) {
    return step(Store.prefs(c).getInt("imagePercent", 50));
  }

  static int aiPercent(Context c) {
    return Math.min(imagePercent(c), step(Store.prefs(c).getInt("aiPercent", 30)));
  }

  static int step(int n) {
    return Math.max(0, Math.min(100, Math.round(n / 10f) * 10));
  }
}
