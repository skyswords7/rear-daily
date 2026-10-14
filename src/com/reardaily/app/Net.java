package com.reardaily.app;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

final class Net {
  static class ApiError extends IOException {
    final int status;
    final String code;

    ApiError(int s, String c) {
      super("API HTTP " + s + " / " + c);
      status = s;
      code = c;
    }
  }

  static String safeError(Throwable e) {
    if (e instanceof java.net.SocketTimeoutException) return "网络超时，保留原缓存";
    if (e instanceof java.net.UnknownHostException) return "网络不可达，保留原缓存";
    String s = e.getMessage();
    if (s == null) return e.getClass().getSimpleName();
    s = s.replaceAll("sk-[A-Za-z0-9_-]+", "[已隐藏]").replaceAll("https?://\\S+", "[链接]");
    return s.substring(0, Math.min(180, s.length()));
  }

  static String text(String url, int max) throws IOException {
    HttpURLConnection cn = Updater.connect(url);
    try (InputStream in = cn.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Store.copy(in, out, max);
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    } finally {
      cn.disconnect();
    }
  }

  static JSONObject postBailian(String path, String key, JSONObject body, int timeout)
      throws Exception {
    return post("https://dashscope.aliyuncs.com" + path, key, body, timeout, 2 * 1024 * 1024);
  }

  static JSONObject post(String endpoint, String key, JSONObject body, int timeout, int max)
      throws Exception {
    URL url = new URL(endpoint);
    if (!url.getProtocol().equals("https") || url.getUserInfo() != null)
      throw new IOException("API 地址无效");
    HttpURLConnection c = (HttpURLConnection) url.openConnection();
    c.setConnectTimeout(20000);
    c.setReadTimeout(timeout);
    c.setInstanceFollowRedirects(false);
    c.setRequestMethod("POST");
    c.setDoOutput(true);
    c.setRequestProperty("Authorization", "Bearer " + key);
    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
    c.setFixedLengthStreamingMode(bytes.length);
    try {
      try (OutputStream out = c.getOutputStream()) {
        out.write(bytes);
      }
      int status = c.getResponseCode();
      InputStream stream = status < 400 ? c.getInputStream() : c.getErrorStream();
      String response = "";
      if (stream != null)
        try (InputStream in = stream;
            ByteArrayOutputStream out = new ByteArrayOutputStream()) {
          Store.copy(in, out, status == 200 ? max : 256 * 1024);
          response = new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
      JSONObject j;
      try {
        j = new JSONObject(response);
      } catch (Exception ex) {
        throw new ApiError(status, "InvalidResponse");
      }
      if (status != 200 || j.has("code") || j.has("error")) {
        JSONObject err = j.optJSONObject("error");
        String code =
            err != null ? err.optString("code", "Unknown") : j.optString("code", "Unknown");
        throw new ApiError(status, code.replaceAll("[^A-Za-z0-9_.-]", ""));
      }
      return j;
    } finally {
      c.disconnect();
    }
  }
}
