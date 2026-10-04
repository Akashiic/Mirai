package com.akashic.compat.dhrejectedlogout;

import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class RejectedLogoutGuardTransformer implements IClassTransformer {
    private static final Logger LOG = LogManager.getLogger("AkashicDHRejectedLogoutGuard");
    private static final String TARGET_CLASS =
        "com.seibel.distanthorizons.forge17.ForgeServerProxy";
    private static final String TARGET_METHOD = "playerLoggedOutEvent";
    private static final String TARGET_DESC =
        "(Lcpw/mods/fml/common/gameevent/PlayerEvent$PlayerLoggedOutEvent;)V";
    private static final String HOOK_OWNER =
        "com/akashic/compat/dhrejectedlogout/RejectedLogoutGuardHooks";
    private static final String WRAPPER_OWNER =
        "com/seibel/distanthorizons/forge17/ForgeServerProxy";
    private static final String SERVER_API_OWNER =
        "com/seibel/distanthorizons/core/api/internal/ServerApi";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }

        String className = transformedName != null ? transformedName : name;
        if (!TARGET_CLASS.equals(className)) {
            return basicClass;
        }

        try {
            return patch(basicClass);
        } catch (Throwable t) {
            LOG.error("Rejected-logout guard failed; preserving original ForgeServerProxy bytecode.", t);
            return basicClass;
        }
    }
    private static byte[] patch(byte[] originalBytes) {
        ClassNode classNode = new ClassNode();
        new ClassReader(originalBytes).accept(classNode, 0);

        MethodNode target = null;
        int matchingMethods = 0;
        for (Object methodObject : classNode.methods) {
            MethodNode method = (MethodNode) methodObject;
            if (TARGET_METHOD.equals(method.name) && TARGET_DESC.equals(method.desc)) {
                target = method;
                matchingMethods++;
            }
        }

        if (matchingMethods != 1 || target == null) {
            LOG.error("Refusing ambiguous patch: expected exactly one {}{}, found {}.",
                TARGET_METHOD, TARGET_DESC, matchingMethods);
            return originalBytes;
        }

        Shape shape = inspectShape(target);
        if (shape.guardCalls == 1) {
            LOG.info("Rejected-logout guard already present; leaving ForgeServerProxy unchanged.");
            return originalBytes;
        }
        if (shape.guardCalls != 0 || shape.wrapperCalls != 1 || shape.disconnectCalls != 1) {
            LOG.error(
                "Refusing unexpected 0.1.19 logout shape: guard={}, wrapper={}, disconnect={}.",
                shape.guardCalls, shape.wrapperCalls, shape.disconnectCalls
            );
            return originalBytes;
        }

        InsnList guard = new InsnList();
        LabelNode proceed = new LabelNode();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
        guard.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            HOOK_OWNER,
            "shouldSkip",
            "(Ljava/lang/Object;)Z",
            false
        ));
        guard.add(new JumpInsnNode(Opcodes.IFEQ, proceed));
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(proceed);
        guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        target.instructions.insert(guard);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        LOG.info("Patched Distant Horizons 0.1.19 rejected/incomplete logout guard at {}#{}.",
            TARGET_CLASS, TARGET_METHOD);
        return writer.toByteArray();
    }

    private static Shape inspectShape(MethodNode method) {
        Shape shape = new Shape();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode)) {
                continue;
            }
            MethodInsnNode call = (MethodInsnNode) insn;
            if (HOOK_OWNER.equals(call.owner) && "shouldSkip".equals(call.name)
                && "(Ljava/lang/Object;)Z".equals(call.desc)) {
                shape.guardCalls++;
            }
            if (WRAPPER_OWNER.equals(call.owner) && "getServerPlayerWrapper".equals(call.name)) {
                shape.wrapperCalls++;
            }
            if (SERVER_API_OWNER.equals(call.owner) && "serverPlayerDisconnectEvent".equals(call.name)) {
                shape.disconnectCalls++;
            }
        }
        return shape;
    }

    private static final class Shape {
        int guardCalls;
        int wrapperCalls;
        int disconnectCalls;
    }
}
