package com.reardaily.app;

import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Environment;
import dalvik.system.PathClassLoader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** One-time provisioning. No root, UI coordinates, or device-specific wallpaper IDs. */
final class RearSetup {
  static final String THEME = "com.android.thememanager";
  static final String REAR = "com.xiaomi.subscreencenter";
  static final String DETAIL = "com.rearScreen.RearScreenDetailActivity";
  static final int REVISION = 1;
  static final java.util.concurrent.ExecutorService EXEC =
      java.util.concurrent.Executors.newSingleThreadExecutor();

  static synchronized String id(Context c) {
    String id = Store.prefs(c).getString("setup.id", "");
    if (!id.matches("[a-f0-9]{32}")) {
      id = UUID.randomUUID().toString().replace("-", "");
      Store.prefs(c).edit().putString("setup.id", id).commit();
    }
    return id;
  }

  static File stage(Context c) throws IOException {
    File external = c.getExternalFilesDir(null);
    if (external == null) throw new IOException("外部存储尚未就绪，请解锁手机后重试");
    return new File(external, "rear-setup");
  }

  static File themeFiles() {
    return new File(Environment.getExternalStorageDirectory(), "Android/data/" + THEME + "/files");
  }

  static String target(Context c) {
    return new File(themeFiles(), "RearDaily/" + id(c)).getPath();
  }

  static String unsupported(Context c) {
    try {
      PackageManager pm = c.getPackageManager();
      ApplicationInfo info = pm.getApplicationInfo(THEME, 0);
      pm.getApplicationInfo(REAR, 0);
      if ((info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0)
        return "未检测到受支持的系统主题管理器";
      return "";
    } catch (Exception e) {
      return "未检测到兼容的小米背屏服务；普通手机无法使用此模板";
    }
  }

  static String automaticUnsupported(Context c) {
    String reason = unsupported(c);
    if (!reason.isEmpty()) return reason;
    try {
      ActivityInfo a = c.getPackageManager().getActivityInfo(new ComponentName(THEME, DETAIL), 0);
      if (a.exported && a.enabled) return "";
    } catch (Exception ignored) {}
    return "此系统未开放自动预览入口，请切换手动操作";
  }

  static String quote(String value) {
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }

  static String command(Context c, String mode) throws IOException {
    String command = "adb shell sh " + quote(new File(stage(c), "install.sh").getPath());
    if (!mode.isEmpty()) command += " " + mode;
    return command;
  }

  static byte[] asset(Context c, String name) throws IOException {
    try (InputStream in = c.getAssets().open("setup/" + name);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Store.copy(in, out, 1024 * 1024);
      return out.toByteArray();
    }
  }

  static void write(File f, byte[] bytes) throws IOException {
    try (FileOutputStream out = new FileOutputStream(f)) {
      out.write(bytes);
      out.getFD().sync();
    }
  }

  static String hash(byte[] bytes) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
    StringBuilder text = new StringBuilder();
    for (byte b : digest) text.append(String.format(Locale.ROOT, "%02x", b & 255));
    return text.toString();
  }

  static byte[] archive(String manifest) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      ZipEntry entry = new ZipEntry("manifest.xml");
      entry.setTime(0); // Stable bytes make repeated provisioning idempotent.
      zip.putNextEntry(entry);
      zip.write(manifest.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return out.toByteArray();
  }

