package ben.smith53.utils;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class AppUtils {

    //log

    private static final String LOG_TAG = "StreamedProxy";

    public static void log(String message) {
        Log.d(LOG_TAG, message);
    }

    public static void log(String message, Throwable error) {
        log("error " + message + ": \n" + Log.getStackTraceString(error));
    }

    //lists & maps

    public static JSONObject mapToJson(Map<String, String> map) {
        return new JSONObject(map);
    }

    public static Map<String, String> jsonToMap(JSONObject json) {
        Map<String, String> map = new HashMap<>();
        try {
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                map.put(key, json.getString(key));
            }
        } catch (JSONException e) {
            AppUtils.log("jsonToMap()", e);
        }
        return map;
    }

    //streams

    private final static int BUFFER_SIZE = 8192;

    public static void copyTo(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer, 0, BUFFER_SIZE)) >= 0) {
            out.write(buffer, 0, read);
        }
    }

}
