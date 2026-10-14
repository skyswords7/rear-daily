package com.reardaily.app;

import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

final class SecretVault {
  private static String alias(String provider) {
    return AIConfig.ALI.equals(provider) ? "reardaily.bailian.v1" : "reardaily.compatible.v1";
  }

  static android.content.SharedPreferences vault(Context c, String p) {
    return c.getSharedPreferences(AIConfig.ALI.equals(p) ? "vault" : "vault_compatible", 0);
  }

  private static javax.crypto.SecretKey key(String provider) throws Exception {
    String ALIAS = alias(provider);
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (!ks.containsAlias(ALIAS)) {
      KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      g.init(
          new KeyGenParameterSpec.Builder(
                  ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      g.generateKey();
    }
    return (javax.crypto.SecretKey) ks.getKey(ALIAS, null);
  }

  static boolean configured(Context c) {
    return configured(c, AIConfig.provider(c));
  }

  static boolean configured(Context c, String provider) {
    return vault(c, provider).contains("ciphertext");
  }

  static void save(Context c, String value) throws Exception {
    save(c, AIConfig.provider(c), value);
  }

  static void save(Context c, String provider, String value) throws Exception {
    value = value.trim();
    if (value.length() < 8
        || value.length() > 4096
        || java.util.regex.Pattern.compile("[\\s\\p{Cntrl}]").matcher(value).find())
      throw new Exception("API Key 格式不正确");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key(provider));
    byte[] data = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
    if (!vault(c, provider)
        .edit()
        .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
        .putString("ciphertext", Base64.encodeToString(data, Base64.NO_WRAP))
        .commit()) throw new Exception("密钥保存失败");
  }

  static String read(Context c) throws Exception {
    return read(c, AIConfig.provider(c));
  }

  static String read(Context c, String provider) throws Exception {
    android.content.SharedPreferences p = vault(c, provider);
    if (!configured(c, provider))
      throw new Exception("请先设置 " + AIConfig.name(provider) + " API Key");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        key(provider),
        new GCMParameterSpec(128, Base64.decode(p.getString("iv", ""), Base64.NO_WRAP)));
    return new String(
        cipher.doFinal(Base64.decode(p.getString("ciphertext", ""), Base64.NO_WRAP)),
        StandardCharsets.UTF_8);
  }

  static void clear(Context c) {
    clear(c, AIConfig.provider(c));
  }

  static void clear(Context c, String provider) {
    vault(c, provider).edit().clear().commit();
  }
}
