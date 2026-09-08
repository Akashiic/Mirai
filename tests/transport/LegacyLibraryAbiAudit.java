import org.objectweb.asm.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Static symbolic-reference audit, not an execution or reflection/string audit. */
public final class LegacyLibraryAbiAudit
{
    private static final String[] PREFIXES = {"com/google/common/", "org/apache/logging/log4j/", "io/netty/"};
    private final Map<String, Definition> definitions = new HashMap<>();
    private final Map<String, byte[]> available = new HashMap<>();
    private final Map<String, Use> uses = new TreeMap<>();
    private ZipFile rt;
    private int scannedClasses;

    public static void main(String[] args) throws Exception
    {
        LegacyLibraryAbiAudit audit = new LegacyLibraryAbiAudit();
        try (ZipFile rt = new ZipFile(args[1]))
        {
            audit.rt = rt;
            for (int i = 2; i < args.length; i++)
            {
                try (ZipFile lib = new ZipFile(args[i]))
                {
                    Enumeration<? extends ZipEntry> entries = lib.entries();
                    while (entries.hasMoreElements())
                    {
                        ZipEntry entry = entries.nextElement();
                        if (entry.getName().endsWith(".class") && !entry.getName().startsWith("META-INF/versions/"))
                        {
                            audit.available.put(entry.getName().substring(0, entry.getName().length() - 6), read(lib.getInputStream(entry)));
                        }
                    }
                }
            }
            try (ZipFile jar = new ZipFile(args[0]))
            {
                Enumeration<? extends ZipEntry> entries = jar.entries();
                while (entries.hasMoreElements())
                {
                    ZipEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".class") && !entry.getName().startsWith("META-INF/versions/"))
                    {
                        audit.scan(read(jar.getInputStream(entry)));
                    }
                }
            }
            audit.report();
        }
    }

    private static byte[] read(InputStream input) throws IOException
    {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            byte[] chunk = new byte[8192]; int size;
            while ((size = in.read(chunk)) != -1) out.write(chunk, 0, size);
            return out.toByteArray();
        }
    }

    private static boolean target(String owner)
    {
        if (owner == null) return false;
        for (String prefix : PREFIXES) if (owner.startsWith(prefix)) return true;
        return false;
    }

    private void add(String kind, String owner, String name, String desc, int opcode, String caller)
    {
        if (!target(owner)) return;
        String key = kind + " " + owner + "." + name + desc + " opcode=" + opcode;
        Use use = this.uses.get(key);
        if (use == null)
        {
            use = new Use(kind, owner, name, desc, opcode);
            this.uses.put(key, use);
        }
        use.count++;
        if (use.callers.size() < 5) use.callers.add(caller);
    }

    private void type(Type type, String caller)
    {
        if (type.getSort() == Type.OBJECT) add("class", type.getInternalName(), "", "", 0, caller);
        else if (type.getSort() == Type.ARRAY) type(type.getElementType(), caller);
        else if (type.getSort() == Type.METHOD)
        {
            type(type.getReturnType(), caller);
            for (Type argument : type.getArgumentTypes()) type(argument, caller);
        }
    }

    private void constant(Object value, String caller)
    {
        if (value instanceof Type) type((Type) value, caller);
        else if (value instanceof Handle)
        {
            Handle handle = (Handle) value;
            int tag = handle.getTag();
            boolean field = tag <= Opcodes.H_PUTSTATIC;
            int opcode = field
                    ? ((tag == Opcodes.H_GETSTATIC || tag == Opcodes.H_PUTSTATIC) ? Opcodes.GETSTATIC : Opcodes.GETFIELD)
                    : (tag == Opcodes.H_INVOKESTATIC ? Opcodes.INVOKESTATIC : tag == Opcodes.H_INVOKEINTERFACE ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL);
            add(field ? "field" : "method", handle.getOwner(), handle.getName(), handle.getDesc(), opcode, caller);
            type(Type.getType(handle.getDesc()), caller);
        }
        else if (value instanceof ConstantDynamic)
        {
            ConstantDynamic dynamic = (ConstantDynamic) value;
            type(Type.getType(dynamic.getDescriptor()), caller);
            constant(dynamic.getBootstrapMethod(), caller);
            for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) constant(dynamic.getBootstrapMethodArgument(i), caller);
        }
    }

    private void scan(byte[] bytes)
    {
        this.scannedClasses++;
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9)
        {
            private String caller;
            @Override public void visit(int version, int access, String name, String signature, String parent, String[] interfaces)
            {
                caller = name;
                add("class", parent, "", "", 0, caller);
                for (String iface : interfaces) add("class", iface, "", "", 0, caller);
            }
            @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value)
            {
                type(Type.getType(descriptor), caller + "." + name);
                constant(value, caller + "." + name);
                return null;
            }
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions)
            {
                final String site = caller + "." + name + descriptor;
                type(Type.getMethodType(descriptor), site);
                if (exceptions != null) for (String exception : exceptions) add("class", exception, "", "", 0, site);
                return new MethodVisitor(Opcodes.ASM9)
                {
                    @Override public void visitTypeInsn(int opcode, String name)
                    {
                        if (name.startsWith("[")) type(Type.getType(name), site);
                        else add("class", name, "", "", opcode, site);
                    }
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean iface)
                    {
                        add("method", owner, name, descriptor, opcode, site);
                        type(Type.getMethodType(descriptor), site);
                    }
                    @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor)
                    {
                        add("field", owner, name, descriptor, opcode, site);
                        type(Type.getType(descriptor), site);
                    }
                    @Override public void visitLdcInsn(Object value) { constant(value, site); }
                    @Override public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments)
                    {
                        type(Type.getMethodType(descriptor), site);
                        constant(bootstrap, site);
                        for (Object argument : arguments) constant(argument, site);
                    }
                    @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type)
                    { add("class", type, "", "", 0, site); }
                    @Override public void visitMultiANewArrayInsn(String descriptor, int dimensions)
                    { type(Type.getType(descriptor), site); }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private Definition definition(String name) throws IOException
    {
        Definition known = this.definitions.get(name);
        if (known != null) return known;
        byte[] bytes = this.available.get(name);
        if (bytes == null)
        {
            ZipEntry entry = this.rt.getEntry(name + ".class");
            if (entry != null) bytes = read(this.rt.getInputStream(entry));
        }
        if (bytes == null) return null;
        Definition result = new Definition();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9)
        {
            @Override public void visit(int version, int access, String name, String signature, String parent, String[] interfaces)
            { result.name = name; result.access = access; result.parent = parent; result.interfaces = interfaces; }
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions)
            { result.methods.put(name + desc, access); return null; }
            @Override public FieldVisitor visitField(int access, String name, String desc, String signature, Object value)
            { result.fields.put(name + desc, access); return null; }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        this.definitions.put(name, result);
        return result;
    }

    private Member resolve(Use use, String owner, Set<String> seen) throws IOException
    {
        if (owner == null || !seen.add(owner)) return null;
        Definition definition = definition(owner);
        if (definition == null) return null;
        Integer access = (use.kind.equals("method") ? definition.methods : definition.fields).get(use.name + use.desc);
        if (access != null) return new Member(owner, access);
        if (use.name.equals("<init>")) return null;
        Member found = resolve(use, definition.parent, seen);
        if (found != null) return found;
        for (String iface : definition.interfaces)
        {
            found = resolve(use, iface, seen);
            if (found != null) return found;
        }
        return null;
    }

    private void report() throws IOException
    {
        int failures = 0; int inheritedJdk = 0;
        for (Map.Entry<String, Use> entry : this.uses.entrySet())
        {
            Use use = entry.getValue();
            Definition owner = definition(use.owner);
            String failure = null;
            Member member = null;
            if (owner == null) failure = "MISSING_CLASS";
            else if ((owner.access & Opcodes.ACC_PUBLIC) == 0) failure = "NONPUBLIC_OWNER_REQUIRES_ACCESS_REVIEW";
            else if (!use.kind.equals("class"))
            {
                member = resolve(use, use.owner, new HashSet<>());
                if (member == null) failure = "MISSING_MEMBER";
                else
                {
                    boolean staticUse = use.opcode == Opcodes.INVOKESTATIC || use.opcode == Opcodes.GETSTATIC || use.opcode == Opcodes.PUTSTATIC;
                    if (staticUse != ((member.access & Opcodes.ACC_STATIC) != 0)) failure = "STATIC_INSTANCE_MISMATCH";
                    else if ((member.access & Opcodes.ACC_PUBLIC) == 0) failure = "NONPUBLIC_MEMBER_REQUIRES_ACCESS_REVIEW";
                    if (member.owner.startsWith("java/")) inheritedJdk++;
                }
            }
            if (failure != null)
            {
                failures++;
                System.out.println(failure + " " + entry.getKey() + " callers=" + use.callers);
            }
        }
        System.out.println("SUMMARY classes=" + this.scannedClasses + " uniqueSymbolUses=" + this.uses.size()
                + " findings=" + failures + " inheritedJdkMembers=" + inheritedJdk);
        System.out.println("SCOPE direct bytecode types/descriptors/methods/fields/bootstrap handles; libraries supplied as arguments; not runtime validation, reflection, strings, or native ABI");
    }

    private static final class Definition
    {
        String name, parent; String[] interfaces; int access;
        final Map<String, Integer> methods = new HashMap<>(), fields = new HashMap<>();
    }
    private static final class Member
    {
        final String owner; final int access;
        Member(String owner, int access) { this.owner = owner; this.access = access; }
    }
    private static final class Use
    {
        final String kind, owner, name, desc; final int opcode;
        int count; final Set<String> callers = new LinkedHashSet<>();
        Use(String kind, String owner, String name, String desc, int opcode)
        { this.kind = kind; this.owner = owner; this.name = name; this.desc = desc; this.opcode = opcode; }
    }
}
