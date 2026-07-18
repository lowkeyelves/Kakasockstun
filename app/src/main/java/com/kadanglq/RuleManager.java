package com.kadanglq;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class RuleManager {

    private static final String PREFS_NAME = "kadanglq_rule_prefs";
    private static final String KEY_INSTALL_TIME = "install_time";
    private static final String KEY_RULES_CACHE = "rules_cache";

    public static final String RULE_URL = "https://gg.lowkeydoi.dpdns.org";
    public static final long EXPIRE_DAYS = 30L;

    private final SharedPreferences prefs;
    private volatile Set<String> cachedRules = null;

    public RuleManager(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS);
        if (!prefs.contains(KEY_INSTALL_TIME)) {
            prefs.edit().putLong(KEY_INSTALL_TIME, System.currentTimeMillis()).apply();
        }
    }

    public boolean isExpired() {
        long installTime = prefs.getLong(KEY_INSTALL_TIME, System.currentTimeMillis());
        long elapsedDays = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - installTime);
        return elapsedDays >= EXPIRE_DAYS;
    }

    public boolean isBlocked(String domain) {
        Set<String> rules = getRules();
        String d = domain.toLowerCase().trim();
        while (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        for (String r : rules) {
            if (d.equals(r) || d.endsWith("." + r)) return true;
        }
        return false;
    }

    private synchronized Set<String> getRules() {
        if (cachedRules != null) return cachedRules;
        String raw = prefs.getString(KEY_RULES_CACHE, "");
        cachedRules = parse(raw);
        return cachedRules;
    }

    public void refreshRulesBlocking() {
        try {
            URL url = new URL(RULE_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Cache-Control", "no-store");
            conn.setRequestProperty("Pragma", "no-cache");

            if (conn.getResponseCode() == 200) {
                StringBuilder sb = new StringBuilder();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append('\n');
                reader.close();

                String text = sb.toString();
                if (!text.trim().isEmpty()) {
                    prefs.edit().putString(KEY_RULES_CACHE, text).apply();
                    synchronized (this) {
                        cachedRules = parse(text);
                    }
                }
            }
            conn.disconnect();
        } catch (Exception e) {
        }
    }

    private Set<String> parse(String raw) {
        Set<String> set = new HashSet<>();
        if (raw == null) return set;
        for (String line : raw.split("\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("!")) continue;
            if (s.startsWith("||")) s = s.substring(2);
            if (s.endsWith("^")) s = s.substring(0, s.length() - 1);
            s = s.trim().toLowerCase();
            if (!s.isEmpty()) set.add(s);
        }
        return set;
    }
}
