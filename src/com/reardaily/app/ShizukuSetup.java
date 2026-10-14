package com.reardaily.app;

import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import rikka.shizuku.Shizuku;

/** Application-scoped operation survives dialog dismissal and Activity recreation. */
final class ShizukuSetup {
  static final String MANAGER = "moe.shizuku.privileged.api";
  static final int REQUEST = 8108;
  enum Availability { MISSING, STOPPED, OLD, DENIED, READY }
  private static ShizukuSetup instance;
  static synchronized ShizukuSetup get(Context c) {
    if (instance == null) {
      Context app = c.getApplicationContext();
      if (app instanceof android.app.Activity) app = ((android.app.Activity) app).getApplication();
      instance = new ShizukuSetup(app);
    }
    return instance;
  }
  final Context context;
  final Handler main = new Handler(Looper.getMainLooper());
  final Set<Runnable> listeners = new LinkedHashSet<>();
  String message = "";
  int phase, generation;
  private boolean previewPending;
  private ServiceConnection connection;
  private Shizuku.UserServiceArgs args;
  private Runnable deadline;
  private long lastConnectionRequest;

  private ShizukuSetup(Context context) {
    this.context = context;
    Shizuku.addBinderReceivedListener(() -> main.post(this::notifyChanged));
    Shizuku.addBinderDeadListener(() -> main.post(() -> {
      if (busy()) finish(generation, "Shizuku 已断开，请启动后重试。", false);
      else notifyChanged();
    }));
    Shizuku.addRequestPermissionResultListener((request, result) -> main.post(() -> {
      if (request != REQUEST || phase != 1) return;
      if (result == PackageManager.PERMISSION_GRANTED) prepare(generation);
      else finish(generation, "未获得授权。可以重新授权，或展开电脑 ADB 方式。", false);
    }));
  }

