package com.reardaily.app;

import android.app.*;
import android.content.*;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public class SmokeInstrumentation extends Instrumentation {
  Bundle args;
  StringBuilder report = new StringBuilder();

  void ok(boolean value, String label) {
    if (!value) throw new AssertionError(label);
    report.append("PASS ").append(label).append('\n');
  }

  public void onCreate(Bundle arguments) {
    args = arguments;
    start();
  }

  public void onStart() {
    Bundle out = new Bundle();
    Context c = getTargetContext();
    android.content.SharedPreferences p = Store.prefs(c);
    Map<String, ?> saved = new HashMap<>(p.getAll());
    int result = Activity.RESULT_OK;
    try {
      Store.ensure(c);
      JSONObject s = Store.state(c);
      Uri img = Uri.parse(Store.imageUri(s));
      try (ParcelFileDescriptor fd = c.getContentResolver().openFileDescriptor(img, "r")) {
        ok(fd != null && fd.getStatSize() > 0, "seekable image file descriptor");
        ok(
            BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor()) != null,
            "image decodes through provider");
      }
      boolean denied = false;
      try {
        c.getContentResolver().openFileDescriptor(img, "w");
      } catch (Exception expected) {
        denied = true;
      }
      ok(denied, "provider rejects writes");
      denied = false;
      try {
        c.getContentResolver()
            .openFileDescriptor(
                Uri.parse("content://com.reardaily.media/image/..%2Fstate.json"), "r");
      } catch (Exception expected) {
        denied = true;
      }
      ok(denied, "provider rejects path traversal");
      String before = s.getString("version");
      File good = Store.asset(c, "welcome-alt.jpg"),
          bad = File.createTempFile("invalid-video-", ".tmp", c.getCacheDir());
      try {
        try (FileOutputStream f = new FileOutputStream(bad)) {
          f.write("not a video".getBytes());
        }
        boolean rejected = false;
        try {
          Store.publish(c, good, bad, "invalid", "test", "");
        } catch (Exception expected) {
          rejected = true;
        }
        ok(
            rejected && before.equals(Store.state(c).getString("version")),
            "invalid video preserves complete previous cache");
      } finally {
        good.delete();
        bad.delete();
      }
      String base = args.getString("baseUrl", "");
      if (!base.isEmpty()) {
        p.edit()
            .putString("source", "popular")
            .putString("endpoint.popular", base + "/manifest")
            .putBoolean("auto", true)
            .remove("successDay.popular")
            .putLong("lastAttempt", 0)
            .commit();
        CountDownLatch done = new CountDownLatch(1);
        ok(Updater.check(c, true, done::countDown), "network update started");
        ok(done.await(90, TimeUnit.SECONDS), "network update completed");
        s = Store.state(c);
        ok(
            !before.equals(s.getString("version"))
                && s.getString("sourceVersion").equals("integration-red-v1"),
            "download publishes new image and video generation");
        ok(
            Store.today().equals(p.getString("successDay.popular", "")),
            "successful download marks current local day");
        ok(!Updater.check(c, false, null), "automatic update skipped after today's success");
        String updated = s.getString("version");
        p.edit().putString("endpoint.popular", base + "/failure").commit();
        CountDownLatch failed = new CountDownLatch(1);
        Updater.check(c, true, failed::countDown);
        ok(failed.await(30, TimeUnit.SECONDS), "failed request completed");
        ok(
            updated.equals(Store.state(c).getString("version")),
            "network failure preserves current generation");
        try (ParcelFileDescriptor fd =
            c.getContentResolver()
                .openFileDescriptor(Uri.parse(Store.state(c).getString("videoUri")), "r")) {
          ok(fd != null && fd.getStatSize() > 0, "published video remains readable offline");
        }
      }
    } catch (Throwable e) {
      result = Activity.RESULT_CANCELED;
      report.append("FAIL ").append(e.toString());
    } finally {
      android.content.SharedPreferences.Editor edit = p.edit().clear();
      for (Map.Entry<String, ?> entry : saved.entrySet()) {
        Object value = entry.getValue();
        String k = entry.getKey();
        if (value instanceof String) edit.putString(k, (String) value);
        else if (value instanceof Boolean) edit.putBoolean(k, (Boolean) value);
        else if (value instanceof Integer) edit.putInt(k, (Integer) value);
        else if (value instanceof Long) edit.putLong(k, (Long) value);
        else if (value instanceof Float) edit.putFloat(k, (Float) value);
      }
      edit.commit();
      c.getContentResolver().notifyChange(Store.STATE, null);
      out.putString("stream", report.toString());
      finish(result, out);
    }
  }
}
