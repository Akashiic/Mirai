#!/usr/bin/env python3
"""Compile the real Forge 1.7.10 initializer against narrow lifecycle test doubles.

This checks registration order, duplicate calls, physical-side isolation and the
Hodgepodge capability contract. It is not a Minecraft/Forge/Mixin runtime test.
Requires a JDK 8+ via --jdk (both javac and java); no downloaded dependencies.
"""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
PREFIX = 'com.seibel.distanthorizons.'


def run(jdk, source_root):
    with tempfile.TemporaryDirectory(prefix='akashic-compat-test-') as directory:
        work = Path(directory)
        src, classes = work / 'src', work / 'classes'

        def write(name, body):
            package, simple = name.rsplit('.', 1)
            path = src / (name.replace('.', '/') + '.java')
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('package ' + package + ';\n' + body)

        def copy(relative):
            text = (source_root / relative).read_text()
            # This harness exclusively targets 1.7.10; its interface has one
            # MC_VER == MC_1_7_10 block and no alternate implementation.
            text = '\n'.join(line for line in text.splitlines() if not line.strip().startswith('#'))
            package = text.split('package ', 1)[1].split(';', 1)[0]
            path = src / package.replace('.', '/') / Path(relative).name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)

        for relative in [
            'forge17/src/main/java/com/seibel/distanthorizons/forge17/ForgeMain.java',
            'forge17/src/main/java/com/seibel/distanthorizons/forge17/modAccessor/HodgePodgeAccessor.java',
            'common/src/main/java/com/seibel/distanthorizons/common/wrappers/modAccessor/IHodgePodgeCommonAccessor.java',
        ]:
            copy(relative)
        write(PREFIX + 'coreapi.ModInfo', 'public class ModInfo { public static final String READABLE_NAME="Akashic: Horizons of The Mirai", VERSION="0.1.0-dev"; }')
        write('test.State', '''public class State {
          public static final java.util.Map<Class<?>, Object> bindings = new java.util.HashMap<>();
          public static final java.util.List<String> logs = new java.util.ArrayList<>();
          public static void require(boolean result, String message) { if (!result) throw new AssertionError(message); }
          public static Object get(Class<?> type) { return bindings.get(type); }
          public static void bind(Class<?> type, Object object) {
            require(!bindings.containsKey(type), "duplicate binding: " + type.getName()); bindings.put(type, object);
          }
        }''')
        write('test.Log', 'public class Log { public void info(String s) { State.logs.add(s); } public void warn(String s) { State.logs.add(s); } }')
        write(PREFIX + 'common.AbstractModInitializer', '''public abstract class AbstractModInitializer {
          protected static final test.Log LOGGER = new test.Log();
          protected abstract void createInitialSharedBindings();
          protected abstract void createInitialClientBindings();
          protected abstract IEventProxy createClientProxy();
          protected abstract IEventProxy createServerProxy(boolean dedicated);
          protected abstract void initializeModCompat();
          protected abstract void subscribeClientStartedEvent(Runnable handler);
          protected abstract void subscribeServerStartingEvent(java.util.function.Consumer<net.minecraft.server.MinecraftServer> handler);
          protected abstract void runDelayedSetup();
          public interface IEventProxy { default void registerEvents() {} }
          protected <T> void addModCompatAccessor(Class<? super T> type, java.util.function.Supplier<T> factory) {
            test.State.bind(type, factory.get());
          }
          protected <T> void tryCreateModCompatAccessor(String mod, Class<? super T> type, java.util.function.Supplier<T> factory) {
            if (cpw.mods.fml.common.Loader.isModLoaded(mod)) addModCompatAccessor(type, factory);
          }
          public void onInitializeClient() {
            test.CapturedClient.check(); // Equivalent first capture performed by DependencySetup.createClientBindings().
            initializeModCompat();       // Real common initializer calls the hook a second time.
          }
          public void onInitializeServer() { test.CapturedServer.check(); initializeModCompat(); }
        }''')
        for iface in ['IAngelicaAccessor', 'IIrisAccessor', 'IGregTechAccessor', 'IRpleAccessor', 'IHodgePodgeAccessor', 'IModChecker']:
            write(PREFIX + 'core.wrapperInterfaces.modAccessor.' + iface,
                  'public interface ' + iface + ' { default String getModName() { return "test"; } }')
        for name, parent in [('IGregTechCommonAccessor', 'IGregTechAccessor'), ('IRpleCommonAccessor', 'IRpleAccessor')]:
            write(PREFIX + 'common.wrappers.modAccessor.' + name,
                  'public interface ' + name + ' extends ' + PREFIX + 'core.wrapperInterfaces.modAccessor.' + parent + ' {}')
        for name in ['IBiomeHandler']:
            write(PREFIX + 'common.wrappers.block.' + name, 'public interface ' + name + ' {}')
        write(PREFIX + 'core.wrapperInterfaces.modLoader.IForgeMain', 'public interface IForgeMain {}')
        write(PREFIX + 'core.wrapperInterfaces.misc.IPluginPacketSender', 'public interface IPluginPacketSender {}')
        write(PREFIX + 'core.dependencyInjection.SingletonInjector', '''public class SingletonInjector {
          public static final SingletonInjector INSTANCE = new SingletonInjector();
          public void bind(Class<?> type, Object value) {} public void runDelayedSetup() {}
        }''')
        write(PREFIX + 'core.api.internal.ServerApi', '''public class ServerApi {
          public static final ServerApi INSTANCE = new ServerApi();
          public void serverLoadEvent(boolean dedicated) {} public void serverUnloadEvent() {}
        }''')
        write(PREFIX + 'common.util.threading.ServerThreadTaskHandler', '''public class ServerThreadTaskHandler {
          public static final ServerThreadTaskHandler INSTANCE = new ServerThreadTaskHandler(); public void reset() {}
        }''')
        for name, body in {
            'ForgeClientProxy': 'implements ' + PREFIX + 'common.AbstractModInitializer.IEventProxy {}',
            'ForgeServerProxy': 'implements ' + PREFIX + 'common.AbstractModInitializer.IEventProxy { public ForgeServerProxy(boolean d) {} public static void serverStopping() {} }',
            'ModChecker': '{ public static final ModChecker INSTANCE = new ModChecker(); }',
            'BiomeHandler': '{ public static final BiomeHandler INSTANCE = new BiomeHandler(); }',
            'ForgePluginPacketSender': '{}',
        }.items():
            write(PREFIX + 'forge17.' + name, 'public class ' + name + ' ' + body)
        for name, iface, extra in [
            ('AngelicaAccessor', PREFIX + 'core.wrapperInterfaces.modAccessor.IAngelicaAccessor',
             'public static final String ANGELICA_MOD_ID="angelica"; public void throwIfUnsupportedAngelicaVersion() {}'),
            ('IrisAccessor', PREFIX + 'core.wrapperInterfaces.modAccessor.IIrisAccessor', ''),
            ('GregTechAccessor', PREFIX + 'common.wrappers.modAccessor.IGregTechCommonAccessor', ''),
            ('RpleAccessor', PREFIX + 'common.wrappers.modAccessor.IRpleCommonAccessor', ''),
        ]:
            write(PREFIX + 'forge17.modAccessor.' + name, 'public class ' + name + ' implements ' + iface + ' {' + extra + '}')
        write('gregtech.api.interfaces.IBlockWithTextures', 'public interface IBlockWithTextures {}')
        write('cpw.mods.fml.relauncher.Side', 'public enum Side { CLIENT, SERVER; public boolean isClient() { return this==CLIENT; } }')
        write('cpw.mods.fml.relauncher.SideOnly', 'public @interface SideOnly { Side value(); }')
        write('cpw.mods.fml.common.FMLCommonHandler', '''public class FMLCommonHandler {
          private static final FMLCommonHandler INSTANCE = new FMLCommonHandler();
          public static cpw.mods.fml.relauncher.Side physicalSide, effectiveSide;
          public static FMLCommonHandler instance() { return INSTANCE; }
          public cpw.mods.fml.relauncher.Side getSide() { return physicalSide; }
          public cpw.mods.fml.relauncher.Side getEffectiveSide() { return effectiveSide; }
        }''')
        write('cpw.mods.fml.common.Loader', '''public class Loader {
          public static final java.util.Set<String> loaded = new java.util.HashSet<>();
          public static boolean isModLoaded(String name) { return loaded.contains(name); }
        }''')
        write('cpw.mods.fml.common.Mod', '''public @interface Mod {
          String modid(); String name(); String version() default ""; String dependencies(); public @interface Instance {} public @interface EventHandler {}
        }''')
        write('cpw.mods.fml.common.network.NetworkCheckHandler', 'public @interface NetworkCheckHandler {}')
        write('cpw.mods.fml.common.event.FMLInitializationEvent', '''public class FMLInitializationEvent {
          public cpw.mods.fml.relauncher.Side getSide() { return cpw.mods.fml.common.FMLCommonHandler.physicalSide; }
        }''')
        for name in ['FMLServerAboutToStartEvent', 'FMLServerStoppedEvent', 'FMLServerStoppingEvent']:
            write('cpw.mods.fml.common.event.' + name, 'public class ' + name + ' { public net.minecraft.server.MinecraftServer getServer() { return new net.minecraft.server.MinecraftServer(); } }')
        write('net.minecraft.server.MinecraftServer', 'public class MinecraftServer { public boolean isDedicatedServer() { return true; } }')
        write('net.minecraft.world.World', 'public class World {}')
        write('net.minecraft.world.ChunkCoordIntPair', '''public class ChunkCoordIntPair {
          public static long chunkXZ2Int(int x, int z) { return (x & 0xffffffffL) | ((z & 0xffffffffL) << 32); }
        }''')
        write('net.minecraftforge.common.ForgeChunkManager', '''public class ForgeChunkManager {
          public static class Ticket {}
          public interface Callback { void accept(java.util.List<Ticket> tickets, net.minecraft.world.World world); }
          public static void setForcedChunkLoadingCallback(Object instance, Callback callback) {}
        }''')
        write('com.mitchej123.hodgepodge.config.FixesConfig', 'public class FixesConfig { public static boolean addSimulationDistance_WIP; }')
        write('com.mitchej123.hodgepodge.SimulationDistanceHelper', '''public class SimulationDistanceHelper {
          public static int calls; public static long lastPos; public static boolean lastPrevent;
          public static void preventChunkSimulation(net.minecraft.world.World world, long packed, boolean prevent) {
            if (!com.mitchej123.hodgepodge.config.FixesConfig.addSimulationDistance_WIP) return;
            calls++; lastPos=packed; lastPrevent=prevent;
          }
        }''')
        write('test.CapturedClient', '''import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.*;
          public class CapturedClient {
            private static final Object ANGELICA = State.get(IAngelicaAccessor.class);
            private static final Object IRIS = State.get(IIrisAccessor.class);
            private static final Object RPLE = State.get(IRpleAccessor.class);
            private static final Object GREGTECH = State.get(IGregTechAccessor.class);
            public static void check() {
              State.require(ANGELICA != null, "Angelica captured null before registration");
              State.require(IRIS != null, "Iris captured null before registration");
              State.require(RPLE != null, "RPLE captured null before registration");
              State.require(GREGTECH != null, "GregTech captured null before registration");
              CapturedServer.check();
            }
          }''')
        write('test.CapturedServer', '''import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IHodgePodgeAccessor;
          import com.seibel.distanthorizons.common.wrappers.modAccessor.IHodgePodgeCommonAccessor;
          public class CapturedServer {
            private static final Object HODGE = State.get(IHodgePodgeCommonAccessor.class);
            public static void check() {
              boolean present = cpw.mods.fml.common.Loader.isModLoaded("hodgepodge");
              State.require((HODGE != null) == present, "Hodgepodge captured incorrect presence");
              if (present) State.require(HODGE == State.get(IHodgePodgeAccessor.class), "Hodgepodge aliases must share instance");
            }
          }''')
        write('test.Harness', '''import cpw.mods.fml.common.*;
          import cpw.mods.fml.relauncher.Side;
          import com.seibel.distanthorizons.forge17.ForgeMain;
          import com.mitchej123.hodgepodge.config.FixesConfig;
          import com.mitchej123.hodgepodge.SimulationDistanceHelper;
          import com.seibel.distanthorizons.common.wrappers.modAccessor.IHodgePodgeCommonAccessor;
          public class Harness {
            public static void main(String[] args) throws Exception {
              boolean client=args[0].startsWith("client"), present=!args[0].equals("absent-server");
              FMLCommonHandler.physicalSide=client ? Side.CLIENT : Side.SERVER;
              // A second client case and both server cases intentionally disagree:
              // optional integration must follow physical side, not logical side.
              FMLCommonHandler.effectiveSide=args[0].equals("client") ? Side.CLIENT : (client ? Side.SERVER : Side.CLIENT);
              Loader.loaded.addAll(java.util.Arrays.asList("angelica", "gregtech", "rple"));
              if (present) { Loader.loaded.add("hodgepodge"); FixesConfig.addSimulationDistance_WIP=false; }
              ForgeMain main = new ForgeMain();
              main.init(new cpw.mods.fml.common.event.FMLInitializationEvent());
              State.require(ForgeMain.isHodgePodgeInstalled==present, "presence flag is stale");
              if (!client) State.require(State.bindings.size() == (present ? 2 : 0), "server registered client accessors");
              if (present) {
                State.require(!FixesConfig.addSimulationDistance_WIP, "init enabled experimental config");
                Object hodge=State.get(IHodgePodgeCommonAccessor.class);
                java.lang.reflect.Method capability=hodge.getClass().getMethod("isSimulationSuppressionEnabled");
                State.require(Boolean.FALSE.equals(capability.invoke(hodge)), "inactive capability reported enabled");
                IHodgePodgeCommonAccessor accessor=(IHodgePodgeCommonAccessor)hodge;
                net.minecraft.world.World world=new net.minecraft.world.World();
                accessor.preventChunkSimulation(world, -2, Integer.MAX_VALUE);
                State.require(SimulationDistanceHelper.calls==0, "inactive capability performed suppression");
                FixesConfig.addSimulationDistance_WIP=true;
                State.require(Boolean.TRUE.equals(capability.invoke(hodge)), "capability incorrectly cached");
                accessor.preventChunkSimulation(world, -2, Integer.MAX_VALUE);
                State.require(SimulationDistanceHelper.calls==1 && SimulationDistanceHelper.lastPrevent, "acquisition was not forwarded");
                State.require(SimulationDistanceHelper.lastPos==9223372036854775806L, "chunk coordinates were changed");
                accessor.allowChunkSimulation(world, -2, Integer.MAX_VALUE);
                State.require(SimulationDistanceHelper.calls==2 && !SimulationDistanceHelper.lastPrevent, "release was not forwarded");
                main.onServerAboutToStart(new cpw.mods.fml.common.event.FMLServerAboutToStartEvent());
                State.require(State.logs.stream().anyMatch(s -> s.contains("INACTIVE")), "missing inactive diagnostic");
                State.require(State.logs.stream().anyMatch(s -> s.contains("ENABLED")), "missing enabled diagnostic");
              } else {
                State.require(State.logs.stream().anyMatch(s -> s.contains("ABSENT")), "missing absent diagnostic");
              }
              System.out.println("PASS " + args[0]);
            }
          }''')
        sources = sorted(str(path) for path in src.rglob('*.java'))
        classes.mkdir()
        version = subprocess.run([str(jdk/'bin/javac'), '-version'], capture_output=True, text=True, check=True)
        target = ['-source', '8', '-target', '8'] if '1.8.' in (version.stdout + version.stderr) else ['--release', '8', '-Xlint:-options']
        subprocess.run([str(jdk/'bin/javac'), *target, '-d', str(classes), *sources], check=True)
        subprocess.run([str(jdk/'bin/java'), '-Xverify:all', '-cp', str(classes), 'test.Harness', 'client'], check=True)
        subprocess.run([str(jdk/'bin/java'), '-Xverify:all', '-cp', str(classes), 'test.Harness', 'client-effective-server'], check=True)
        # Remove graphics classes from the test runtime altogether. A dedicated
        # server path that eagerly links any of these must fail this scenario.
        for name in ['AngelicaAccessor', 'IrisAccessor', 'RpleAccessor', 'GregTechAccessor']:
            (classes / (PREFIX.replace('.', '/') + 'forge17/modAccessor/' + name + '.class')).unlink()
        (classes / (PREFIX.replace('.', '/') + 'forge17/ForgeClientProxy.class')).unlink()
        subprocess.run([str(jdk/'bin/java'), '-Xverify:all', '-cp', str(classes), 'test.Harness', 'server'], check=True)
        shutil.rmtree(classes / 'com/mitchej123')
        subprocess.run([str(jdk/'bin/java'), '-Xverify:all', '-cp', str(classes), 'test.Harness', 'absent-server'], check=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', required=True, type=Path)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    options = parser.parse_args()
    run(options.jdk.resolve(), options.source_root.resolve())
