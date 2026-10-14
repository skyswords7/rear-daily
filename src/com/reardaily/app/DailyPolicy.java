package com.reardaily.app;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

final class DailyPolicy {
  static final class Choice {
    final String kind;
    final boolean ai, forced;

    Choice(String k, boolean a, boolean f) {
      kind = k;
      ai = a;
      forced = f;
    }
  }

  // Symmetric image/video choice. 60% of image days use AI: 0.5 * 0.6 = 0.30 overall.
  static Choice choose(Random r, boolean aiEnabled, String previous, String since, String today) {
    return choose(r, aiEnabled, 50, 30, previous, since, today);
  }

  static Choice choose(
      Random r,
      boolean aiEnabled,
      int imagePercent,
      int aiPercent,
      String previous,
      String since,
      String today) {
    imagePercent = AIConfig.step(imagePercent);
    aiPercent = Math.min(imagePercent, AIConfig.step(aiPercent));
    boolean forced = false;
    try {
      forced =
          !previous.isEmpty()
              && ChronoUnit.DAYS.between(LocalDate.parse(since), LocalDate.parse(today)) >= 3;
    } catch (Exception ignored) {
    }
    String kind =
        forced
            ? (previous.equals("image") ? "video" : "image")
            : (r.nextInt(100) < imagePercent ? "image" : "video");
    return new Choice(
        kind,
        aiEnabled
            && kind.equals("image")
            && imagePercent > 0
            && r.nextDouble() < aiPercent / (double) imagePercent,
        forced);
  }

  static String normalise(String s) {
    return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}]", "");
  }

  static boolean repeated(String topic, Collection<String> previous) {
    String k = normalise(topic);
    if (k.isEmpty()) return true;
    for (String old : previous) {
      String o = normalise(old);
      if (!o.isEmpty()
          && (k.equals(o)
              || (Math.min(k.length(), o.length()) >= 4 && (k.contains(o) || o.contains(k)))))
        return true;
    }
    return false;
  }

  static final String[] COOL = {
    "赛璐璐动作分镜：清晰剪影、单一主光、刀锋般的运动构图，石墨灰配一点赤红",
    "机甲概念设计：拉丝装甲、机械结构、尺度对比，冷白天光与少量琥珀指示灯",
    "东方水墨幻想：大面积留白、飞白笔触、墨色山势，朱砂只用于视觉中心",
    "电影式环境叙事：层层空间与逆光轮廓，黄昏体积光、克制的冷暖关系",
    "黑白漫画版画：粗细线条和网点、对角构图，单处点色，不堆特效",
    "复古像素游戏场景：严整像素簇、纵深和光源层次，有限调色板",
    "精细游戏插画：风、布料和金属的物理质感，主体鲜明、背景简洁",
    "科幻建筑速写：巨大几何结构与小人物对比，银灰与深蓝、局部窗光"
  };
  static final String[] CUTE = {"治愈绘本：柔软纸张笔触、自然阳光、圆润小伙伴，奶油色与草绿", "精巧黏土微缩场景：可爱角色和可触摸材质，柔光、整洁配色"};

  static String style(Random r, String last) {
    String[] group = r.nextInt(10) < 8 ? COOL : CUTE;
    String s;
    do {
      s = group[r.nextInt(group.length)];
    } while (s.equals(last));
    return s;
  }
}
