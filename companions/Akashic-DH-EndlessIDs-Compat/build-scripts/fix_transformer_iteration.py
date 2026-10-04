from pathlib import Path

p = Path(r"C:\Users\User\Desktop\Codex\Akashic-DH-EndlessIDs-Compat\src\main\java\com\akashic\compat\dhendlessids\DHEndlessIDsCompatTransformer.java")
s = p.read_text(encoding="utf-8")
start = s.index("            for (AbstractInsnNode insn = method.instructions.getFirst();")
end = s.index("            }\n        }\n        return count;", start) + len("            }\n")
replacement = '''            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
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
'''
p.write_text(s[:start] + replacement + s[end:], encoding="utf-8")
