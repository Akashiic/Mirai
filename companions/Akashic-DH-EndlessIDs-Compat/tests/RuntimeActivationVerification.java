import com.akashic.compat.dhendlessids.DHEndlessIDsCompatTransformer;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class RuntimeActivationVerification {
    private static final String TARGET =
        "com.seibel.distanthorizons.common.commonMixins.MixinChunkMapCommon";
    private static final String TARGET_METHOD = "onChunkSave";
    private static final String HOOK_OWNER =
        "com/akashic/compat/dhendlessids/DHEndlessIDsCompatHooks";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected Mirai jar");
        byte[] original = readClass(args[0], TARGET);
        LaunchClassLoader previous = Launch.classLoader;
        try {
            DHEndlessIDsCompatTransformer transformer = new DHEndlessIDsCompatTransformer();

            Launch.classLoader = new LaunchClassLoader(new URL[0]);
            byte[] untouched = transformer.transform(TARGET, TARGET, original);
            require(countCalls(untouched, false) == 1, "Patch activated without EndlessIDs");
            require(countCalls(untouched, true) == 0, "Hook appeared without EndlessIDs");
            Launch.classLoader.close();

            Path fakeEndlessIds = createFakeEndlessIdsJar();
            Launch.classLoader = new LaunchClassLoader(new URL[] { fakeEndlessIds.toUri().toURL() });
            byte[] patched = transformer.transform(TARGET, TARGET, original);
            require(countCalls(patched, false) == 0, "Unsafe call remains with EndlessIDs present");
            require(countCalls(patched, true) == 1, "Expected hook missing with EndlessIDs present");
            Launch.classLoader.close();
            Launch.classLoader = previous;
            Files.deleteIfExists(fakeEndlessIds);
        } finally {
            Launch.classLoader = previous;
        }

        System.out.println("RUNTIME_ACTIVATION_OK");
    }

    private static Path createFakeEndlessIdsJar() throws Exception {
        Path jar = Files.createTempFile("fake-endlessids", ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("com/falsepattern/endlessids/EndlessIDs.class"));
            out.write(0);
            out.closeEntry();
        }
        return jar;
    }

    private static int countCalls(byte[] bytes, boolean hook) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int count = 0;
        for (Object methodObject : node.methods) {
            MethodNode method = (MethodNode) methodObject;
            if (!TARGET_METHOD.equals(method.name)) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                if (hook) {
                    if (call.getOpcode() == Opcodes.INVOKESTATIC
                        && HOOK_OWNER.equals(call.owner)
                        && "validationBiomeArray".equals(call.name)) count++;
                } else if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && "net/minecraft/world/chunk/Chunk".equals(call.owner)
                    && "()[B".equals(call.desc)
                    && ("func_76605_m".equals(call.name) || "getBiomeArray".equals(call.name))) {
                    count++;
                }
            }
        }
        return count;
    }

    private static byte[] readClass(String jarPath, String className) throws Exception {
        String entryName = className.replace('.', '/') + ".class";
        try (ZipFile zip = new ZipFile(jarPath)) {
            ZipEntry entry = zip.getEntry(entryName);
            require(entry != null, "Missing target class");
            try (InputStream in = zip.getInputStream(entry);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
                return out.toByteArray();
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