  Availability availability() {
    try {
      if (Shizuku.pingBinder()) {
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) return Availability.OLD;
        return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            ? Availability.READY : Availability.DENIED;
      }
      context.getPackageManager().getPackageInfo(MANAGER, 0);
      return Availability.STOPPED;
    } catch (PackageManager.NameNotFoundException e) { return Availability.MISSING; }
    catch (RuntimeException e) { return Availability.STOPPED; }
  }

  /** Official REQUEST_BINDER handshake also used by Shizuku's shell client.
   * Only accepts a one-shot reply from the installed manager UID; never loads its APK.
   * https://github.com/RikkaApps/Shizuku/blob/master/manager/src/main/java/moe/shizuku/manager/shell/ShellBinderRequestHandler.kt
   */
  void reconnect() {
    long now = SystemClock.elapsedRealtime();
    if (busy() || availability() != Availability.STOPPED || now - lastConnectionRequest < 5000) return;
    lastConnectionRequest = now;
    try {
      int managerUid = context.getPackageManager().getApplicationInfo(MANAGER, 0).uid;
      java.util.concurrent.atomic.AtomicBoolean pending = new java.util.concurrent.atomic.AtomicBoolean(true);
      Binder callback = new ConnectionReply(managerUid, pending, server -> main.post(() -> {
        if (!Shizuku.pingBinder()) {
          try { Shizuku.onBinderReceived(server, context.getPackageName()); }
          catch (RuntimeException ignored) { }
        }
      }));
      Bundle data = new Bundle();
      data.putBinder("binder", callback);
      context.sendBroadcast(new Intent("rikka.shizuku.intent.action.REQUEST_BINDER")
          .setPackage(MANAGER)
          .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND)
          .putExtra("data", data));
      main.postDelayed(() -> pending.set(false), 5000);
    } catch (PackageManager.NameNotFoundException | RuntimeException ignored) { }
  }

  static final class ConnectionReply extends Binder {
    final int expectedUid;
    final java.util.concurrent.atomic.AtomicBoolean pending;
    final java.util.function.Consumer<IBinder> accept;
    ConnectionReply(int expectedUid, java.util.concurrent.atomic.AtomicBoolean pending,
        java.util.function.Consumer<IBinder> accept) {
      this.expectedUid = expectedUid;
      this.pending = pending;
      this.accept = accept;
    }
    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException {
      if (code != 1 || Binder.getCallingUid() != expectedUid || !pending.compareAndSet(true, false))
        return false;
      IBinder server = data.readStrongBinder();
      data.readString(); // Source APK path is deliberately unused.
      if (data.dataAvail() != 0 || server == null || !server.pingBinder()
          || !"moe.shizuku.server.IShizukuService".equals(server.getInterfaceDescriptor())) return false;
      accept.accept(server);
      return true;
    }
  }

  boolean busy() { return phase != 0; }
  boolean takePreview() {
    boolean pending = previewPending;
    previewPending = false;
    return pending;
  }
  void addListener(Runnable listener) { listeners.add(listener); }
  void removeListener(Runnable listener) { listeners.remove(listener); }
  private void notifyChanged() { for (Runnable listener : new ArrayList<>(listeners)) listener.run(); }

  void openManager(Context host) {
    Intent intent = host.getPackageManager().getLaunchIntentForPackage(MANAGER);
    if (intent == null) intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"));
    host.startActivity(intent);
  }

  void start() {
    if (busy()) return;
    String unsupported = RearSetup.automaticUnsupported(context);
    if (!unsupported.isEmpty()) { message = unsupported; notifyChanged(); return; }
    Availability available = availability();
    if (available != Availability.READY && available != Availability.DENIED) {
      message = "请先在 Shizuku 中完成启动，再返回这里。";
      notifyChanged();
      return;
    }
    int token = ++generation;
    previewPending = false;
    phase = 1;
    message = "请在 Shizuku 提示中允许“每日背屏”…";
    notifyChanged();
    setDeadline(token, 90000, "等待授权超时，请重试。");
    try {
      if (available == Availability.READY) prepare(token);
      else if (Shizuku.shouldShowRequestPermissionRationale())
        finish(token, "授权已被拒绝，请打开 Shizuku → 已授权应用，允许“每日背屏”后再试。", false);
      else Shizuku.requestPermission(REQUEST);
    } catch (RuntimeException e) { finish(token, "授权未完成，请检查 Shizuku 后重试。", false); }
  }

  private void prepare(int token) {
    phase = 2;
    message = "正在准备背屏模板…";
    setDeadline(token, 60000, "准备超时，请稍后重试。");
    notifyChanged();
    RearSetup.EXEC.execute(() -> {
      try {
        RearSetup.prepare(context);
        String stage = RearSetup.stage(context).getAbsolutePath();
        String hash = RearSetupUserService.sha256(Files.readAllBytes(new File(stage, "install.sh").toPath()));
        main.post(() -> { if (active(token)) bind(token, stage, hash); });
      } catch (Exception e) { main.post(() -> finish(token, "准备未完成：" + Net.safeError(e), false)); }
    });
  }

  private boolean active(int token) { return token == generation && busy(); }
  private void bind(int token, String stage, String hash) {
    phase = 3;
    message = "正在连接 Shizuku…";
    notifyChanged();
    args = new Shizuku.UserServiceArgs(new ComponentName(context, RearSetupUserService.class))
        .tag("rear-setup-" + android.os.Process.myUid()).version(8).daemon(false)
        .processNameSuffix("rear_setup").debuggable(false);
    connection = new ServiceConnection() {
      @Override public void onServiceConnected(ComponentName name, IBinder service) {
        main.post(() -> {
          if (!active(token) || phase != 3) return;
          phase = 4;
          message = "正在安装模板，请稍候…";
          setDeadline(token, 50000, "安装连接超时，请检查 Shizuku 后重试。");
          notifyChanged();
          RearSetup.EXEC.execute(() -> {
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
              data.writeInterfaceToken(RearSetupUserService.DESCRIPTOR);
              data.writeString(stage);
              data.writeString(hash);
              if (!service.transact(RearSetupUserService.INSTALL, data, reply, 0))
                throw new IOException("安装服务不可用");
              reply.readException();
              reply.readString();
              if (!RearSetup.target(context).equals(RearSetup.receipt(context).optString("path")))
                throw new IOException("安装回执校验失败，请重新设置");
              main.post(() -> finish(token, "模板已安装，请在系统页面中应用“每日背屏”。", true));
            } catch (Exception e) {
              main.post(() -> finish(token, "安装未完成：" + Net.safeError(e), false));
            } finally { data.recycle(); reply.recycle(); }
          });
        });
      }
      @Override public void onServiceDisconnected(ComponentName name) {
        main.post(() -> { if (active(token)) finish(token, "安装连接已断开，请重新启动 Shizuku 后重试。", false); });
      }
    };
    setDeadline(token, 20000, "无法连接安装服务，请重新启动 Shizuku 后重试。");
    try { Shizuku.bindUserService(args, connection); }
    catch (RuntimeException e) { finish(token, "无法连接 Shizuku：" + Net.safeError(e), false); }
  }

  private void setDeadline(int token, long ms, String error) {
    if (deadline != null) main.removeCallbacks(deadline);
    deadline = () -> finish(token, error, false);
    main.postDelayed(deadline, ms);
  }

  private void finish(int token, String result, boolean success) {
    if (!active(token)) return;
    phase = 0; // Set before unbinding: disconnect callbacks must not overwrite success.
    message = result;
    previewPending = success;
    if (deadline != null) main.removeCallbacks(deadline);
    deadline = null;
    if (args != null && connection != null) {
      try { Shizuku.unbindUserService(args, connection, true); } catch (RuntimeException ignored) { }
      try { Shizuku.unbindUserService(args, connection, false); } catch (RuntimeException ignored) { }
    }
    args = null;
    connection = null;
    notifyChanged();
  }
}
