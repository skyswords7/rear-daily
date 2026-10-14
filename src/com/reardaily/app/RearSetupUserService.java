package com.reardaily.app;

import android.content.Context;
import android.os.*;
import java.io.*;
import java.lang.Process;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Short-lived Shizuku UserService. Only this app's prepared installer can run. */
public final class RearSetupUserService extends Binder {
  static final String DESCRIPTOR = "com.reardaily.app.RearSetupInstaller";
  static final int INSTALL = IBinder.FIRST_CALL_TRANSACTION;
  static final int DESTROY = 16777115; // Shizuku UserService destroy contract.
  private final int appUid;
  private final String packageName;
  private final AtomicBoolean running = new AtomicBoolean();
  private volatile Process process;

  public RearSetupUserService(Context context) {
    appUid = context.getApplicationInfo().uid;
    packageName = context.getPackageName();
  }

  @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
      throws RemoteException {
    if (code == DESTROY) {
      int caller = Binder.getCallingUid();
      if (caller != 0 && caller != 2000 && caller != appUid) throw new SecurityException();
      Process current = process;
      if (current != null) current.destroy();
      System.exit(0);
      return true;
    }
    if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
    if (code != INSTALL) return super.onTransact(code, data, reply, flags);
    data.enforceInterface(DESCRIPTOR);
    if (Binder.getCallingUid() != appUid) throw new SecurityException("Caller mismatch");
    String stage = data.readString(), expectedHash = data.readString();
    if (data.dataAvail() != 0) throw new SecurityException("Unexpected arguments");
    if (!running.compareAndSet(false, true)) throw new IllegalStateException("安装正在进行");
    try {
      File script = validatedScript(stage, packageName, appUid / 100000, expectedHash);
      String output = install(script);
      reply.writeNoException();
      reply.writeString(output);
    } catch (Exception e) {
      reply.writeException(new IllegalStateException(Net.safeError(e)));
    } finally { running.set(false); }
    return true;
  }

  static File validatedScript(String stage, String pkg, int user, String expectedHash)
      throws Exception {
    // Primary external storage is user-scoped on the supported Xiaomi devices.
    String expected = "/storage/emulated/" + user + "/Android/data/" + pkg + "/files/rear-setup";
    if (!expected.equals(stage) || expectedHash == null || !expectedHash.matches("[a-f0-9]{64}"))
      throw new IOException("安装目录不属于当前 App，请重新准备文件");
    File script = new File(stage, "install.sh");
    if (!script.getCanonicalPath().equals(script.getAbsolutePath())
        || !script.isFile() || script.length() > 65536)
      throw new IOException("安装脚本不可用，请重新准备文件");
    if (!sha256(Files.readAllBytes(script.toPath())).equals(expectedHash))
      throw new IOException("安装脚本校验失败，请重新准备文件");
    return script;
  }

  static String sha256(byte[] data) throws Exception {
    StringBuilder out = new StringBuilder();
    for (byte b : MessageDigest.getInstance("SHA-256").digest(data))
      out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return out.toString();
  }

  private String install(File script) throws Exception {
    ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", script.getAbsolutePath(), "install");
    builder.redirectErrorStream(true);
    builder.environment().put("REAR_SETUP_NO_LAUNCH", "1");
    Process task = builder.start();
    process = task;
    AtomicBoolean timedOut = new AtomicBoolean();
    ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    timer.schedule(() -> { timedOut.set(true); task.destroy(); }, 35, TimeUnit.SECONDS);
    timer.schedule(() -> task.destroyForcibly(), 40, TimeUnit.SECONDS);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (InputStream input = task.getInputStream()) {
      byte[] chunk = new byte[1024];
      for (int n; (n = input.read(chunk)) != -1; ) {
        int keep = Math.min(n, Math.max(0, 4096 - output.size()));
        output.write(chunk, 0, keep); // Always drain the stream, bound Binder response size.
      }
      int code = task.waitFor();
      if (timedOut.get()) throw new IOException("安装超时，请检查 Shizuku 后重试");
      String text = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
      if (code != 0) throw new IOException(text.isEmpty() ? "模板安装失败，请重试" : text);
      return text;
    } finally {
      task.destroy();
      process = null;
      timer.shutdownNow();
    }
  }
}
