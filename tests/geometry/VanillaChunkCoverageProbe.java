import com.seibel.distanthorizons.common.util.VanillaChunkCoverage;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

public final class VanillaChunkCoverageProbe {
    private static int checks;
    private static void eq(int expected, int actual, String why) {
        checks++; if (expected != actual) throw new AssertionError(why + ": expected " + expected + ", got " + actual);
    }
    private static void yes(boolean actual, String why) {
        checks++; if (!actual) throw new AssertionError(why);
    }
    private static int sample(VanillaChunkCoverage c, int cx, int cz, int configured, VanillaChunkCoverage.ChunkProbe p) {
        return c.getDistance(new Object(), new Object(), 1, cx, cz, configured, p);
    }
    public static void main(String[] args) throws Exception {
        VanillaChunkCoverage c = new VanillaChunkCoverage();
        eq(7, sample(c, 0, 0, 32, (x,z) -> Math.abs(x) <= 9 && Math.abs(z) <= 9), "slider 32, received radius 9");
        eq(9, c.getReceivedRadius(), "observed radius");
        yes(c.getProbeCount() <= 4225, "bounded square 9 probes");
        eq(0, sample(c,0,0,32,(x,z)->x == 0 || z == 0), "cardinal cross is not a loaded square");
        eq(1, sample(c,0,0,32,(x,z)->Math.abs(x)<=9 && Math.abs(z)<=9 && !(x==4 && z==3)), "interior hole wins over cardinal extent");
        eq(2, sample(c,0,0,32,(x,z)->Math.abs(x)<=9 && Math.abs(z)<=9 && !(x==5 && z==0)), "missing edge limits radius");
        eq(0, sample(c,0,0,32,(x,z)->Math.abs(x)<=20 && Math.abs(z)<=1), "long corridor gives no wide handoff");
        eq(0, sample(c,0,0,32,(x,z)->(x==0 && z==0)||(x==100 && z==100)), "isolated distant data cannot enlarge coverage");
        eq(0, sample(c,0,0,32,(x,z)->!(x==0 && z==0)), "missing center");
        eq(7, sample(c,-100,-50,32,(x,z)->Math.abs(x+100)<=9 && Math.abs(z+50)<=9), "negative coordinates");
        eq(0, sample(c,Integer.MAX_VALUE,0,32,(x,z)->true), "positive coordinate overflow refused");
        eq(0, sample(c,Integer.MIN_VALUE,0,32,(x,z)->true), "negative coordinate overflow refused");
        eq(0, sample(c,0,Integer.MAX_VALUE,32,(x,z)->true), "Z overflow refused");
        eq(0, sample(c,0,0,-1,(x,z)->{throw new AssertionError("negative config must not probe");}), "negative config");
        eq(0, sample(c,0,0,0,(x,z)->{throw new AssertionError("zero config must not probe");}), "zero config");
        eq(0, sample(c,0,0,1,(x,z)->true), "one chunk setting");
        eq(0, sample(c,0,0,2,(x,z)->true), "two chunk setting");
        eq(30, sample(c,0,0,Integer.MAX_VALUE,(x,z)->true), "huge config bounded without overflow");
        eq(4225,c.getProbeCount(),"exact maximum one query per square coordinate");
        Set<Long> seen = new HashSet<>();
        eq(30,sample(c,0,0,32,(x,z)->seen.add((((long)x)<<32)^(z&0xffffffffL))),"no corner queried twice");
        eq(4225,seen.size(),"complete bounded square");

        Object world=new Object(), provider=new Object(); final int[] queries={0}; final int[] radius={9};
        VanillaChunkCoverage.ChunkProbe p=(x,z)->{queries[0]++; return Math.abs(x)<=radius[0] && Math.abs(z)<=radius[0];};
        eq(7,c.getDistance(world,provider,1,0,0,32,p),"cache initial sample");
        int first=queries[0];
        eq(7,c.getDistance(world,provider,1,0,0,32,(x,z)->{throw new AssertionError("same key must not probe");}),"cache reuse");
        eq(first,queries[0],"no second lookup");
        radius[0]=4;
        eq(2,c.getDistance(world,provider,2,0,0,32,p),"shrink detected next local tick");
        eq(1,c.getDistance(world,provider,2,1,0,32,p),"same-tick camera change invalidates");
        eq(0,c.getDistance(world,provider,2,1,0,2,p),"same-tick configuration change invalidates");
        eq(7,c.getDistance(new Object(),provider,2,1,0,32,(x,z)->Math.abs(x-1)<=9 && Math.abs(z)<=9),"world identity invalidates");
        eq(0,c.getDistance(world,new Object(),2,1,0,32,(x,z)->false),"provider identity invalidates");
        eq(0,c.getDistance(null,provider,3,0,0,32,p),"null world");
        eq(-1,c.getReceivedRadius(),"null world coverage unknown");
        eq(0,c.getDistance(world,null,3,0,0,32,p),"null provider");
        eq(0,c.getDistance(world,provider,3,0,0,32,null),"null probe");
        try { c.getDistance(world,provider,4,0,0,32,(x,z)->{throw new IllegalStateException("injected lookup failure");}); throw new AssertionError("lookup failure swallowed"); }
        catch(IllegalStateException expected){checks++;}
        eq(0,c.getEffectiveDistance(),"no stale positive distance published on failure");
        eq(0,c.getDistance(world,provider,4,0,0,32,(x,z)->false),"failed sample not cached");

        // Inspect actual retained fields: references are weak and probe is never a field.
        c.getDistance(world,provider,5,0,0,32,(x,z)->true);
        for (Field f : VanillaChunkCoverage.class.getDeclaredFields()) {
            if(java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            Object value=f.get(c);
            yes(value != world && value != provider && !(value instanceof VanillaChunkCoverage.ChunkProbe),"no strong identity/probe field "+f.getName());
        }
        for(String name:new String[]{"world","provider"}) {
            Field f=VanillaChunkCoverage.class.getDeclaredField(name); f.setAccessible(true);
            yes(f.get(c) instanceof WeakReference,"weak "+name);
            ((WeakReference<?>)f.get(c)).clear();
        }
        eq(0,c.getDistance(world,provider,5,0,0,32,(x,z)->false),"cleared weak reference invalidates");
        c.clear(32);
        eq(-1,c.getReceivedRadius(),"explicit clear");
        eq(0,c.getProbeCount(),"clear resets counters");
        System.out.println("VanillaChunkCoverageProbe: PASS " + checks + " assertions");
    }
}
