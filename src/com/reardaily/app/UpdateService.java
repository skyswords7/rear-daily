package com.reardaily.app;

import android.app.*;
import android.content.*;
import android.os.*;

public class UpdateService extends Service {
  static void start(Context c, String action) {
    c.startForegroundService(new Intent(c, UpdateService.class).putExtra("action", action));
  }

  static void startSelected(Context c, String url, boolean video) {
    c.startForegroundService(
        new Intent(c, UpdateService.class)
            .putExtra("action", "selected")
            .putExtra("url", url)
            .putExtra("video", video));
  }

  static void startLibrary(Context c, String mediaId) {
    c.startForegroundService(
        new Intent(c, UpdateService.class)
            .putExtra("action", "library")
            .putExtra("mediaId", mediaId));
  }

  boolean running;

  public IBinder onBind(Intent i) {
    return null;
  }

  public int onStartCommand(Intent i, int flags, int id) {
    if (running) return START_NOT_STICKY;
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(
        new NotificationChannel("daily", "素材更新", NotificationManager.IMPORTANCE_LOW));
    Notification n =
        new Notification.Builder(this, "daily")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("每日背屏")
            .setContentText("正在准备素材，完成后自动结束")
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
            .build();
    startForeground(31, n);
    Runnable end =
        () -> {
          running = false;
          stopForeground(STOP_FOREGROUND_REMOVE);
          stopSelf(id);
        };
    String action = i == null ? "daily" : i.getStringExtra("action");
    if (Updater.BUSY.get()) {
      end.run();
      return START_NOT_STICKY;
    }
    running = true;
    if ("library".equals(action)) DailyEngine.library(this, i.getStringExtra("mediaId"), end);
    else if ("selected".equals(action))
      DailyEngine.selected(this, i.getStringExtra("url"), i.getBooleanExtra("video", false), end);
    else if ("daily".equals(action)) Updater.check(this, true, end);
    else DailyEngine.manual(this, action, end);
    return START_NOT_STICKY;
  }
}
