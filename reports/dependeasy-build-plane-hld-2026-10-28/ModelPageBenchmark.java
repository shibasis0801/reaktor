import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public final class ModelPageBenchmark {
    static final class Variant implements AutoCloseable {
        final URLClassLoader loader;
        final Object reader;
        final Method page;
        Variant(Path runtime, Path overlay) throws Exception {
            List<URL> urls = new ArrayList<>();
            urls.add(overlay.toUri().toURL());
            try (var paths = Files.list(runtime)) {
                for (Path jar : paths.sorted().filter(p -> p.toString().endsWith(".jar")).toList()) urls.add(jar.toUri().toURL());
            }
            loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
            Class<?> type = loader.loadClass("dev.shibasis.dependeasy.model.BuildModelReader");
            reader = type.getField("INSTANCE").get(null);
            page = type.getMethod("modelPage", File.class, String.class, int.class, int.class);
        }
        String read(Path root, String view, int offset, int limit) throws Exception {
            return page.invoke(reader, root.toFile(), view, offset, limit).toString();
        }
        public void close() throws Exception { loader.close(); }
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        try (Variant before = new Variant(Path.of(args[1]), Path.of(args[2]));
             Variant after = new Variant(Path.of(args[1]), Path.of(args[3]))) {
            List<String> scopes = args.length > 4 ? List.of("nodes:0:1")
                : List.of("nodes:0:1", "nodes:1000:10", "nodes:1906:10", "nodes:3000:1", "edges:1000:10");
            for (String scope : scopes) {
                String[] parts = scope.split(":");
                for (int rep = -4; rep < (args.length > 4 ? 20 : 8); rep++) {
                    String expected = null;
                    for (String name : rep % 2 == 0 ? List.of("before", "after") : List.of("after", "before")) {
                        Variant variant = name.equals("before") ? before : after;
                        long started = System.nanoTime();
                        String result = variant.read(root, parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                        double seconds = (System.nanoTime() - started) / 1e9;
                        if (expected != null && !expected.equals(result)) throw new AssertionError("Page differs: " + scope);
                        expected = result;
                        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(result.getBytes(StandardCharsets.UTF_8)));
                        System.out.printf(Locale.ROOT, "{\"scope\":\"%s\",\"rep\":%d,\"variant\":\"%s\",\"seconds\":%.9f,\"bytes\":%d,\"sha256\":\"%s\"}%n",
                            scope, rep, name, seconds, result.getBytes(StandardCharsets.UTF_8).length, digest);
                        System.out.flush();
                    }
                }
            }
        }
    }
}
