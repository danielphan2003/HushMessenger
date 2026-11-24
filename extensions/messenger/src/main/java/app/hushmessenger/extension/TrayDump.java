package app.hushmessenger.extension;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

/** Diagnostics only (the debug tray dump patch): logs every field of each attachment entering a chat's tray. */
public final class TrayDump {
    private static final String TAG = "HushTrayDump";

    private TrayDump() {}

    private static boolean copied;

    /** Debug: copy Meta's decompressed native library where adb can read it, once per process. */
    private static void copyNativeLibrary() {
        if (copied) return;
        copied = true;
        try {
            android.content.Context context = Settings.appContext;
            if (context == null) return;
            java.io.File out = context.getExternalFilesDir(null);
            java.io.File dir = new java.io.File(context.getApplicationInfo().dataDir, "lib-compressed");
            String[] names = dir.list();
            android.util.Log.w(TAG, "lib-compressed: " + (names == null ? "none" : java.util.Arrays.toString(names)));
            for (String name : new String[] {"libstartup.so"}) {
                java.io.File source = new java.io.File(dir, name);
                if (!source.isFile()) continue;
                java.io.File target = new java.io.File(out, name);
                try (java.io.InputStream in = new java.io.FileInputStream(source); java.io.OutputStream o = new java.io.FileOutputStream(target)) {
                    byte[] buffer = new byte[1 << 16];
                    for (int n; (n = in.read(buffer)) > 0; ) o.write(buffer, 0, n);
                }
                android.util.Log.w(TAG, "copied " + name + " (" + source.length() + " bytes) to " + target);
            }
        } catch (Throwable error) {
            android.util.Log.w(TAG, "native library copy failed: " + error);
        }
    }

    /** Debug: every value of the send source's two enums, with their analytics names. */
    private static void sendSourceValues(Object media) {
        try {
            Object source = field(media.getClass(), "A0Z").get(media);
            for (String name : new String[] {"A00", "A01"}) {
                Object value = field(source.getClass(), name).get(source);
                Class<?> type = value.getClass();
                if (!type.isEnum() && type.getSuperclass() != null && type.getSuperclass().isEnum()) type = type.getSuperclass();
                StringBuilder all = new StringBuilder();
                for (Object constant : type.getEnumConstants()) {
                    String analytics;
                    try { analytics = String.valueOf(field(type, "analyticsName").get(constant)); } catch (Throwable e) { analytics = "?"; }
                    all.append(((Enum<?>) constant).name()).append('=').append(analytics).append(", ");
                }
                for (int i = 0; i < all.length(); i += 900) android.util.Log.w(TAG, "source " + name + " values: " + all.substring(i, Math.min(all.length(), i + 900)));
            }
        } catch (Throwable error) {
            android.util.Log.w(TAG, "send source values failed: " + error);
        }
    }

    public static void dump(Object single, List<?> items) {
        copyNativeLibrary();
        if (items != null && !items.isEmpty() && items.get(0) != null) sendSourceValues(items.get(0));
        try {
            android.util.Log.w(TAG, "insert: single=" + (single == null ? "null" : single.getClass().getName())
                    + " items=" + (items == null ? "null" : items.size()) + " from=" + caller());
            if (single != null) fields("single", single);
            if (items != null) for (int i = 0; i < items.size(); i++) fields("item" + i, items.get(i));
        } catch (Throwable error) {
            android.util.Log.w(TAG, "dump failed: " + error);
        }
    }

