package probe;

import java.lang.reflect.*;
import java.util.*;
import com.seibel.distanthorizons.lwjgl.ILWJGLService;

/** Deliberate driver double. Allocated handles and failed calls are observable. */
public final class Driver implements InvocationHandler {
    public static final Map<String,Integer> calls=new HashMap<>();
    public static final Set<Integer> frames=new HashSet<>(), textures=new HashSet<>(), shaders=new HashSet<>(), programs=new HashSet<>();
    public static String failOperation, cleanupFailure, zeroOperation;
    public static int failOccurrence, framebufferStatus=0x8CD5, next=200;
    public static ILWJGLService create() {
        return (ILWJGLService)Proxy.newProxyInstance(Driver.class.getClassLoader(),new Class[]{ILWJGLService.class},new Driver());
    }
    public static void reset() {
        calls.clear(); frames.clear(); textures.clear(); shaders.clear(); programs.clear();
        failOperation=cleanupFailure=zeroOperation=null; failOccurrence=0; framebufferStatus=0x8CD5; next=200;
    }
    public static int count(String name) { return calls.getOrDefault(name,0); }
    public static void empty() {
        State.check(frames.isEmpty() && textures.isEmpty() && shaders.isEmpty() && programs.isEmpty(), "owned mock handles released");
    }
    public Object invoke(Object proxy, Method method, Object[] args) {
        String name=method.getName(); int occurrence=count(name)+1; calls.put(name,occurrence);
        if(name.equals(failOperation) && occurrence==failOccurrence) throw State.PRIMARY;
        if(name.equals("glDeleteFramebuffers")) frames.remove((Integer)args[0]);
        if(name.equals("glDeleteTextures")) textures.remove((Integer)args[0]);
        if(name.equals("glDeleteShader")) shaders.remove((Integer)args[0]);
        if(name.equals("glDeleteProgram")) programs.remove((Integer)args[0]);
        if(name.equals(cleanupFailure)) throw State.RESTORE;
        Set<Integer> target=name.equals("glGenFramebuffers")?frames:name.equals("glGenTextures")?textures:name.equals("glCreateShader")?shaders:name.equals("glCreateProgram")?programs:null;
        if(target!=null) { if(name.equals(zeroOperation)) return 0; int id=next++; target.add(id); return id; }
        if(name.equals("glCheckFramebufferStatus")) return framebufferStatus;
        Class<?> type=method.getReturnType();
        if(type==boolean.class) return false;
        if(type==int.class) return 1;
        if(type==long.class) return 0L;
        if(type==float.class) return 0f;
        if(type==double.class) return 0d;
        if(type==String.class) return "fake compiler status";
        return null;
    }
}
