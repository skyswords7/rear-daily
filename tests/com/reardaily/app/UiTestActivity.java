package com.reardaily.app;

import android.content.*;
import android.os.Bundle;
import java.io.File;

/** Test-only activity: its preferences and files never overlap the user's content. */
public class UiTestActivity extends MainActivity {
  public SharedPreferences getSharedPreferences(String name, int mode) {
    return super.getSharedPreferences("ui_test_" + name, mode);
  }

  public Context getApplicationContext() {
    return this;
  }

  public File getFilesDir() {
    File f = new File(super.getFilesDir(), "ui-test");
    f.mkdirs();
    return f;
  }

  public File getExternalFilesDir(String type) {
    File f = new File(super.getExternalFilesDir(type), "ui-test");
    f.mkdirs();
    return f;
  }

  protected void onCreate(Bundle state) {
    if (getIntent().getBooleanExtra("reset", false) && state == null) {
      getSharedPreferences("settings", 0).edit().clear().putBoolean("auto", false).commit();
      getSharedPreferences("vault", 0).edit().clear().commit();
    }
    super.onCreate(state);
  }
}
