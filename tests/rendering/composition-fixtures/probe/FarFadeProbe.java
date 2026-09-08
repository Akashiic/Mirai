package probe;

import com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer;
import com.seibel.distanthorizons.common.render.openGl.postProcessing.fade.GlDhFarFadeRenderer;
import com.seibel.distanthorizons.core.render.RenderParams;
import java.lang.reflect.Constructor;

public final class FarFadeProbe {
    static GlDhFarFadeRenderer previous;
    static final RenderParams PARAMS=new RenderParams();
    static GlDhFarFadeRenderer fresh() throws Exception {
        Driver.cleanupFailure=null; State.failRestore=false;
        if(previous!=null) previous.free();
        State.reset(); Driver.reset();
        GlDhMetaRenderer.depth=101; GlDhMetaRenderer.color=102; GlDhMetaRenderer.framebuffer=103;
        Constructor<GlDhFarFadeRenderer> constructor=GlDhFarFadeRenderer.class.getDeclaredConstructor();
        constructor.setAccessible(true); previous=constructor.newInstance(); return previous;
    }
    static void noCopyAfterFailure(GlDhFarFadeRenderer renderer, String expected) throws Exception {
        Throwable failure=State.thrown(()->renderer.render(PARAMS));
        State.check(failure.getMessage().contains(expected),"explicit invalid composition input: " + expected);
        State.check(State.quads==0&&State.captures==1&&State.restores==1,"failed pass does not draw and restores state");
        Driver.empty();
        int created=Driver.count("glCreateProgram")+Driver.count("glGenFramebuffers");
        Throwable blocked=State.thrown(()->renderer.render(PARAMS));
        State.check(blocked.getCause()==failure,"prior failure preserved for blocked retry");
        State.check(created==Driver.count("glCreateProgram")+Driver.count("glGenFramebuffers"),"blocked retry does not acquire resources");
    }
    public static void main(String[] args) throws Exception {
        State.bindMinecraft();
        GlDhFarFadeRenderer renderer=fresh();
        if(!args[0].equals("all")) {
            String expected;
            if(args[0].equals("shader-failure")) { Driver.failOperation="glCompileShader"; Driver.failOccurrence=1; expected="injected render failure"; }
            else if(args[0].equals("missing-input")) { GlDhMetaRenderer.depth=-1; expected="DH depth/color"; }
            else if(args[0].equals("invalid-viewport")) { State.width=0; expected="positive viewport"; }
            else if(args[0].equals("incomplete-fbo")) { Driver.framebufferStatus=0x8CD6; expected="incomplete"; }
            else throw new IllegalArgumentException(args[0]);
            noCopyAfterFailure(renderer,expected);
            System.out.println("PASS FarFade " + args[0] + "; checks=" + State.checks + "; exact production classes, declared GL doubles");
            return;
        }
        renderer.render(PARAMS);
        State.check(State.quads==2&&State.restores==1,"compose and apply quads execute exactly once");
        State.check(Driver.count("glCheckFramebufferStatus")==1,"intermediate framebuffer completeness checked");
        renderer.render(PARAMS);
        State.check(Driver.count("glGenFramebuffers")==1&&State.quads==4,"same viewport reuses initialized framebuffer");
        State.width=96; renderer.render(PARAMS);
        State.check(Driver.count("glGenFramebuffers")==2&&Driver.count("glDeleteFramebuffers")==1,"viewport resize replaces owned framebuffer");
        renderer.free(); Driver.empty();

        renderer=fresh(); Driver.framebufferStatus=0x8CD6; noCopyAfterFailure(renderer,"incomplete");
        for(int value:new int[]{0,-1}) {
            renderer=fresh(); State.width=value; noCopyAfterFailure(renderer,"positive viewport");
            renderer=fresh(); State.height=value; noCopyAfterFailure(renderer,"positive viewport");
            renderer=fresh(); GlDhMetaRenderer.framebuffer=value; noCopyAfterFailure(renderer,"destination framebuffer");
            renderer=fresh(); GlDhMetaRenderer.depth=value; noCopyAfterFailure(renderer,"DH depth/color");
            renderer=fresh(); GlDhMetaRenderer.color=value; noCopyAfterFailure(renderer,"DH depth/color");
            renderer=fresh(); State.mcColor=value; noCopyAfterFailure(renderer,"DH depth/color");
        }
        for(String operation:new String[]{"glCreateProgram","glCompileShader","glGenFramebuffers","glTexImage2D","glFramebufferTexture2D","glCheckFramebufferStatus"}) {
            final GlDhFarFadeRenderer pass=fresh(); Driver.failOperation=operation; Driver.failOccurrence=1;
            Throwable failure=State.thrown(()->pass.render(PARAMS));
            State.check(failure==State.PRIMARY,"original failure propagated from " + operation);
            State.check(State.quads==0&&State.restores==1,"failure aborts before drawing and restores"); Driver.empty();
        }
        for(String operation:new String[]{"glCreateProgram","glGenFramebuffers","glGenTextures"}) {
            final GlDhFarFadeRenderer pass=fresh(); Driver.zeroOperation=operation;
            State.thrown(()->pass.render(PARAMS)); State.check(State.quads==0,"zero handle blocks drawing: " + operation); Driver.empty();
        }
        final GlDhFarFadeRenderer pass=fresh(); Driver.failOperation="glTexImage2D"; Driver.failOccurrence=1; Driver.cleanupFailure="glDeleteFramebuffers"; State.failRestore=true;
        Throwable failure=State.thrown(()->pass.render(PARAMS));
        State.check(failure==State.PRIMARY&&failure.getSuppressed().length>=1,"allocation failure survives cleanup and restore errors");
        State.check(Driver.count("glDeleteFramebuffers")==1&&Driver.count("glDeleteTextures")==1,"all owned target cleanup attempted"); Driver.empty();
        final GlDhFarFadeRenderer drawFailure=fresh(); State.failQuad=true;
        failure=State.thrown(()->drawFailure.render(PARAMS));
        State.check(failure==State.PRIMARY&&State.quads==1,"failed composition quad prevents apply quad"); Driver.empty();

        // Full production LodRenderer -> FarFadeRenderer boundary with the same GL failure.
        renderer=fresh();
        LodPassProbe.main(new String[]{"absent"});
        LodPassProbe.installActualCompositor(renderer); Driver.framebufferStatus=0x8CD6;
        failure=State.thrown(LodPassProbe::draw);
        State.check(failure.getMessage().contains("incomplete"),"actual renderer propagates incomplete framebuffer");
        State.check(!State.events.contains("copy")&&!State.events.contains("cleanup")&&!State.events.contains("apply-event"),"actual renderer never applies uncomposed texture");
        State.check(State.captures==2&&State.restores==2,"inner and outer state scopes restored"); Driver.empty();
        renderer.free();
        System.out.println("PASS FarFade actual renderer/shader/programs + complete LodRenderer boundary; checks=" + State.checks + "; explicit GL/memory/state/quad doubles, no GPU");
    }
}
