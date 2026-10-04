package com.akashic.compat.dhendlessids;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.net.URL;

public final class DHEndlessIDsCompatTransformer implements IClassTransformer {
    private static final Logger LOG = LogManager.getLogger("AkashicDHEndlessIDsCompat");
    private static final String CHUNK_OWNER = "net/minecraft/world/chunk/Chunk";
    private static final String HOOK_OWNER =
        "com/akashic/compat/dhendlessids/DHEndlessIDsCompatHooks";

    private static final TargetSpec SAVE_HOOK = new TargetSpec(
        "com.seibel.distanthorizons.common.commonMixins.MixinChunkMapCommon",
        "onChunkSave",
        1
    );
    private static final TargetSpec PREP_SCAN = new TargetSpec(
        "com.seibel.distanthorizons.common.util.LegacyLoadedTerrainPreparation",
        "scanBatch",
        2
    );

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }

        TargetSpec target = targetFor(transformedName != null ? transformedName : name);
        if (target == null) {
            return basicClass;
        }

        if (!isEndlessIDsPresent()) {
            LOG.info("EndlessIDs not detected; leaving {} untouched.", target.className);
            return basicClass;
        }

        return patchValidationCalls(target, basicClass);
    }

    private static TargetSpec targetFor(String className) {
        if (SAVE_HOOK.className.equals(className)) return SAVE_HOOK;
        if (PREP_SCAN.className.equals(className)) return PREP_SCAN;
        return null;
    }
    private static boolean isEndlessIDsPresent() {
        return hasResource("com/falsepattern/endlessids/EndlessIDs.class")
            || hasResource("com/falsepattern/endlessids/asm/EndlessIDsCore.class");
    }

    private static boolean hasResource(String path) {
        try {
            URL resource = Launch.classLoader.getResource(path);
            return resource != null;
        } catch (Throwable t) {
            LOG.warn("Could not probe classpath resource {}. Patch stays disabled.", path, t);
            return false;
        }
    }

    private static byte[] patchValidationCalls(TargetSpec target, byte[] originalBytes) {
        try {
            ClassNode classNode = new ClassNode();
            new ClassReader(originalBytes).accept(classNode, 0);
            int patchedCalls = replaceUnsafeCalls(classNode, target.methodName);

            if (patchedCalls == 0) {
                LOG.info("No vanilla biome-array validation calls remain in {}; no patch needed.",
                    target.className);
                return originalBytes;
            }

            if (patchedCalls != target.expectedCalls) {
                LOG.error("Refusing ambiguous patch for {}#{}: expected {} calls, found {}.",
                    target.className, target.methodName, target.expectedCalls, patchedCalls);
                return originalBytes;
            }
            ClassWriter writer = new ClassWriter(0);
            classNode.accept(writer);
            LOG.info("Patched {} safe biome-validation call(s) in {}#{}.",
                patchedCalls, target.className, target.methodName);
            return writer.toByteArray();
        } catch (Throwable t) {
            LOG.error("Failed to patch {}#{}. Original bytecode will be preserved.",
                target.className, target.methodName, t);
            return originalBytes;
        }
    }

    private static int replaceUnsafeCalls(ClassNode classNode, String targetMethodName) {
        int count = 0;
        for (Object methodObject : classNode.methods) {
            MethodNode method = (MethodNode) methodObject;
            if (!targetMethodName.equals(method.name)) continue;

            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
                AbstractInsnNode next = insn.getNext();
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) insn;
                    if (isUnsafeBiomeArrayCall(call)) {
                        MethodInsnNode replacement = new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            HOOK_OWNER,
                            "validationBiomeArray",
                            "(Ljava/lang/Object;)[B",
                            false
                        );
                        method.instructions.set(call, replacement);
                        count++;
                    }
                }
                insn = next;
            }
        }
        return count;
    }

    private static boolean isUnsafeBiomeArrayCall(MethodInsnNode call) {
        if (call.getOpcode() != Opcodes.INVOKEVIRTUAL) return false;
        if (!CHUNK_OWNER.equals(call.owner)) return false;
        if (!"()[B".equals(call.desc)) return false;
        return "func_76605_m".equals(call.name) || "getBiomeArray".equals(call.name);
    }

    private static final class TargetSpec {
        final String className;
        final String methodName;
        final int expectedCalls;

        TargetSpec(String className, String methodName, int expectedCalls) {
            this.className = className;
            this.methodName = methodName;
            this.expectedCalls = expectedCalls;
        }
    }
}
