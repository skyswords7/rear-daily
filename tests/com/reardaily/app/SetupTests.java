package com.reardaily.app;

import android.content.*;
import android.net.Uri;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import org.json.*;

final class SetupTests {
  static String run(Context real) throws Exception {
    String ns = "setup_test_" + UUID.randomUUID().toString().replace("-", "");
    File sandbox = new File(real.getCacheDir(), ns);
    sandbox.mkdirs();
    Context c = new ContextWrapper(real) {
      public File getExternalFilesDir(String type) { return sandbox; }
      public SharedPreferences getSharedPreferences(String name, int mode) {
        return real.getSharedPreferences(ns + "_" + name, mode);
      }
    };
    StringBuilder report = new StringBuilder();
    try {
      String identity = RearSetup.id(c);
      check(report, identity.matches("[a-f0-9]{32}") && identity.equals(RearSetup.id(c)),
          "installation identity remains stable across preparation");
      check(report, !identity.equals(RearSetup.id(real)), "isolated installation receives a different identity");
      RearSetup.prepare(c);
      File stage = RearSetup.stage(c);
      byte[] first = Files.readAllBytes(new File(stage, "rearscreen").toPath());
      String hash = Store.prefs(c).getString("setup.hash", "");
      RearSetup.prepare(c);
      check(report, Arrays.equals(first, Files.readAllBytes(new File(stage, "rearscreen").toPath()))
          && hash.equals(Store.prefs(c).getString("setup.hash", "")), "repeat preparation is byte-stable");
      try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(first))) {
        check(report, zip.getNextEntry().getName().equals("manifest.xml"), "self-authored manifest in template");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Store.copy(zip, out, 1024 * 1024);
        String manifest = out.toString("UTF-8");
        check(report, manifest.contains("setup=" + identity + "&amp;revision=" + RearSetup.REVISION)
            && zip.getNextEntry() == null, "template bound to this installation, with no vendor assets");
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(new ByteArrayInputStream(out.toByteArray()));
      }
      JSONObject meta = new JSONObject(new String(Files.readAllBytes(new File(stage, "resource.mrm").toPath()), StandardCharsets.UTF_8));
      check(report, meta.getString("localId").equals("reardaily-" + identity)
          && meta.getString("contentPath").equals(RearSetup.target(c) + "/rearscreen"), "metadata resolves current installation path");
      File lock = new File(stage, ".install-lock");
      lock.mkdir();
      boolean rejected = false;
      try { RearSetup.prepare(c); } catch (IOException expected) { rejected = true; }
      check(report, rejected && lock.isDirectory() && Arrays.equals(first,
          Files.readAllBytes(new File(stage, "rearscreen").toPath())), "busy preparation preserves files and other operation's lock");
      lock.delete();
      File receipt = new File(stage, "installed");
      RearSetup.write(receipt, (identity + "\n" + hash + "\n" + RearSetup.target(c) + "\n").getBytes(StandardCharsets.UTF_8));
      check(report, RearSetup.receipt(c).has("path"), "valid installation receipt accepted");
      String activePath = "/data/system/theme/rearScreenWhite/rearscreen_reardaily-" + identity + "_" + identity + ".mrc";
      String activeList = new JSONObject().put("data", new JSONArray().put(new JSONObject().put("path", activePath))).toString();
      check(report, RearSetup.registered(activeList, identity) && !RearSetup.registered(activeList, RearSetup.id(real))
          && !RearSetup.registered("{\"data\":[]}", identity), "existing registration distinguished from first install and other devices");
      Intent intent = RearSetup.previewIntent(c);
      check(report, intent.getComponent().getPackageName().equals(RearSetup.THEME)
          && intent.hasExtra("rear_screen_detail_extra") && intent.hasExtra("REQUEST_RES_OBJECT"),
          "installed ROM accepts custom resource model and preview extras");
      for (String bad : new String[] {"wrong\n" + hash + "\n" + RearSetup.target(c),
          identity + "\nwrong\n" + RearSetup.target(c), identity + "\n" + hash + "\n/sdcard/other"}) {
        RearSetup.write(receipt, bad.getBytes(StandardCharsets.UTF_8));
        check(report, !RearSetup.receipt(c).has("path"), "foreign or outdated receipt rejected");
      }
      String manual = new File(RearSetup.themeFiles(), "MIUI/.ai_wallpaper/maml/confirmed-1").getPath();
      check(report, RearSetup.validTarget(c, manual) && !RearSetup.validTarget(c, manual + "/../victim")
          && !RearSetup.validTarget(c, RearSetup.target(c) + "-other"), "manual target validation prevents traversal and prefix confusion");
      RearSetup.recordRead(c, Uri.parse("content://com.reardaily.media/state?setup=" + identity + "&revision=" + RearSetup.REVISION));
      check(report, !Store.prefs(c).contains("setup.rearReadAt") && !Store.prefs(c).contains("setup.previewReadAt"),
          "own-app queries cannot falsely confirm system preview or rear display");
      check(report, RearSetup.command(c, "").contains(sandbox.getPath())
          && RearSetup.quote("a'b").equals("'a'\"'\"'b'"), "command uses actual app storage and shell quoting");
      check(report, new File(stage, "ready").isFile() && !lock.exists(), "successful preparation is committed and unlocked");
      return report.toString();
    } finally {
      Store.prefs(c).edit().clear().commit();
      V05Tests.remove(sandbox, sandbox.getCanonicalPath());
    }
  }

  static void check(StringBuilder report, boolean value, String label) throws Exception {
    if (!value) throw new Exception(label);
    report.append("PASS ").append(label).append('\n');
  }
}
