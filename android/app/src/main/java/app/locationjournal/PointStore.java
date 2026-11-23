package app.locationjournal;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;

public final class PointStore {
    private final File file;
    private final Object lock = new Object();

    public PointStore(Context context) {
        file = new File(context.getApplicationContext().getFilesDir(), "points.json");
    }

    public static String hourKey(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        return String.format(
                "%04d-%02d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH),
                calendar.get(Calendar.HOUR_OF_DAY));
    }

    public void saveFix(double lat, double lng, float accuracy, boolean replaceHourly) {
        long t = System.currentTimeMillis();
        synchronized (lock) {
            try {
                JSONArray all = read();
                all.put(point("sample:" + t, t, lat, lng, accuracy, "sample", null));
                String hour = hourKey(t);
                String hourlyId = "hourly:" + hour;
                int existing = indexOf(all, hourlyId);
                if (existing < 0 || replaceHourly) {
                    JSONObject hourly = point(hourlyId, t, lat, lng, accuracy, "hourly", hour);
                    if (existing >= 0) all.put(existing, hourly);
                    else all.put(hourly);
                }
                write(all);
            } catch (Exception ignored) {
                /* Keep the previous log if this fix cannot be stored. */
            }
        }
    }

    public String allJson() {
        synchronized (lock) {
            return read().toString();
        }
    }

    private JSONObject point(String id, long t, double lat, double lng, float accuracy, String kind, String hour) {
        JSONObject object = new JSONObject();
        try {
            object.put("id", id);
            object.put("t", t);
            object.put("lat", lat);
            object.put("lng", lng);
            object.put("accuracy", Math.round(accuracy));
            object.put("kind", kind);
            if (hour != null) object.put("hour", hour);
        } catch (Exception ignored) {
            /* The keys above are constant and cannot fail. */
        }
        return object;
    }

    private int indexOf(JSONArray all, String id) {
        for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i);
            if (item != null && id.equals(item.optString("id"))) return i;
        }
        return -1;
    }

    private JSONArray read() {
        try {
            if (!file.exists()) return new JSONArray();
            FileInputStream input = new FileInputStream(file);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = input.read(chunk)) >= 0) buffer.write(chunk, 0, read);
            input.close();
            String text = buffer.toString(StandardCharsets.UTF_8.name());
            if (text.isBlank()) return new JSONArray();
            return new JSONArray(text);
        } catch (Exception error) {
            return new JSONArray();
        }
    }

    private void write(JSONArray all) {
        try {
            FileOutputStream output = new FileOutputStream(file);
            output.write(all.toString().getBytes(StandardCharsets.UTF_8));
            output.close();
        } catch (Exception ignored) {
            /* A failed write leaves the previous file in place. */
        }
    }
}
