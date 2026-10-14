package com.reardaily.app;

import android.content.Context;
import android.text.Html;
import java.net.URLDecoder;
import java.text.Normalizer;
import java.util.*;
import org.json.*;

/** Local metadata matching only. Never uploads images or video frames. */
final class KeywordFilter {
  static final String PREF = "excludedKeywords";
  static final String DEFAULTS =
      "nsfw, nude, nudity, naked, topless, hentai, ecchi, erotic, porn, sexual, sexy, lewd,"
          + " suggestive\n"
          + "lingerie, underwear, bikini, bikinis, swimsuit, swimwear, cleavage, sideboob,"
          + " underboob, breasts, panties, thong, bra, bondage, fetish, see through\n"
          + "horror, gore, gory, blood, bloody, zombie, zombies, corpse, skull, skeleton, creepy,"
          + " scary, nightmare, dismemberment, decapitation\n"
          + "裸露, 裸体, 裸體, 裸胸, 色情, 色气, 色氣, 情色, 成人向, 内衣, 內衣, 泳装, 泳裝, 比基尼, 透视装, 透視裝, 露乳, 露臀, 走光, 低胸,"
          + " 情趣\n"
          + "恐怖, 惊悚, 驚悚, 血腥, 鲜血, 鮮血, 尸体, 屍體, 僵尸, 殭屍, 丧尸, 喪屍, 骷髅, 骷髏, 肢解, 断头, 斷頭";

  static String configured(Context c) {
    return Store.prefs(c).getString(PREF, DEFAULTS);
  }

  static List<String> words(String raw) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (String word : raw.split("[,，;；\\r\\n]+")) {
      String clean = word.trim();
      if (!normalise(clean).isEmpty()) out.add(clean);
    }
    return new ArrayList<>(out);
  }

  static String validate(String raw) {
    if (raw.length() > 12000) throw new IllegalArgumentException("排除词总长度不能超过 12000 字");
    List<String> words = words(raw);
    if (words.size() > 200) throw new IllegalArgumentException("最多保存 200 个排除词");
    for (String word : words)
      if (word.length() > 64) throw new IllegalArgumentException("每个排除词不能超过 64 字");
    return String.join("\n", words);
  }

  static void save(Context c, String raw) {
    String value = validate(raw);
    if (!Store.prefs(c).edit().putString(PREF, value).commit())
      throw new IllegalStateException("排除词保存失败");
  }

  static String plain(String value) {
    return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString();
  }

  static String normalise(String value) {
    try {
      value = URLDecoder.decode(value, "UTF-8");
    } catch (Exception ignored) {
    }
    return Normalizer.normalize(plain(value), Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}]+", " ")
        .trim();
  }

  static String match(String raw, String text) {
    String haystack = normalise(text);
    for (String word : words(raw)) {
      String needle = normalise(word);
      if (needle.isEmpty()) continue;
      // Latin terms match whole words/phrases (bra does not match library); CJK uses substring.
      boolean latin = needle.matches("[a-z0-9 ]+");
      if (latin ? (" " + haystack + " ").contains(" " + needle + " ") : haystack.contains(needle))
        return word;
    }
    return "";
  }

  static String text(JSONObject media) {
    return media.optString("title")
        + "\n"
        + media.optString("description")
        + "\n"
        + media.optString("tags")
        + "\n"
        + media.optString("sourceUrl")
        + "\n"
        + media.optString("mediaUrl");
  }

  static String match(Context c, JSONObject media) {
    return match(configured(c), text(media));
  }

  static void requireAllowed(Context c, JSONObject media) throws java.io.IOException {
    String matched = match(c, media);
    if (!matched.isEmpty()) throw new java.io.IOException("已跳过命中排除词的素材：" + matched);
  }
}
