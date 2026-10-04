import com.akashic.compat.dhrejectedlogout.RejectedLogoutGuardTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class TransformerVerification {
    private static final String TARGET_CLASS =
        "com.seibel.distanthorizons.forge17.ForgeServerProxy";
    private static final String TARGET_ENTRY =
        "com/seibel/distanthorizons/forge17/ForgeServerProxy.class";
    private static final String TARGET_METHOD = "playerLoggedOutEvent";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected path to Mirai 0.1.19 JAR");
        }
        byte[] original = readClass(args[0], TARGET_ENTRY);
        RejectedLogoutGuardTransformer transformer = new RejectedLogoutGuardTransformer();
        byte[] patched = transformer.transform(TARGET_CLASS, TARGET_CLASS, original);
        if (patched == null || patched.length == 0) {
            throw new AssertionError("Transformer returned empty bytecode");
        }
        if (Arrays.equals(original, patched)) {
            throw new AssertionError("Expected Mirai 0.1.19 ForgeServerProxy to be patched");
        }

        ClassNode node = new ClassNode();
        new ClassReader(patched).accept(node, 0);
        MethodNode target = findTarget(node);

        int guardCalls = 0;
        int wrapperCalls = 0;
        int disconnectCalls = 0;
        for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            if ("com/akashic/compat/dhrejectedlogout/RejectedLogoutGuardHooks".equals(call.owner)
                && "shouldSkip".equals(call.name)) guardCalls++;
            if (TARGET_ENTRY.substring(0, TARGET_ENTRY.length() - 6).equals(call.owner)
                && "getServerPlayerWrapper".equals(call.name)) wrapperCalls++;
            if ("com/seibel/distanthorizons/core/api/internal/ServerApi".equals(call.owner)
                && "serverPlayerDisconnectEvent".equals(call.name)) disconnectCalls++;
        }

        expect(1, guardCalls, "guard call count");
        expect(1, wrapperCalls, "original wrapper call count");
        expect(1, disconnectCalls, "original disconnect call count");

        Analyzer analyzer = new Analyzer(new BasicInterpreter());
        analyzer.analyze(node.name, target);

        byte[] secondPass = transformer.transform(TARGET_CLASS, TARGET_CLASS, patched);
        if (!Arrays.equals(patched, secondPass)) {
            throw new AssertionError("Transformer is not idempotent");
        }

        byte[] unrelated = transformer.transform("example.Unrelated", "example.Unrelated", original);
        if (unrelated != original) {
            throw new AssertionError("Unrelated class must be returned untouched");
        }

        System.out.println("TransformerVerification: PASS");
    }
    private static MethodNode findTarget(ClassNode node) {
        MethodNode found = null;
        for (Object methodObject : node.methods) {
            MethodNode method = (MethodNode) methodObject;
            if (TARGET_METHOD.equals(method.name)
                && "(Lcpw/mods/fml/common/gameevent/PlayerEvent$PlayerLoggedOutEvent;)V".equals(method.desc)) {
                if (found != null) throw new AssertionError("Duplicate target method");
                found = method;
            }
        }
        if (found == null) throw new AssertionError("Target logout method not found");
        return found;
    }

    private static byte[] readClass(String jarPath, String entryName) throws Exception {
        try (JarFile jar = new JarFile(jarPath)) {
            JarEntry entry = jar.getJarEntry(entryName);
            if (entry == null) throw new AssertionError("Missing target class in Mirai JAR");
            try (InputStream in = jar.getInputStream(entry)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                return out.toByteArray();
            }
        }
    }
    private static void expect(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
        }
    }
}

