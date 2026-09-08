import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.states.FogState;
import com.gtnewhorizons.angelica.glsm.stacks.FogStateStack;
import java.awt.Color;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Executes the shipped accessor method instructions against real Angelica FogState. */
public final class FogColorProbe {
    private static final String OWNER = "com/seibel/distanthorizons/forge17/modAccessor/AngelicaAccessor";
    private static Method accessor;
    private static Object instance;
    private static int passed;

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            result.append(String.format("%02x", value & 255));
        }
        return result.toString();
    }

    private static byte[] extract(byte[] original) {
        final ClassWriter writer = new ClassWriter(0);
        final int[] copied = {0};
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if (!name.equals("getFogColor") || !descriptor.equals("()Ljava/awt/Color;")) return null;
                copied[0]++;
                // Direct visitor forwarding: no instruction, descriptor or constant rewriting.
                return writer.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 0);
        require(copied[0] == 1, "Expected exactly one getFogColor() method in final JAR");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class FixtureLoader extends URLClassLoader {
        FixtureLoader(Path jar) throws Exception {
            super(new URL[] {jar.toUri().toURL()}, FogColorProbe.class.getClassLoader());
        }
        Class<?> defineAccessor(byte[] bytes) {
            return defineClass(OWNER.replace('/', '.'), bytes, 0, bytes.length);
        }
    }

    private static Color read() throws Exception {
        return (Color) accessor.invoke(instance);
    }

    private static FogStateStack state(float red, float green, float blue, float alpha) {
        FogStateStack state = new FogStateStack();
        state.getFogColorBuffer().put(0, red).put(1, green).put(2, blue).put(3, alpha);
        // Deliberately different: this probe must exercise the buffer contract.
        state.getFogColor().set(0.9375, 0.8125, 0.6875);
        return state;
    }

    private static void color(Color actual, float red, float green, float blue) {
        require(actual.getRGB() == new Color(red, green, blue).getRGB(),
                "RGB/alpha mismatch: got " + actual + " alpha=" + actual.getAlpha());
        require(actual.getAlpha() == 255, "Fog accessor must retain opaque Color output");
    }

    private static void pass(String name) {
        passed++;
        System.out.println("PASS " + name);
    }

    private static int[] contents(FloatBuffer buffer) {
        FloatBuffer view = buffer.duplicate();
        view.clear();
        int[] result = new int[view.capacity()];
        for (int i = 0; i < result.length; i++) result[i] = Float.floatToRawIntBits(view.get(i));
        return result;
    }

    private static void positive() throws Exception {
        GLStateManager.current = new FogStateStack();
        color(read(), 0, 0, 0);
        pass("default-black");

        GLStateManager.current = state(0.2f, 0.4f, 0.8f, 0.1f);
        color(read(), 0.2f, 0.4f, 0.8f);
        pass("rgb-buffer-and-opaque-alpha");

        GLStateManager.current = state(-0.5f, 1.5f, 0.25f, 0.7f);
        color(read(), 0, 1, 0.25f);
        pass("finite-clamp");

        GLStateManager.current = state(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -0.0f, 0);
        color(read(), 0, 1, 0);
        pass("infinite-clamp-and-zero");

        GLStateManager.current = state(0, 0, 0, 0);
        color(read(), 0, 0, 0);
        GLStateManager.current.getFogColorBuffer().put(0, 1).put(1, 1).put(2, 1);
        color(read(), 1, 1, 1);
        pass("same-state-updates-between-calls");

        FogStateStack first = state(0.1f, 0.2f, 0.3f, 0.4f);
        FogStateStack second = state(0.7f, 0.6f, 0.5f, 0.4f);
        GLStateManager.current = first;
        color(read(), 0.1f, 0.2f, 0.3f);
        GLStateManager.current = second;
        color(read(), 0.7f, 0.6f, 0.5f);
        GLStateManager.current = first;
        color(read(), 0.1f, 0.2f, 0.3f);
        pass("current-context-selected-every-call");

        GLStateManager.current = state(0.2f, 0.5f, 0.9f, 0.125f);
        FogState selected = GLStateManager.current;
        FogState snapshot = selected.copy();
        FloatBuffer buffer = selected.getFogColorBuffer();
        buffer.limit(3);
        buffer.position(1);
        buffer.mark();
        buffer.position(3);
        int[] before = contents(buffer);
        color(read(), 0.2f, 0.5f, 0.9f);
        require(buffer.position() == 3 && buffer.limit() == 3, "Buffer cursor or limit mutated");
        require(Arrays.equals(before, contents(buffer)), "Buffer contents mutated");
        require(selected.sameAs(snapshot), "Fog scalar or vector state mutated");
        buffer.reset();
        require(buffer.position() == 1, "Buffer mark mutated");
        pass("absolute-read-preserves-position-limit-mark-contents-state");

        buffer.position(3);
        for (int i = 0; i < 128; i++) color(read(), 0.2f, 0.5f, 0.9f);
        require(buffer.position() == 3 && buffer.limit() == 3, "Repeated reads moved buffer cursor");
        require(Arrays.equals(before, contents(buffer)), "Repeated reads changed buffer data");
        require(selected.sameAs(snapshot), "Repeated reads changed fog state");
        pass("repeated-reads-preserve-state");
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 3, "Usage: FogColorProbe <mirai.jar> <positive|negative> <extracted.class>");
        Path jar = Paths.get(args[0]).toAbsolutePath();
        byte[] original;
        try (ZipFile zip = new ZipFile(jar.toFile());
             InputStream input = zip.getInputStream(zip.getEntry(OWNER + ".class"))) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] block = new byte[8192];
            int count;
            while ((count = input.read(block)) != -1) bytes.write(block, 0, count);
            original = bytes.toByteArray();
        }
        byte[] extracted = extract(original);
        Files.write(Paths.get(args[2]), extracted);
        System.out.println("original_accessor_sha256=" + sha256(original));
        System.out.println("extracted_fixture_sha256=" + sha256(extracted));
        System.out.println("FogState_source=" + FogState.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("FogStateStack_source=" + FogStateStack.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("IStateStack_source=" + com.gtnewhorizon.gtnhlib.client.renderer.stacks.IStateStack.class
                .getProtectionDomain().getCodeSource().getLocation());
        System.out.println("JOML_source=" + org.joml.Vector3d.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("GLStateManager=headless context selector fixture; no GL calls");
        try (FixtureLoader loader = new FixtureLoader(jar)) {
            Class<?> owner = loader.defineAccessor(extracted);
            accessor = owner.getDeclaredMethod("getFogColor");
            instance = owner.getDeclaredConstructor().newInstance();
            if (args[1].equals("negative")) {
                GLStateManager.current = new FogStateStack();
                try {
                    read();
                    throw new AssertionError("Old JAR unexpectedly succeeded");
                } catch (InvocationTargetException error) {
                    Throwable cause = error.getCause();
                    require(cause instanceof NoSuchMethodError, "Unexpected old-JAR exception: " + cause);
                    require(cause.getMessage().contains("getFogColor")
                                    && cause.getMessage().contains("DistantHorizons"),
                            "Wrong NoSuchMethodError: " + cause);
                    System.out.println("expected_failure=" + cause);
                    pass("old-jar-relocated-descriptor-fails");
                }
            } else {
                require(args[1].equals("positive"), "Unknown mode");
                positive();
            }
        }
        System.out.println("PASS_COUNT=" + passed);
    }
}
