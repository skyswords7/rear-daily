package com.reardaily.app;

import android.content.Context;
import android.util.Base64;
import java.io.*;
import java.util.UUID;
import org.json.*;

final class CompatibleAI {
  static JSONObject imageRequest(String model, JSONObject d) throws Exception {
    String prompt = Bailian.withContext(d, d.getString("prompt"));
    if (prompt.length() < 40 || prompt.length() > 12000)
      throw new IOException("提示词长度需在40至12000字之间");
    return new JSONObject()
        .put("model", AIConfig.model(model))
        .put("prompt", prompt)
        .put("n", 1)
        .put("size", "1536x1024");
  }

  static void imageResult(Context c, JSONObject response, JSONObject result) throws Exception {
    JSONArray data = response.optJSONArray("data");
    if (data == null || data.length() == 0) throw new IOException("接口未返回图片 data");
    JSONObject image = data.getJSONObject(0);
    String b64 = image.optString("b64_json");
    if (!b64.isEmpty()) {
      if (b64.length() > 28 * 1024 * 1024) throw new IOException("返回图片过大");
      byte[] bytes;
      try {
        bytes = Base64.decode(b64, Base64.DEFAULT);
      } catch (IllegalArgumentException e) {
        throw new IOException("图片 Base64 无效");
      }
      if (bytes.length == 0 || bytes.length > 20 * 1024 * 1024) throw new IOException("返回图片大小无效");
      File out = new File(c.getFilesDir(), "pending-ai-" + UUID.randomUUID() + ".img");
      try (FileOutputStream stream = new FileOutputStream(out)) {
        stream.write(bytes);
      }
      result.put("localMedia", out.getName());
    } else {
      String url = image.optString("url");
      if (!url.startsWith("https://")) throw new IOException("接口未返回有效图片 URL 或 Base64");
      result.put("mediaUrl", url);
    }
  }

  static File pendingFile(Context c, String name) throws Exception {
    if (!name.matches("pending-ai-[a-f0-9-]{36}\\.img")) throw new IOException("本地生成缓存名称无效");
    File f = new File(c.getFilesDir(), name);
    if (!f.isFile()) throw new IOException("生成缓存已丢失");
    return f;
  }
}
