package com.reardaily.app;

import android.app.job.*;
import android.content.*;

public class DailyJob extends JobService {
  static final int ID = 2401, WAKE = 2402;

  static void schedule(Context c) {
    JobScheduler js = c.getSystemService(JobScheduler.class);
    if (js.getPendingJob(ID) == null)
      js.schedule(
          new JobInfo.Builder(ID, new ComponentName(c, DailyJob.class))
              .setPersisted(true)
              .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
              .setPeriodic(6 * 60 * 60 * 1000L, 60 * 60 * 1000L)
              .build());
  }

  static void wake(Context c) {
    if (!Store.prefs(c).getString("source", "demo").equals("daily")) {
      Updater.check(c, false, null);
      return;
    }
    if (!DailyEngine.due(c) || Updater.BUSY.get()) return;
    JobScheduler js = c.getSystemService(JobScheduler.class);
    if (js.getPendingJob(WAKE) == null)
      js.schedule(
          new JobInfo.Builder(WAKE, new ComponentName(c, DailyJob.class))
              .setMinimumLatency(1000)
              .build());
  }

  public boolean onStartJob(JobParameters p) {
    boolean started = Updater.check(this, false, () -> jobFinished(p, false));
    return started;
  }

  public boolean onStopJob(JobParameters p) {
    return true;
  }
}
