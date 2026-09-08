import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.util.List;

/** Uses real JARs in a fresh, isolated class loader; no Minecraft/GL mocks. */
public final class FastutilCollisionProbe {
    private static String origin(Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        return source == null ? "unknown" : source.getLocation().toExternalForm();
    }
    @SuppressWarnings("unchecked")
    private static void checkExternalCoexistence(ClassLoader loader, String phase) throws Exception {
        Class<?> arrays = Class.forName("it.unimi.dsi.fastutil.objects.ObjectArrayList", true, loader);
        Class<?> immutable = Class.forName("it.unimi.dsi.fastutil.objects.ObjectImmutableList", true, loader);
        System.out.println("COEXIST_" + phase + "_ARRAY_ORIGIN=" + origin(arrays));
        System.out.println("COEXIST_" + phase + "_IMMUTABLE_ORIGIN=" + origin(immutable));
        List<Object> list = (List<Object>) arrays.getDeclaredConstructor().newInstance();
        list.add("modern");
        List<?> value = (List<?>) immutable.getConstructor(java.util.Collection.class).newInstance(list);
        if (value.size() != 1 || !value.get(0).equals("modern"))
            throw new AssertionError("external coexistence contract " + phase);
        try { value.clear(); throw new AssertionError("external immutable modified " + phase); }
        catch (UnsupportedOperationException expected) { }
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("<mode:external|isolated> <jar1> [jarN ...]");
        if (!args[0].equals("external") && !args[0].equals("isolated"))
            throw new IllegalArgumentException("Unknown mode: " + args[0]);
        URL[] urls = new URL[args.length - 1];
        for (int i = 1; i < args.length; i++) urls[i - 1] = new File(args[i]).toURI().toURL();
        try (URLClassLoader loader = new URLClassLoader(urls, null)) {
            boolean coexistence = args[0].equals("isolated") && urls.length > 1;
            if (coexistence) checkExternalCoexistence(loader, "BEFORE");
            String prefix = args[0].equals("isolated")
                    ? "DistantHorizons.libraries.unimi.dsi.fastutil."
                    : "it.unimi.dsi.fastutil.";
            Class<?> arrays = Class.forName(prefix + "objects.ObjectArrayList", true, loader);
            System.out.println("ARRAY_ORIGIN=" + origin(arrays));
            List<Object> list = (List<Object>) arrays.getDeclaredConstructor().newInstance();
            list.add("mirai"); list.add("horizons");
            if (list.size() != 2 || !list.get(1).equals("horizons")) throw new AssertionError("list contract");
            if (args[0].equals("isolated")) {
                Class<?> longs = Class.forName(prefix + "longs.LongArrayList", true, loader);
                System.out.println("LONG_ARRAY_ORIGIN=" + origin(longs));
                Object values = longs.getDeclaredConstructor().newInstance();
                Method add = longs.getMethod("add", long.class);
                add.invoke(values, Long.MIN_VALUE); add.invoke(values, Long.MAX_VALUE);
                if (!Long.valueOf(Long.MIN_VALUE).equals(longs.getMethod("getLong", int.class).invoke(values, 0)))
                    throw new AssertionError("primitive list contract");
                if (!Long.valueOf(Long.MAX_VALUE).equals(longs.getMethod("getLong", int.class).invoke(values, 1)))
                    throw new AssertionError("primitive list contract");
            } else {
                Class<?> immutable = Class.forName(prefix + "objects.ObjectImmutableList", true, loader);
                System.out.println("IMMUTABLE_ORIGIN=" + origin(immutable));
                List<?> value = (List<?>) immutable.getConstructor(java.util.Collection.class).newInstance(list);
                if (value.size() != 2 || !value.get(0).equals("mirai")) throw new AssertionError("immutable contract");
                try { value.clear(); throw new AssertionError("immutable modified"); }
                catch (UnsupportedOperationException expected) { }
            }
            if (coexistence) checkExternalCoexistence(loader, "AFTER");
            System.out.println("PASS " + args[0]);
        }
    }
}
