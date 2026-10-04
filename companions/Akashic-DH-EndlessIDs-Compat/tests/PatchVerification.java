import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class PatchVerification {
    private static final String TRANSFORMER =
        "com.akashic.compat.dhendlessids.DHEndlessIDsCompatTransformer";
    private static final String HOOK_OWNER =
        "com/akashic/compat/dhendlessids/DHEndlessIDsCompatHooks";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected Mirai 0.1.16 and 0.1.17 jars");
        verifyJar(args[0], "SAVE_HOOK", "onChunkSave", 1);
        verifyJar(args[0], "PREP_SCAN", "scanBatch", 2);
        verifyJar(args[1], "SAVE_HOOK", "onChunkSave", 1);
        verifyJar(args[1], "PREP_SCAN", "scanBatch", 2);
        System.out.println("PATCH_VERIFICATION_OK");
    }

    private static void verifyJar(String jarPath, String specField,
                                  String methodName, int expected) throws Exception {
        Class<?> transformerClass = Class.forName(TRANSFORMER);
        Field field = transformerClass.getDeclaredField(specField);
        field.setAccessible(true);
        Object spec = field.get(null);

        Field classNameField = spec.getClass().getDeclaredField("className");
        classNameField.setAccessible(true);
        String className = (String) classNameField.get(spec);

        byte[] original = readClass(jarPath, className);
        int before = countCalls(original, methodName, false);
        require(before == expected, jarPath + " original count " + before + " != " + expected);

        Method patch = transformerClass.getDeclaredMethod(
            "patchValidationCalls", spec.getClass(), byte[].class);
        patch.setAccessible(true);
        byte[] patched = (byte[]) patch.invoke(null, spec, original);

        int unsafeAfter = countCalls(patched, methodName, false);
        int hooksAfter = countCalls(patched, methodName, true);
        require(unsafeAfter == 0, jarPath + " still contains unsafe biome-array calls");
        require(hooksAfter == expected, jarPath + " hook count " + hooksAfter + " != " + expected);

        byte[] patchedTwice = (byte[]) patch.invoke(null, spec, patched);
        require(countCalls(patchedTwice, methodName, false) == 0,
            jarPath + " is not idempotent");
    }

    private static int countCalls(byte[] bytes, String methodName, boolean hooks) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        int count = 0;
        for (Object methodObject : node.methods) {
            MethodNode method = (MethodNode) methodObject;
            if (!methodName.equals(method.name)) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst();
                 insn != null;
                 insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                if (hooks) {
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
            require(entry != null, "Missing class " + className + " in " + jarPath);
            try (InputStream in = zip.getInputStream(entry);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
