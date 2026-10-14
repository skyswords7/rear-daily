package com.reardaily.app;

import android.content.Context;
import android.os.Parcel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;

/** Read-only validation of the privileged boundary, never executes the installer. */
final class ShizukuTests {
  static String run(Context c) throws Exception {
    StringBuilder report = new StringBuilder();
    String pkg = c.getPackageName();
    int user = android.os.Process.myUid() / 100000;
    String stage = RearSetup.stage(c).getAbsolutePath();
    String hash = RearSetupUserService.sha256("sample".getBytes(StandardCharsets.UTF_8));
    check(report, hash.equals("af2bdbe1aa9b6ec1e2ade1d694f41fc71a831d0268e9891562113d8a62add1bf"),
        "installer digest matches known SHA-256 vector");
    for (String bad : new String[] {null, "", "/data/local/tmp", stage + "/../rear-setup",
        stage.replace(pkg, "com.other.app"), stage.replace("/emulated/" + user + "/", "/emulated/99/"),
        stage + "; touch /sdcard/unwanted"}) {
      boolean rejected = false;
      try { RearSetupUserService.validatedScript(bad, pkg, user, hash); }
      catch (Exception expected) { rejected = true; }
      check(report, rejected, "reject foreign, traversal or wrong-user installer path");
    }
    for (String bad : new String[] {null, "", "invalid", new String(new char[64]).replace('\0', 'g')}) {
      boolean rejected = false;
      try { RearSetupUserService.validatedScript(stage, pkg, user, bad); }
      catch (Exception expected) { rejected = true; }
      check(report, rejected, "reject malformed installer digest");
    }
    RearSetupUserService service = new RearSetupUserService(c);
    Parcel data = Parcel.obtain(), reply = Parcel.obtain();
    try {
      data.writeInterfaceToken("com.other.interface");
      data.setDataPosition(0);
      boolean rejected = false;
      try { service.onTransact(RearSetupUserService.INSTALL, data, reply, 0); }
      catch (SecurityException expected) { rejected = true; }
      check(report, rejected, "reject unexpected Binder interface before filesystem access");
    } finally { data.recycle(); reply.recycle(); }
    data = Parcel.obtain(); reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(RearSetupUserService.DESCRIPTOR);
      data.writeString(stage); data.writeString(hash); data.writeString("extra command");
      data.setDataPosition(0);
      boolean rejected = false;
      try { service.onTransact(RearSetupUserService.INSTALL, data, reply, 0); }
      catch (SecurityException expected) { rejected = true; }
      check(report, rejected, "reject additional Binder arguments rather than execute commands");
    } finally { data.recycle(); reply.recycle(); }
    java.util.concurrent.atomic.AtomicBoolean pending = new java.util.concurrent.atomic.AtomicBoolean(true);
    final int[] accepted = {0};
    ShizukuSetup.ConnectionReply receiver = new ShizukuSetup.ConnectionReply(
        android.os.Process.myUid() + 1, pending, binder -> accepted[0]++);
    data = Parcel.obtain();
    try {
      check(report, !receiver.onTransact(1, data, null, 1) && pending.get(),
          "connection reply from wrong UID is rejected without consuming legitimate callback");
      receiver = new ShizukuSetup.ConnectionReply(android.os.Process.myUid(), pending, binder -> accepted[0]++);
      check(report, !receiver.onTransact(2, data, null, 1) && pending.get(),
          "unexpected reply transaction is rejected");
      android.os.Binder fake = new android.os.Binder();
      fake.attachInterface(null, "com.other.service");
      data.writeStrongBinder(fake); data.writeString("/ignored/source.apk"); data.setDataPosition(0);
      check(report, !receiver.onTransact(1, data, null, 1) && accepted[0] == 0,
          "wrong service descriptor cannot be attached as Shizuku");
      check(report, !receiver.onTransact(1, data, null, 1), "connection callback is one-shot");
      pending.set(true); data.setDataSize(0); data.setDataPosition(0);
      fake.attachInterface(null, "moe.shizuku.server.IShizukuService");
      data.writeStrongBinder(fake); data.writeString("/ignored/source.apk"); data.setDataPosition(0);
      check(report, receiver.onTransact(1, data, null, 1) && accepted[0] == 1,
          "verified service is handed to the connector exactly once without loading an APK");
    } finally { data.recycle(); }
    return report.toString();
  }
  static void check(StringBuilder report, boolean ok, String label) {
    if (!ok) throw new AssertionError(label);
    report.append("PASS ").append(label).append('\n');
  }
}
