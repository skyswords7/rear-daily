package com.reardaily.app;

import android.content.*;

public class BootReceiver extends BroadcastReceiver {
  public void onReceive(Context c, Intent i) {
    DailyJob.schedule(c);
  }
}
