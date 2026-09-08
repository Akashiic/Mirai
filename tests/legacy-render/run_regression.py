#!/usr/bin/env python3
"""Run real legacy mixin method bodies with event/render test doubles.

No Mixin transformation, Minecraft launch, native OpenGL or GPU is exercised.
Use --source-root to check the unmodified pinned baseline with the same harness.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
PREFIX = 'com.seibel.distanthorizons.'


def run(jdk, source_root, case):
    with tempfile.TemporaryDirectory(prefix='akashic-render-test-') as directory:
        work = Path(directory)
        src, classes = work/'src', work/'classes'

        def write(name, body):
            package = name.rsplit('.', 1)[0]
            path = src/(name.replace('.', '/')+'.java')
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('package '+package+';\n'+body)

        name = PREFIX+'forge17.mixin.MixinEntityRenderer'
        real_file = source_root/'forge17/src/main/java'/Path(name.replace('.', '/')+'.java')
        target = src/Path(name.replace('.', '/')+'.java')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(real_file.read_text())
        write('test.State', '''public class State {
          public static final java.util.List<String> sequence = new java.util.ArrayList<>();
          public static void require(boolean result, String message) { if (!result) throw new AssertionError(message); }
        }''')
        write('org.spongepowered.asm.mixin.Mixin', 'public @interface Mixin { Class<?>[] value(); }')
        write('org.spongepowered.asm.mixin.Unique', 'public @interface Unique {}')
        write('org.spongepowered.asm.mixin.gen.Accessor', 'public @interface Accessor {}')
        write('org.spongepowered.asm.mixin.injection.At', '''public @interface At {
          String value(); String target() default ""; boolean remap() default true; int ordinal() default -1;
        }''')
        for name in ['Inject', 'Redirect']:
            write('org.spongepowered.asm.mixin.injection.'+name, 'public @interface '+name+' { String method(); At at(); }')
        write('org.spongepowered.asm.mixin.injection.callback.CallbackInfo', 'public class CallbackInfo {}')
        write('com.llamalad7.mixinextras.sugar.Local', 'public @interface Local { boolean argsOnly() default false; }')
        write('net.minecraft.client.renderer.EntityRenderer', 'public class EntityRenderer {}')
        write('net.minecraft.client.renderer.texture.DynamicTexture', 'public class DynamicTexture {}')
        write(PREFIX+'forge17.ForgeMain', 'public class ForgeMain {}')
        write(PREFIX+'core.wrapperInterfaces.modAccessor.IRpleAccessor', 'public interface IRpleAccessor { void updateLightmap(); }')
        write(PREFIX+'core.dependencyInjection.ModAccessorInjector', '''public class ModAccessorInjector {
          public static final ModAccessorInjector INSTANCE = new ModAccessorInjector();
          private final java.util.Map<Class<?>, Object> values = new java.util.HashMap<>();
          public <T> T get(Class<T> type) { return type.cast(values.get(type)); }
          public <T> void bind(Class<T> type, T value) { values.put(type, value); }
        }''')
        write(PREFIX+'forge17.RenderHelper', '''public class RenderHelper {
          public static void enableFog() { test.State.sequence.add("enable"); }
          public static void disableFogDuringSetup() { test.State.sequence.add("fog"); }
          public static void disableFogDuringRender(int cap) { test.State.sequence.add("render:"+cap); }
        }''')
        write('cpw.mods.fml.common.eventhandler.Event', 'public class Event { public boolean cancelled; }')
        write('cpw.mods.fml.common.eventhandler.EventBus', '''public class EventBus {
          public int calls; public Event expected; public java.util.function.Consumer<Event> listener;
          public boolean post(Event event) {
            calls++; test.State.require(event==expected, "event identity changed"); test.State.sequence.add("post");
            if (listener!=null) listener.accept(event); return event.cancelled;
          }
        }''')
        write('test.Harness', '''import java.lang.reflect.Method;
          import java.lang.reflect.InvocationTargetException;
          import com.seibel.distanthorizons.forge17.mixin.MixinEntityRenderer;
          import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
          import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IRpleAccessor;
          import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
          import cpw.mods.fml.common.eventhandler.Event;
          import cpw.mods.fml.common.eventhandler.EventBus;
          public class Harness {
            private static final class Renderer extends MixinEntityRenderer {}
            public static void main(String[] args) throws Exception {
              // Matches vanilla constructing EntityRenderer before FML.initializeMods.
              Renderer renderer = new Renderer();
              Method fog=MixinEntityRenderer.class.getDeclaredMethod("disableFogDuringSetup", EventBus.class, Event.class, int.class);
              Method lightmap=MixinEntityRenderer.class.getDeclaredMethod("updateLightmap", float.class, CallbackInfo.class);
              fog.setAccessible(true); lightmap.setAccessible(true);
              if (args.length==0 || args[0].equals("fog")) {
                for (int mode : new int[] {-1, 0}) {
                  for (boolean cancel : new boolean[] {false, true}) {
                    State.sequence.clear();
                    Event event=new Event();
                    EventBus bus=new EventBus(); bus.expected=event;
                    bus.listener=e -> { State.sequence.add("listener"); e.cancelled=cancel; };
                    boolean result=(Boolean)fog.invoke(renderer, bus, event, mode);
                    State.require(bus.calls==1, "event listener was not posted exactly once");
                    State.require(result==cancel, "listener cancellation result was discarded");
                    java.util.List<String> expected = mode==-1 ? java.util.Arrays.asList("post", "listener") : java.util.Arrays.asList("post", "listener", "fog");
                    State.require(State.sequence.equals(expected), "fog helper/listener order changed: "+State.sequence);
                  }
                }
                State.sequence.clear();
                Event event=new Event(); EventBus bus=new EventBus(); bus.expected=event;
                RuntimeException failure=new RuntimeException("listener failed"); bus.listener=e -> { throw failure; };
                try { fog.invoke(renderer,bus,event,0); throw new AssertionError("listener exception swallowed"); }
                catch (InvocationTargetException thrown) { State.require(thrown.getCause()==failure, "exception identity changed"); }
                State.require(bus.calls==1 && State.sequence.equals(java.util.Arrays.asList("post")), "fog ran after failed listener");
                System.out.println("PASS fog: sky/terrain, cancellation false/true, listener once, call order, listener exception");
              }
              if (args.length==0 || args[0].equals("rple")) {
                lightmap.invoke(renderer, 0.0f, new CallbackInfo()); // absent integration is valid
                int[] updates={0}; IRpleAccessor accessor=() -> updates[0]++;
                ModAccessorInjector.INSTANCE.bind(IRpleAccessor.class,accessor); // only now does FML register RPLE
                lightmap.invoke(renderer, 0.0f, new CallbackInfo());
                State.require(updates[0]==1, "RPLE remained null after its late registration");
                lightmap.invoke(renderer, 0.5f, new CallbackInfo());
                State.require(updates[0]==2, "RPLE did not receive one update per render callback");
                System.out.println("PASS rple: renderer constructed before registration, optional absence, late updates");
              }
            }
          }''')
        classes.mkdir()
        version=subprocess.run([str(jdk/'bin/javac'), '-version'], capture_output=True, text=True, check=True)
        target=['-source','8','-target','8'] if '1.8.' in (version.stdout+version.stderr) else ['--release','8','-Xlint:-options']
        subprocess.run([str(jdk/'bin/javac'), *target, '-d', str(classes), *map(str, sorted(src.rglob('*.java')))], check=True)
        selected = [] if case == 'all' else [case]
        subprocess.run([str(jdk/'bin/java'), '-Xverify:all', '-cp', str(classes), 'test.Harness', *selected], check=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', required=True, type=Path)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--case', choices=['all', 'fog', 'rple'], default='all')
    options=parser.parse_args()
    run(options.jdk.resolve(), options.source_root.resolve(), options.case)
