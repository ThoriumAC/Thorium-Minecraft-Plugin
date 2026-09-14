package ac.thorium.mc.plugin.compat;

import java.lang.reflect.Method;

public final class Reflect {
    private Reflect() {}

    public static boolean classPresent(String className) {
        try { Class.forName(className); return true; } catch (Throwable t) { return false; }
    }

    public static Method method(String className, String name, Class<?>... params) {
        try { return method(Class.forName(className), name, params); } catch (Throwable t) { return null; }
    }

    public static Method method(Class<?> cls, String name, Class<?>... params) {
        if (cls == null) return null;
        try { Method m = cls.getMethod(name, params); m.setAccessible(true); return m; } catch (Throwable t) { return null; }
    }

    public static Object invoke(Method m, Object target, Object... args) {
        if (m == null) return null;
        try { return m.invoke(target, args); } catch (Throwable t) { return null; }
    }

    public static boolean bool(Method m, Object target, boolean dflt) {
        Object v = invoke(m, target);
        return v instanceof Boolean ? (Boolean) v : dflt;
    }
}
