package app.hushmessenger.extension;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

/** Diagnostics only (the debug tray dump patch): logs every field of each attachment entering a chat's tray. */
public final class TrayDump {
    private static final String TAG = "HushTrayDump";

    private TrayDump() {}

    public static void dump(Object single, List<?> items) {
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
