package io.github.slebed.gdxvulkan;

/**
 * Reads fields of other libraries' classes without allocating. {@code Field.get} and friends allocate on every call on JDK 18 and later (reflection
 * is built on method handles), which matters for fields read once per object per frame, such as stock {@code Mesh}'s private data. A field is looked up
 * once with {@link #id}; reads through the id are a JNI call each and allocate nothing, on the desktop and on Android alike.
 */
public final class FieldAccess {
    private FieldAccess() {}

    /** @param signature a JNI field descriptor such as {@code "I"} (int), {@code "Z"} (boolean) or {@code "Ljava/nio/ByteBuffer;"} @return the id, 0 if there is no such field */
    public static long id(Class<?> type, String name, String signature) {
        GdxVk.loadNatives();
        return GdxVkNative.nativeFieldId(type, name, signature);
    }

    public static int getInt(Object o, long id) {
        return GdxVkNative.nativeGetIntField(o, id);
    }

    public static boolean getBoolean(Object o, long id) {
        return GdxVkNative.nativeGetBooleanField(o, id);
    }

    public static void setBoolean(Object o, long id, boolean value) {
        GdxVkNative.nativeSetBooleanField(o, id, value);
    }

    public static Object getObject(Object o, long id) {
        return GdxVkNative.nativeGetObjectField(o, id);
    }
}