  static synchronized void prepare(Context c) throws Exception {
    String reason = unsupported(c);
    if (!reason.isEmpty()) throw new IOException(reason);
    File root = stage(c);
    if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法准备安装目录");
    // Prevent a concurrent shell run from reading a partially prepared payload.
    File ready = new File(root, "ready");
    File lock = new File(root, ".install-lock");
    if (!lock.mkdir()) throw new IOException("安装正在运行，请稍后重试");
    try {
    if (ready.exists() && !ready.delete()) throw new IOException("无法更新安装状态");
    String identity = id(c);
    String manifest = new String(asset(c, "manifest.xml"), StandardCharsets.UTF_8)
        .replace("content://com.reardaily.media/state", "content://com.reardaily.media/state?setup="
            + identity + "&amp;revision=" + REVISION);
    byte[] template = archive(manifest);
    write(new File(root, "rearscreen"), template);
    write(new File(root, "description.xml"), asset(c, "description.xml"));
    write(new File(root, "resource.mrm"), metadata(c).toString().getBytes(StandardCharsets.UTF_8));
    Bitmap image = Bitmap.createBitmap(976, 596, Bitmap.Config.ARGB_8888);
    try {
      Canvas canvas = new Canvas(image);
      canvas.drawColor(Color.rgb(13, 119, 98));
      Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
      paint.setColor(Color.WHITE);
      paint.setTextSize(70);
      paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
      canvas.drawText("每日背屏", 360, 285, paint);
      paint.setTextSize(26);
      paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
      canvas.drawText("图片 · 视频 · 每日更新", 360, 342, paint);
      try (OutputStream out = new FileOutputStream(new File(root, "preview.png"))) {
        if (!image.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("预览图生成失败");
      }
    } finally {
      image.recycle();
    }
    StringBuilder checksums = new StringBuilder();
    for (String name : new String[] {"rearscreen", "description.xml", "resource.mrm", "preview.png"})
      checksums.append(hash(Files.readAllBytes(new File(root, name).toPath())))
          .append("  ").append(name).append('\n');
    write(new File(root, "checksums"), checksums.toString().getBytes(StandardCharsets.UTF_8));
    String templateHash = hash(checksums.toString().getBytes(StandardCharsets.UTF_8));
    String script = new String(asset(c, "install.sh"), StandardCharsets.UTF_8)
        .replace("@@APP@@", quote(c.getPackageName()))
        .replace("@@ID@@", quote(identity))
        .replace("@@FILES@@", quote(themeFiles().getPath()))
        .replace("@@HASH@@", quote(templateHash))
        .replace("@@USER@@", Integer.toString(android.os.Process.myUid() / 100000));
    write(new File(root, "install.sh"), script.getBytes(StandardCharsets.UTF_8));
    SharedPreferences prefs = Store.prefs(c);
    SharedPreferences.Editor edit = prefs.edit().putString("setup.hash", templateHash)
        .putInt("setup.revision", REVISION);
    if (prefs.getInt("setup.revision", 0) != REVISION)
      edit.remove("setup.rearReadAt").remove("setup.previewReadAt");
    edit.commit();
    write(ready, identity.getBytes(StandardCharsets.UTF_8));
    } finally { lock.delete(); }
  }

  static JSONObject metadata(Context c) throws JSONException {
    JSONObject previews = new JSONObject().put("fallback", new JSONArray()
        .put(target(c) + "/preview/preview_rearscreen_0.png"));
    return new JSONObject().put("localId", "reardaily-" + id(c)).put("version", "1")
        .put("titles", new JSONObject().put("fallback", "每日背屏"))
        .put("descriptions", new JSONObject().put("fallback", "图片与视频由每日背屏 App 更新"))
        .put("authors", new JSONObject().put("fallback", "RearDaily"))
        .put("designers", new JSONObject().put("fallback", "RearDaily"))
        .put("builtInPreviews", previews).put("builtInThumbnails", previews)
        .put("extraMeta", new JSONObject().put("resourceType", "rearscreen"))
        .put("subResourceType", "signature").put("resSubType", "ai")
        .put("isRearScreenEditable", false).put("isRearScreenNeedLogin", false)
        .put("contentPath", target(c) + "/rearscreen")
        .put("metaPath", target(c) + "/resource.mrm")
        .put("parentResources", new JSONArray()).put("subResources", new JSONArray());
  }

  static JSONObject receipt(Context c) {
    try {
      File file = new File(stage(c), "installed");
      if (file.length() > 2048) return new JSONObject();
      String[] lines = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).split("\n");
      if (lines.length != 3 || !lines[0].equals(id(c))
          || !lines[1].equals(Store.prefs(c).getString("setup.hash", ""))) return new JSONObject();
      String path = lines[2];
      if (!validTarget(c, path)) return new JSONObject();
      return new JSONObject().put("path", path).put("hash", lines[1]);
    } catch (Exception e) {
      return new JSONObject();
    }
  }

  static boolean validTarget(Context c, String path) {
    if (path.equals(target(c))) return true;
    String prefix = new File(themeFiles(), "MIUI/.ai_wallpaper/maml").getPath() + "/";
    return path.startsWith(prefix) && path.substring(prefix.length()).matches("[a-zA-Z0-9_-]{1,128}");
  }

  static void recordRead(Context c, Uri uri) {
    if (!Store.prefs(c).getString("setup.id", "").equals(uri.getQueryParameter("setup"))
        || !Integer.toString(REVISION).equals(uri.getQueryParameter("revision"))) return;
    String[] packages = c.getPackageManager().getPackagesForUid(android.os.Binder.getCallingUid());
    if (packages == null) return;
    for (String p : packages) {
      String key = p.equals(REAR) ? "setup.rearReadAt" : p.equals(THEME) ? "setup.previewReadAt" : "";
      if (!key.isEmpty()) {
        // MAML polls frequently; avoid rewriting preferences on every query.
        long now = System.currentTimeMillis();
        if (now - Store.prefs(c).getLong(key, 0) > 5000)
          Store.prefs(c).edit().putLong(key, now).apply();
      }
    }
  }

