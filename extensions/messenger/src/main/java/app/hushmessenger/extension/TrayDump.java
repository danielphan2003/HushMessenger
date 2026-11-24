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