    /**
     * Debug experiment for 347415355/6 only: the gallery pick's MediaResource carries width (A04), height (A00) and file
     * size (A09) as 0. Fill them from the file it points at (A0H), the way Messenger's own gallery fills them.
     */
    public static void fillSize(Object media) {
        try {
            Class<?> type = media.getClass();
            Object uri = field(type, "A0H").get(media);
            String path = uri == null ? null : ((android.net.Uri) uri).getPath();
            if (path == null) { android.util.Log.w(TAG, "fillSize: no file"); return; }
            java.io.File file = new java.io.File(path);
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, bounds);
            int width = bounds.outWidth, height = bounds.outHeight;
            long size = file.length();
            android.util.Log.w(TAG, "fillSize: " + width + "x" + height + ", " + size + " bytes; before A04=" + field(type, "A04").getInt(media)
                    + " A00=" + field(type, "A00").getInt(media) + " A09=" + field(type, "A09").getLong(media));
            if (width > 0 && height > 0) {
                field(type, "A04").setInt(media, width);
                field(type, "A00").setInt(media, height);
            }
            if (size > 0) field(type, "A09").setLong(media, size);
        } catch (Throwable error) {
            android.util.Log.w(TAG, "fillSize failed: " + error);
        }
    }

    private static volatile Object session, threadKey;

    /** Debug: the pick handler's session and chat, for the pre-upload experiment. */
    public static void remember(Object fbSession, Object key) {
        session = fbSession;
        threadKey = key;
        android.util.Log.w(TAG, "remember: session=" + (fbSession == null ? "null" : fbSession.getClass().getName()) + " key=" + key);
    }

    /**
     * Debug experiment for 347415355: start Messenger's own pre-upload for the pick in an encrypted chat, the way the
     * editor (UxJ.A02) and the share sheet (PVv.run) do: ((4dA) 1AY.A06(session, 65582)).A0W(height, width, path, true, hd).
     */
    public static void preUpload(Object media) {
        try {
            Object key = threadKey, user = session;
            if (key == null || user == null) { android.util.Log.w(TAG, "preUpload: no session or chat"); return; }
            StringBuilder checks = new StringBuilder();
            for (java.lang.reflect.Method m : key.getClass().getMethods()) {
                if (m.getParameterTypes().length != 0 || m.getReturnType() != boolean.class || !m.getName().startsWith("A")) continue;
                try { checks.append(m.getName()).append('=').append(m.invoke(key)).append(' '); } catch (Throwable ignored) {}
            }
            android.util.Log.w(TAG, "preUpload: thread key checks: " + checks);
            // debug.6: pre-upload whatever the key type, since sends here go through the encrypted twin thread.
            Class<?> type = media.getClass();
            int height = field(type, "A00").getInt(media), width = field(type, "A04").getInt(media);
            String path = ((android.net.Uri) field(type, "A0H").get(media)).getPath();
            java.lang.reflect.Method lookup = null;
            for (java.lang.reflect.Method m : Class.forName("X.1AY").getDeclaredMethods())
                if (m.getName().equals("A06") && m.getParameterTypes().length == 2 && m.getParameterTypes()[1] == int.class) lookup = m;
            if (lookup == null) { android.util.Log.w(TAG, "preUpload: no 1AY.A06"); return; }
            lookup.setAccessible(true);
            Object uploader = lookup.invoke(null, user, 65582);
            java.lang.reflect.Method upload = null;
            for (java.lang.reflect.Method m : uploader.getClass().getDeclaredMethods())
                if (m.getName().equals("A0W") && m.getParameterTypes().length == 5) upload = m;
            if (upload == null) { android.util.Log.w(TAG, "preUpload: no A0W on " + uploader.getClass().getName()); return; }
            upload.setAccessible(true);
            upload.invoke(uploader, Integer.valueOf(height), Integer.valueOf(width), path, true, false);
            android.util.Log.w(TAG, "preUpload: started " + width + "x" + height + " " + path);
        } catch (Throwable error) {
            android.util.Log.w(TAG, "preUpload failed: " + error + (error.getCause() == null ? "" : " / " + error.getCause()));
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static String caller() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        StringBuilder out = new StringBuilder();
        for (int i = 2; i < Math.min(stack.length, 8); i++) out.append(stack[i].getClassName()).append('.').append(stack[i].getMethodName()).append(' ');
        return out.toString().trim();
    }

    private static void fields(String label, Object object) {
        if (object == null) { android.util.Log.w(TAG, label + " = null"); return; }
        for (Class<?> type = object.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                String value;
                try {
                    field.setAccessible(true);
                    Object v = field.get(object);
                    value = v == null ? "null" : v.getClass().getSimpleName() + ":" + String.valueOf(v);
                } catch (Throwable error) {
                    value = "<" + error.getClass().getSimpleName() + ">";
                }
                if (value.length() > 300) value = value.substring(0, 300) + "...";
                android.util.Log.w(TAG, label + " " + type.getSimpleName() + "." + field.getName() + " (" + field.getType().getSimpleName() + ") = " + value);
            }
        }
    }
}