  static String status(Context c) {
    String reason = unsupported(c);
    if (!reason.isEmpty()) return reason;
    long rear = Store.prefs(c).getLong("setup.rearReadAt", 0);
    if (rear > 0) return "背屏已读取此模板 · 最近 " + new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        .format(new Date(rear)) + "\n这是最近一次读取记录；重新检测时请唤醒背屏。";
    if (receipt(c).has("path")) return "模板已安装 · 等待在系统预览中应用并唤醒背屏";
    return "尚未验证初始化 · App 内的素材缓存与系统背屏配置是两个步骤";
  }

  static void set(Object object, String method, String value) throws Exception {
    object.getClass().getMethod(method, String.class).invoke(object, value);
  }

  static Intent settingsIntent() {
    return new Intent("miui.intent.action.rearscreen_setting").setPackage(THEME)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
  }

  static boolean registered(String json, String identity) throws JSONException {
    if (json == null || json.trim().isEmpty() || json.equals("null")) return false;
    JSONArray data = new JSONObject(json).getJSONArray("data");
    String filename = "/rearscreen_reardaily-" + identity + "_" + identity + ".mrc";
    for (int i = 0; i < data.length(); i++)
      if (data.getJSONObject(i).optString("path").endsWith(filename)) return true;
    return false;
  }

  static boolean registered(Context c) throws IOException {
    try {
      return registered(android.provider.Settings.Secure.getString(
          c.getContentResolver(), "theme_rear_widget"), id(c));
    } catch (Exception e) {
      throw new IOException("无法读取系统背屏列表，请切换手动操作，避免重复添加壁纸", e);
    }
  }

  /** ROM adapter: uses the installed system's own Serializable model; no vendor bytecode bundled. */
  static Intent previewIntent(Context c) throws Exception {
    String reason = automaticUnsupported(c);
    if (!reason.isEmpty()) throw new IOException(reason);
    String path = receipt(c).optString("path");
    if (path.isEmpty()) throw new IOException("请先准备文件并执行安装命令");
    if (!path.equals(target(c))) throw new IOException("手动替换后，请在系统设置里重新应用原占位壁纸");
    // Reopening a new-resource preview would append another widget on this ROM.
    // Route existing registrations to the native list, where they can be selected or removed.
    if (registered(c)) return settingsIntent();
    ApplicationInfo app = c.getPackageManager().getApplicationInfo(THEME, 0);
    ClassLoader loader = new PathClassLoader(app.sourceDir, RearSetup.class.getClassLoader());
    Object resource = loader.loadClass("com.android.thememanager.basemodule.resource.model.Resource")
        .getConstructor().newInstance();
    set(resource, "setLocalId", "reardaily-" + id(c));
    set(resource, "setContentPath", path + "/rearscreen");
    set(resource, "setMetaPath", path + "/resource.mrm");
    set(resource, "setCategory", "rearscreen");
    Object info = resource.getClass().getMethod("getLocalInfo").invoke(resource);
    set(info, "setTitle", "每日背屏");
    set(info, "setActualTitle", "每日背屏");
    set(info, "setSubResourceType", "signature");
    set(info, "setDescription", "图片与视频由每日背屏 App 更新");
    set(info, "setVersion", Integer.toString(REVISION));
    set(info, "setAuthor", "RearDaily");
    set(info, "setDesigner", "RearDaily");
    info.getClass().getMethod("setRearScreenEditable", boolean.class).invoke(info, false);
    resource.getClass().getMethod("putExtraMeta", String.class, String.class)
        .invoke(resource, "resourceType", "rearscreen");
    resource.getClass().getMethod("addBuildInPreview", String.class)
        .invoke(resource, path + "/preview/preview_rearscreen_0.png");
    // Declare our own generated template as a local custom resource.
    Class<?> extraClass = loader.loadClass("com.rearScreen.bean.RearScreenDetailExtraBean");
    Object companion = extraClass.getField("Companion").get(null);
    Object extra = companion.getClass().getMethod("createDefaultRearExtraBean").invoke(companion);
    extraClass.getMethod("setAiResource", boolean.class).invoke(extra, true);
    set(extra, "setApplyId", id(c));
    return new Intent().setClassName(THEME, DETAIL)
        .putExtra("rear_screen_detail_extra", (android.os.Parcelable) extra)
        .putExtra("REQUEST_RES_OBJECT", (Serializable) resource)
        .putExtra("REQUEST_SOURCE_TYPE", 1)
        .putExtra("rear_screen_sub_type", "signature")
        .putExtra("rear_screen_title", "每日背屏")
        .putExtra("rear_new_task", true);
  }
}
