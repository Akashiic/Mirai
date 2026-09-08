import java.util.ArrayList;
import java.util.List;

/** Executes the real packaged identity classes; needs Log4j API on the classpath. */
public final class ReleaseIdentityProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Expected version, commit, branch, build source");
        }
        List<String> failures = new ArrayList<String>();
        check(failures, "com.seibel.distanthorizons.coreapi.ModInfo", "VERSION", args[0]);
        check(failures, "com.seibel.distanthorizons.core.jar.ModJarInfo", "Git_Commit", args[1]);
        check(failures, "com.seibel.distanthorizons.core.jar.ModJarInfo", "Git_Branch", args[2]);
        check(failures, "com.seibel.distanthorizons.core.jar.ModJarInfo", "Build_Source", args[3]);
        if (!failures.isEmpty()) {
            throw new AssertionError(failures.toString());
        }
        System.out.println("PASS: packaged VERSION, Git_Commit, Git_Branch and Build_Source");
    }

    private static void check(List<String> failures, String className, String field, String expected)
            throws Exception {
        Object actual = Class.forName(className).getField(field).get(null);
        if (!expected.equals(actual)) {
            failures.add(field + ": expected [" + expected + "] but was [" + actual + "]");
        }
    }
}
