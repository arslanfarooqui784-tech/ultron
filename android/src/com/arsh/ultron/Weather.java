package com.arsh.ultron;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live weather, forecast and air quality for any city (Open-Meteo: free, no key, no account).
 * Facts only: ULTRON never guesses the weather and never reveals where Arsh is.
 */
public class Weather {
    public static final Pattern ASKS = Pattern.compile("(?i)\\b(weather|forecast|temperature|temp|mausam|mosam|baarish|barish|rain(ing|y)?|"
        + "garmi|sardi|thand|humid(ity)?|hot|cold|sunny|cloudy|storm|aqi|air quality|pollution|degree|degrees|monsoon|umbrella)\\b|मौसम|बारिश|तापमान|موسم|بارش");
    static final Map<String, Object[]> cache = new HashMap<>();   // city -> {time, facts}
    static final Map<String, JSONObject> places = new HashMap<>();

    static String get(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(12000);
        h.setRequestProperty("User-Agent", "ULTRON");
        int code = h.getResponseCode();
        InputStream in = code < 400 ? h.getInputStream() : h.getErrorStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (in != null && (n = in.read(buf)) > 0) b.write(buf, 0, n);
        if (code >= 400) throw new Exception("weather service error " + code);
        return b.toString("UTF-8");
    }

