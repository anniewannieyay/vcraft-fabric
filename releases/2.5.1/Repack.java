import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Makes vcraft-2.5.1.jar from vcraft-2.5.0.jar: swaps in the recompiled classes, bumps the
 * version in fabric.mod.json and the manifest, and copies every other entry byte for byte.
 *
 *   java Repack.java <vcraft-2.5.0.jar> <classes dir> <out jar>
 */
public class Repack {
    static final String OLD = "2.5.0", NEW = "2.5.1";

    public static void main(String[] a) throws IOException {
        Path base = Path.of(a[0]), classes = Path.of(a[1]), out = Path.of(a[2]);
        Map<String, byte[]> replace = new TreeMap<>();
        try (var walk = Files.walk(classes)) {
            for (Path p : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".class"))::iterator)
                replace.put(classes.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
        }
        Set<String> used = new HashSet<>();
        try (ZipFile zin = new ZipFile(base.toFile());
             ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))) {
            for (ZipEntry e : Collections.list(zin.entries())) {
                byte[] data = zin.getInputStream(e).readAllBytes();
                String name = e.getName();
                if (replace.containsKey(name)) {
                    data = replace.get(name);
                    used.add(name);
                } else if (name.equals("fabric.mod.json")) {
                    String t = new String(data, StandardCharsets.UTF_8);
                    if (!t.contains("\"version\": \"" + OLD + "\"")) throw new IllegalStateException("unexpected fabric.mod.json");
                    data = t.replace("\"version\": \"" + OLD + "\"", "\"version\": \"" + NEW + "\"").getBytes(StandardCharsets.UTF_8);
                } else if (name.equals("META-INF/MANIFEST.MF")) {
                    String t = new String(data, StandardCharsets.UTF_8);
                    if (!t.contains("Implementation-Version: " + OLD)) throw new IllegalStateException("unexpected manifest");
                    data = t.replace("Implementation-Version: " + OLD, "Implementation-Version: " + NEW).getBytes(StandardCharsets.UTF_8);
                }
                ZipEntry n = new ZipEntry(name);
                n.setTime(e.getTime());
                zout.putNextEntry(n);
                zout.write(data);
                zout.closeEntry();
            }
            // Classes the old jar didn't have.
            for (var en : replace.entrySet())
                if (!used.contains(en.getKey())) {
                    ZipEntry n = new ZipEntry(en.getKey());
                    n.setTime(315532800000L + 86400000L * 31);
                    zout.putNextEntry(n);
                    zout.write(en.getValue());
                    zout.closeEntry();
                    System.out.println("added " + en.getKey());
                }
        }
        System.out.println("replaced " + used.size() + " classes, wrote " + out);
    }
}
