package probe;

import java.lang.reflect.Field;
import java.util.Arrays;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiTransparency;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.*;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.*;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.render.*;
import com.seibel.distanthorizons.core.render.renderer.LodRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.*;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;

public final class LodPassProbe {
    static boolean active, flipAfterRead, failFar, cancelApply, cancelFog, cancelClear, failSetup;
    static int reads;
    static final RenderParams PARAMS=new RenderParams();
    static final IProfilerWrapper PROFILER=new IProfilerWrapper() {
        public IProfileBlock push(String name) { return () -> {}; }
        public void popPush(String name) { }
    };
    static final class Meta implements IDhMetaRenderer {
        public RenderPassState captureRenderPassState() {
            State.events.add("capture"); State.captures++;
            return () -> { State.events.add("restore"); State.restores++; if(State.failRestore) throw State.RESTORE; };
        }
        public void runRenderPassSetup(RenderParams p) { State.events.add("setup"); if(failSetup) throw State.PRIMARY; }
        public void runRenderPassCleanup(RenderParams p) { State.events.add("cleanup"); }
        public void copyToMcTexture(RenderParams p) { State.events.add("copy"); }
        public void clearDhDepthAndColorTextures(RenderParams p) { State.events.add("clear"); }
    }
    static void field(String name,Object value) throws Exception {
        Field field=LodRenderer.class.getDeclaredField(name); field.setAccessible(true); field.set(LodRenderer.INSTANCE,value);
    }
    static void reset() {
        State.reset(); reads=0; flipAfterRead=failFar=cancelApply=cancelFog=cancelClear=failSetup=false;
        Config.Client.Advanced.Graphics.enableAntiAliasing.setWithoutFiringEvents(true);
        DhApiRenderProxy.INSTANCE.setDeferTransparentRendering(false);
    }
    static void draw() { LodRenderer.INSTANCE.render(PARAMS,PROFILER); }
    public static void installActualCompositor(IDhFarFadeRenderer compositor) throws Exception {
        reset(); active=false; field("farFadeRenderer",compositor);
    }
    static void sequence(String... expected) { State.check(State.events.equals(Arrays.asList(expected)),"exact render order expected " + Arrays.toString(expected)); }
    public static void main(String[] args) throws Exception {
        boolean present=args[0].equals("present");
        if(present) ModAccessorInjector.INSTANCE.bind(IIrisAccessor.class,new IIrisAccessor() {
            public String getModName() { return "test Angelica"; }
            public boolean isShaderPackInUse() { reads++; boolean result=active; if(flipAfterRead) active=!active; return result; }
            public boolean isRenderingShadowPass() { return false; }
        });
        field("renderersBound",true); field("vanillaSettingsOverridden",true); field("metaRenderer",new Meta());
        field("terrainRenderer",(IDhTerrainRenderer)(p,o,b,f)->State.events.add(o?"opaque":"transparent"));
        field("fogRenderer",(IDhFogRenderer)(p,f)->State.events.add("fog"));
        field("farFadeRenderer",(IDhFarFadeRenderer)p->{ State.events.add("compose"); if(failFar) throw State.PRIMARY; });
        field("antiAliasRenderer",(IDhAntiAliasRenderer)p->State.events.add("aa"));
        Config.Client.Advanced.Graphics.GenericRendering.enableGenericRendering.setWithoutFiringEvents(false);
        Config.Client.Advanced.Graphics.enableSsao.setWithoutFiringEvents(false);
        Config.Client.Advanced.Graphics.Fog.enableDhFog.setWithoutFiringEvents(true);
        Config.Client.Advanced.Graphics.Quality.transparency.setWithoutFiringEvents(EDhApiTransparency.COMPLETE);
        PARAMS.hasBeenValidated=true; PARAMS.renderBufferHandler=new RenderBufferHandler();
        ApiEventInjector.INSTANCE.bind(DhApiBeforeTextureClearEvent.class,new DhApiBeforeTextureClearEvent(){ public void beforeClear(DhApiCancelableEventParam<DhApiRenderParam> e){State.events.add("clear-event");if(cancelClear)e.cancelEvent();} });
        ApiEventInjector.INSTANCE.bind(DhApiBeforeFogRenderEvent.class,new DhApiBeforeFogRenderEvent(){ public void beforeRender(DhApiCancelableEventParam<EventParam> e){State.events.add("fog-event");if(cancelFog)e.cancelEvent();} });
        ApiEventInjector.INSTANCE.bind(DhApiBeforeApplyShaderRenderEvent.class,new DhApiBeforeApplyShaderRenderEvent(){ public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> e){State.events.add("apply-event");if(cancelApply)e.cancelEvent();} });
        reset(); active=false; draw();
        sequence("capture","setup","sort","clear-event","clear","opaque","transparent","fog-event","fog","compose","aa","apply-event","copy","cleanup","restore");
        if(present) State.check(reads==1,"one activity read in main pass");
        reset(); active=false; Config.Client.Advanced.Graphics.enableAntiAliasing.setWithoutFiringEvents(false); draw();
        State.check(State.events.contains("compose")&&!State.events.contains("aa")&&State.events.contains("copy"),"composition retained with AA off");
        reset(); active=false; cancelApply=cancelFog=cancelClear=true; draw();
        sequence("capture","setup","sort","clear-event","opaque","transparent","fog-event","compose","aa","apply-event","cleanup","restore");
        reset(); active=false; failFar=true; State.failRestore=true;
        Throwable failure=State.thrown(LodPassProbe::draw);
        State.check(failure==State.PRIMARY&&failure.getSuppressed().length==1&&failure.getSuppressed()[0]==State.RESTORE,"failure and restore suppression preserved");
        State.check(!State.events.contains("copy")&&!State.events.contains("cleanup")&&State.restores==1,"failed composition cannot copy; scope restored once");
        reset(); failSetup=true; failure=State.thrown(LodPassProbe::draw);
        State.check(failure==State.PRIMARY&&State.restores==1&&!State.events.contains("copy"),"partial setup aborts apply and restores");
        reset(); active=false; DhApiRenderProxy.INSTANCE.setDeferTransparentRendering(true); draw();
        State.check(!State.events.contains("transparent")&&State.events.contains("compose"),"opaque deferred first pass remains separate");
        State.events.clear(); reads=0; LodRenderer.INSTANCE.renderDeferred(PARAMS,PROFILER);
        sequence("capture","setup","transparent","fog-event","fog","cleanup","restore");
        State.check(reads==0,"deferred pass has no composition activity query");
        reset(); LodRenderer.INSTANCE.renderDeferred(PARAMS,PROFILER); State.check(State.events.isEmpty(),"unrequested deferred pass skipped");
        if(present) {
            reset(); active=true; cancelApply=true; DhApiRenderProxy.INSTANCE.setDeferTransparentRendering(true); draw();
            sequence("capture","setup","sort","clear-event","clear","opaque","fog-event","fog","apply-event","cleanup","restore");
            State.check(reads==1,"active pack queried once");
            reset(); active=false; flipAfterRead=true; draw();
            State.check(reads==1&&State.events.contains("compose")&&State.events.contains("aa"),"pack result frozen across composition and AA");
            reset(); active=true; cancelApply=true; draw(); State.check(!State.events.contains("compose")&&!State.events.contains("aa"),"pack enabled next pass");
            reset(); active=false; draw(); State.check(State.events.contains("compose")&&State.events.contains("aa")&&State.events.contains("copy"),"pack disabled next pass restores composition");
        }
        System.out.println("PASS LodRenderer " + args[0] + "; checks=" + State.checks + "; actual class, explicit render/event doubles, no GPU");
    }
}
