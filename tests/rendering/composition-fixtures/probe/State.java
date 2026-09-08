package probe;

import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Proxy;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;

public final class State {
    public static final List<String> events = new ArrayList<>();
    public static int checks, captures, restores, quads, width=64, height=64, mcColor=104;
    public static boolean failRestore, failQuad;
    public static RuntimeException PRIMARY = new RuntimeException("injected render failure");
    public static RuntimeException RESTORE = new RuntimeException("injected restore failure");
    public static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message + "; events=" + events);
    }
    public static Throwable thrown(Runnable work) {
        try { work.run(); } catch(Throwable failure) { return failure; }
        throw new AssertionError("Expected a failure; composition must not silently continue.");
    }
    public static void reset() {
        events.clear(); captures=restores=quads=0; width=height=64; mcColor=104;
        failRestore=failQuad=false;
        PRIMARY=new RuntimeException("injected render failure");
        RESTORE=new RuntimeException("injected restore failure");
    }
    public static void bindMinecraft() {
        IMinecraftRenderWrapper wrapper=(IMinecraftRenderWrapper)Proxy.newProxyInstance(State.class.getClassLoader(), new Class[]{IMinecraftRenderWrapper.class}, (p,m,a)->{
            if(m.getName().equals("getDelayedSetupComplete")) return true;
            if(m.getName().equals("getTargetFramebufferViewportWidth")) return width;
            if(m.getName().equals("getTargetFramebufferViewportHeight")) return height;
            if(m.getName().equals("getGlColorTextureId")) return mcColor;
            Class<?> type=m.getReturnType();
            if(type==boolean.class) return false;
            if(type==int.class) return 0;
            if(type==long.class) return 0L;
            if(type==float.class) return 0f;
            if(type==double.class) return 0d;
            return null;
        });
        SingletonInjector.INSTANCE.bind(IMinecraftRenderWrapper.class,wrapper);
    }
}