    /** City name -> {name, admin1, country, latitude, longitude}, or null. */
    static JSONObject place(String city) throws Exception {
        String k = city.trim().toLowerCase(Locale.ROOT);
        if (places.containsKey(k)) return places.get(k);
        JSONObject r = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=5&language=en&format=json&name="
            + URLEncoder.encode(city.trim(), "UTF-8")));
        JSONArray a = r.optJSONArray("results");
        if (a == null || a.length() == 0) return null;
        JSONObject best = a.getJSONObject(0);
        for (int i = 0; i < a.length(); i++) if ("IN".equals(a.getJSONObject(i).optString("country_code"))) { best = a.getJSONObject(i); break; }  // prefer India
        places.put(k, best);
        return best;
    }

    static String sky(int code) {
        switch (code) {
            case 0: return "clear sky";
            case 1: return "mainly clear";
            case 2: return "partly cloudy";
            case 3: return "overcast";
            case 45: case 48: return "fog";
            case 51: case 53: case 55: return "drizzle";
            case 56: case 57: return "freezing drizzle";
            case 61: return "light rain";
            case 63: return "moderate rain";
            case 65: return "heavy rain";
            case 66: case 67: return "freezing rain";
            case 71: case 73: case 75: case 77: return "snow";
            case 80: return "light rain showers";
            case 81: return "rain showers";
            case 82: return "violent rain showers";
            case 85: case 86: return "snow showers";
            case 95: return "thunderstorm";
            case 96: case 99: return "thunderstorm with hail";
            case 97: return "heavy thunderstorm";
            default: return "weather code " + code;
        }
    }

    static String aqiWord(int aqi) {
        return aqi <= 50 ? "good" : aqi <= 100 ? "moderate" : aqi <= 150 ? "unhealthy for sensitive people" : aqi <= 200 ? "unhealthy"
            : aqi <= 300 ? "very unhealthy" : "hazardous";
    }

    /** Plain facts for one city: now, today, next days, air quality. Cached for 10 minutes. */
    public static String facts(String city, int days) throws Exception {
        String k = city.trim().toLowerCase(Locale.ROOT) + "|" + days;
        synchronized (cache) {
            Object[] c = cache.get(k);
            if (c != null && System.currentTimeMillis() - (Long) c[0] < 10 * 60000L) return (String) c[1];
        }
        JSONObject p = place(city);
        if (p == null) return "No place called '" + city + "' was found.";
        double lat = p.getDouble("latitude"), lon = p.getDouble("longitude");
        days = Math.max(1, Math.min(7, days));
        JSONObject w = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
            + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,precipitation,is_day"
            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,uv_index_max"
            + "&timezone=auto&forecast_days=" + days));
        JSONObject cur = w.getJSONObject("current"), d = w.getJSONObject("daily");
        String name = p.optString("name") + (p.optString("admin1").isEmpty() || p.optString("admin1").equals(p.optString("name")) ? "" : ", " + p.optString("admin1"))
            + (p.optString("country").isEmpty() ? "" : ", " + p.optString("country"));
        StringBuilder b = new StringBuilder(name).append(" - now: ").append(Math.round(cur.optDouble("temperature_2m"))).append("°C (feels like ")
            .append(Math.round(cur.optDouble("apparent_temperature"))).append("°C), ").append(sky(cur.optInt("weather_code")))
            .append(", humidity ").append(cur.optInt("relative_humidity_2m")).append("%, wind ").append(Math.round(cur.optDouble("wind_speed_10m"))).append(" km/h")
            .append(cur.optDouble("precipitation") > 0 ? ", raining now (" + cur.optDouble("precipitation") + " mm)" : "").append(".");
        JSONArray t = d.getJSONArray("time");
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT), out = new SimpleDateFormat("EEEE", Locale.ENGLISH);
        for (int i = 0; i < t.length(); i++) {
            String day = i == 0 ? "Today" : i == 1 ? "Tomorrow" : out.format(in.parse(t.getString(i)));
            b.append(" ").append(day).append(": ").append(sky(d.getJSONArray("weather_code").optInt(i))).append(", ")
                .append(Math.round(d.getJSONArray("temperature_2m_min").optDouble(i))).append("-").append(Math.round(d.getJSONArray("temperature_2m_max").optDouble(i)))
                .append("°C, rain chance ").append(d.getJSONArray("precipitation_probability_max").optInt(i)).append("%")
                .append(i == 0 ? ", UV " + Math.round(d.getJSONArray("uv_index_max").optDouble(i)) : "").append(".");
        }
        try {
            JSONObject aq = new JSONObject(get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + lat + "&longitude=" + lon
                + "&current=us_aqi,pm2_5")).getJSONObject("current");
            int aqi = aq.optInt("us_aqi", -1);
            if (aqi >= 0) b.append(" Air quality: AQI ").append(aqi).append(" (").append(aqiWord(aqi)).append("), PM2.5 ")
                .append(Math.round(aq.optDouble("pm2_5"))).append(".");
        } catch (Exception ignored) {
        }
        b.append(" (Live data from Open-Meteo, ").append(new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new Date())).append(")");
        String f = b.toString();
        synchronized (cache) {
            cache.put(k, new Object[]{System.currentTimeMillis(), f});
        }
        return f;
    }

    /** Facts for several cities, one line each. */
    public static String factsFor(java.util.List<String> cities, int days) {
        StringBuilder b = new StringBuilder();
        for (String c : cities) {
            if (c.trim().isEmpty()) continue;
            try {
                b.append(facts(c, days)).append("\n");
            } catch (Exception e) {
                b.append(c).append(": weather service not reachable right now.\n");
            }
        }
        return b.toString().trim();
    }

    /** Which places does this text ask about? Uses the AI; falls back to "in/of/at X". */
    public static java.util.List<String> cities(Brain brain, String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            String raw = brain.quick("Which cities or places does this ask the weather, forecast or air quality for? Text: \"" + text
                + "\". Answer ONLY with JSON {\"places\": [\"City\"], \"days\": 1 to 7 (how many days of forecast they need, 1 = today only)}. "
                + "Only places actually named in the text. If none is named, places is empty.", "Output only JSON.");
            JSONObject j = new JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1));
            JSONArray a = j.optJSONArray("places");
            for (int i = 0; a != null && i < a.length() && out.size() < 6; i++) out.add(a.getString(i));
            lastDays = Math.max(1, Math.min(7, j.optInt("days", 1)));
            if (lastDays < 3 && Pattern.compile("(?i)tomorrow|kal|forecast|week|weekend|next").matcher(text).find()) lastDays = 3;
        } catch (Exception e) {
            java.util.regex.Matcher m = Pattern.compile("(?i)\\b(?:in|of|at|for)\\s+([A-Z][a-zA-Z]+(?:\\s+[A-Z][a-zA-Z]+)?)").matcher(text);
            while (m.find() && out.size() < 6) out.add(m.group(1));
            lastDays = 3;
        }
        return out;
    }

    static volatile int lastDays = 1;

    /** Today's rain chance in a city (0-100), or -1. */
    public static int todayRain(String city) {
        try {
            Matcher m = Pattern.compile("Today: [^.]*rain chance (\\d+)%").matcher(facts(city, 1));
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** "22°C, partly cloudy" for the widget. */
    public static String shortNow(String city) {
        try {
            Matcher m = Pattern.compile("now: (-?\\d+°C)[^,]*\\), ([a-z ]+)").matcher(facts(city, 1));
            return m.find() ? m.group(1) + " · " + m.group(2) : "";
        } catch (Exception e) {
            return "";
        }
    }

    public static String home(Store s) {
        return s.get("home_city", "Lucknow");
    }
}
