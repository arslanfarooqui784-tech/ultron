package com.arsh.ultron;

import android.content.Context;
import android.content.SharedPreferences;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;

/** Password (stored only as a salted hash) and AI keys, kept in the app's private storage. */
public class Store {
    private final SharedPreferences p;

    public Store(Context c) {
        p = c.getSharedPreferences("ultron", Context.MODE_PRIVATE);
    }

    // ---------- password ----------
    public boolean hasPassword() {
        return p.contains("pw_hash");
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    private static byte[] unhex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    private static String hash(String pw, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pw.toCharArray(), salt, 120000, 256);
        return hex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
    }

    public void setPassword(String pw) throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        p.edit().putString("pw_salt", hex(salt)).putString("pw_hash", hash(pw, salt)).apply();
    }

    public boolean checkPassword(String pw) {
        try {
            String want = p.getString("pw_hash", "");
            String got = hash(pw, unhex(p.getString("pw_salt", "")));
            if (want.length() != got.length()) return false;
            int d = 0;
            for (int i = 0; i < want.length(); i++) d |= want.charAt(i) ^ got.charAt(i);
            return d == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- encrypted values (AES-GCM key kept in the phone's Android Keystore) ----------
    private static SecretKey vaultKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias("ultron_vault")) {
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder("ultron_vault", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
            g.generateKey();
        }
        return (SecretKey) ks.getKey("ultron_vault", null);
    }

    public synchronized void putSecret(String name, String value) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, vaultKey());
            byte[] out = c.doFinal((value == null ? "" : value).getBytes("UTF-8"));
            p.edit().putString("enc_" + name, Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":"
                + Base64.encodeToString(out, Base64.NO_WRAP)).remove(name).apply();
        } catch (Exception e) {
            p.edit().putString(name, value).apply();   // the phone has no keystore: keep it private only
        }
    }

    public synchronized String getSecret(String name, String def) {
        String v = p.getString("enc_" + name, null);
        if (v == null) return p.getString(name, def);
        try {
            String[] parts = v.split(":");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, vaultKey(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), "UTF-8");
        } catch (Exception e) {
            return def;
        }
    }

    // ---------- keys ----------
    public void setKeysText(String text) {
        putSecret("keys", text == null ? "" : text);
    }

    public void setFishKey(String k) {
        // like ULTRON on the PC: the key is the first word (pasting the whole fish_api_key.txt also works)
        String t = k == null ? "" : k.replace("PASTE_YOUR_FISH_AUDIO_API_KEY_HERE", "").trim();
        for (String w : t.split("\\s+")) {
            if (w.length() >= 16 && !w.equals("bb742915b1fa41b389a850b11efae0c8")) { putSecret("fish", w); return; }
        }
        putSecret("fish", t.isEmpty() ? "" : t.split("\\s+")[0]);
    }

    public String fishKey() {
        return getSecret("fish", "").trim();
    }

    // ---------- activity history (everything ULTRON does) ----------
    public synchronized void activity(String kind, String text) {
        try {
            org.json.JSONArray old = new org.json.JSONArray(getSecret("activity", "[]"));
            org.json.JSONArray now = new org.json.JSONArray();
            now.put(new org.json.JSONObject().put("t", System.currentTimeMillis()).put("kind", kind).put("text", text));
            for (int i = 0; i < old.length() && i < 299; i++) now.put(old.get(i));
            putSecret("activity", now.toString());
        } catch (Exception ignored) {
        }
    }

    /** brain -> key, found by the key's prefix (same rules as ULTRON on the PC). */
    public Map<String, String> keys() {
        Map<String, String> k = new LinkedHashMap<>();
        String text = getSecret("keys", "");
        for (String w : text.replace(",", " ").replace("=", " ").replace(":", " ").split("\\s+")) {
            w = w.trim().replace("\"", "").replace("'", "");
            if (w.isEmpty()) continue;
            if (w.startsWith("sk-ant-")) put(k, "claude", w);
            else if (w.startsWith("sk-or-")) put(k, "openrouter", w);
            else if (w.startsWith("gsk_")) put(k, "groq", w);
            else if (w.startsWith("xai-")) put(k, "grok", w);
            else if (w.startsWith("AIza") || w.startsWith("AQ.")) put(k, "gemini", w);
            else if (w.startsWith("sk-") && w.length() > 30) put(k, "chatgpt", w);
        }
        if (k.containsKey("openrouter")) {
            for (String b : new String[]{"chatgpt", "claude", "grok", "gemini"}) {
                if (!k.containsKey(b)) k.put(b, "OR:" + k.get("openrouter"));
            }
        }
        return k;
    }

    private static void put(Map<String, String> k, String b, String w) {
        if (!k.containsKey(b)) k.put(b, w);
    }

    public String get(String name, String def) {
        return p.getString(name, def);
    }

    public void put(String name, String value) {
        p.edit().putString(name, value).apply();
    }
}
