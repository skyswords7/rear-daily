package com.reardaily.app;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.util.Log;
import java.io.*;
import java.util.*;
import org.json.*;

public class MediaProvider extends ContentProvider {
  public boolean onCreate() {
    try {
      Store.ensure(getContext());
      DailyJob.schedule(getContext());
      return true;
    } catch (Exception e) {
      Log.e("RearDaily", "Initialise", e);
      return false;
    }
  }

  void enforceCaller() {
    int uid = Binder.getCallingUid();
    if (uid == android.os.Process.myUid() || uid == 2000 || uid == 0) return;
    String[] names = getContext().getPackageManager().getPackagesForUid(uid);
    if (names != null)
      for (String name : names)
        if (name.equals("com.android.thememanager") || name.equals("com.xiaomi.subscreencenter"))
          return;
    throw new SecurityException("RearDaily media is reserved for the system rear screen");
  }

  public String getType(Uri u) {
    return u.getPath() != null && u.getPath().startsWith("/image/")
        ? "image/jpeg"
        : "vnd.android.cursor.item/vnd.reardaily.state";
  }

  public Cursor query(Uri u, String[] p, String s, String[] a, String sort) {
    enforceCaller();
    if (!"/state".equals(u.getPath())) throw new IllegalArgumentException("Unknown URI");
    RearSetup.recordRead(getContext(), u);
    DailyJob.wake(getContext());
    JSONObject state = Store.state(getContext());
    String[] cols = p == null ? new String[] {"version", "image", "video", "title", "mode"} : p;
    MatrixCursor c = new MatrixCursor(cols);
    Map<String, Object> v = new HashMap<>();
    v.put("version", state.optString("version"));
    v.put("image", Store.imageUri(state));
    v.put("video", state.optString("videoPath"));
    v.put("title", state.optString("title"));
    v.put("videoAspect", state.optDouble("videoAspect", 16.0 / 9.0));
    v.put("mode", state.optInt("mode", Store.prefs(getContext()).getInt("mode", 3)));
    v.put("result", state.toString());
    Object[] row = new Object[cols.length];
    for (int i = 0; i < cols.length; i++) row[i] = v.get(cols[i]);
    c.addRow(row);
    c.setNotificationUri(getContext().getContentResolver(), Store.STATE);
    return c;
  }

  public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
    enforceCaller();
    if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
    List<String> parts = u.getPathSegments();
    if (parts.size() != 2 || !parts.get(0).equals("image"))
      throw new FileNotFoundException("Unknown media");
    File f = Store.imageFile(getContext(), parts.get(1));
    int uid = Binder.getCallingUid();
    String line =
        "image uid="
            + uid
            + " packages="
            + Arrays.toString(getContext().getPackageManager().getPackagesForUid(uid));
    Log.i("RearDaily", "OPEN " + line);
    getContext()
        .getSharedPreferences("diag", 0)
        .edit()
        .putString("lastRead", line)
        .putLong("lastReadAt", System.currentTimeMillis())
        .apply();
    return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
  }

  public Uri insert(Uri u, ContentValues v) {
    throw new UnsupportedOperationException("Read only");
  }

  public int update(Uri u, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException("Read only");
  }

  public int delete(Uri u, String s, String[] a) {
    throw new UnsupportedOperationException("Read only");
  }
}
