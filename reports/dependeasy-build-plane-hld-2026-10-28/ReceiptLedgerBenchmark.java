import java.io.File;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Full ledger reads, isolated bytecode variants, one unchanged receipt history. */
public final class ReceiptLedgerBenchmark {
    static final class Variant implements AutoCloseable {
        final URLClassLoader loader;
        final Object ledger, json, serializer;
        final Method list, show, encode;
        Variant(Path root, Path runtime, Path overlay) throws Exception {
            List<URL> urls = new ArrayList<>();
            if (overlay != null) urls.add(overlay.toUri().toURL());
            try (var paths = Files.list(runtime)) {
                for (Path jar : paths.sorted().toList()) urls.add(jar.toUri().toURL());
            }
            loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
            Class<?> type = loader.loadClass("dev.shibasis.reaktor.tooling.GradleReceiptRunLedger");
            ledger = type.getConstructor(File.class).newInstance(root.toFile());
            list = Arrays.stream(type.getMethods()).filter(m -> m.getName().startsWith("runs-")).findFirst().orElseThrow();
            show = Arrays.stream(type.getMethods()).filter(m -> m.getName().startsWith("run-")).findFirst().orElseThrow();
            Class<?> run = loader.loadClass("dev.shibasis.reaktor.tooling.TaskRun");
            Object companion = run.getField("Companion").get(null);
            Object element = companion.getClass().getMethod("serializer").invoke(companion);
            serializer = loader.loadClass("kotlinx.serialization.builtins.BuiltinSerializersKt")
                .getMethod("ListSerializer", loader.loadClass("kotlinx.serialization.KSerializer")).invoke(null, element);
            Class<?> jsonType = loader.loadClass("kotlinx.serialization.json.Json");
            json = jsonType.getField("Default").get(null);
            encode = jsonType.getMethod("encodeToString", loader.loadClass("kotlinx.serialization.SerializationStrategy"), Object.class);
        }
        List<?> read(int limit, String id) throws Exception {
            return id == null ? (List<?>) list.invoke(ledger, null, limit, null)
                : Collections.singletonList(show.invoke(ledger, id, null));
        }
        String text(List<?> result) throws Exception { return (String) encode.invoke(json, serializer, result); }
        public void close() throws Exception { loader.close(); }
    }
    static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        try (Variant before = new Variant(root, Path.of(args[1]), null);
             Variant after = new Variant(root, Path.of(args[1]), Path.of(args[2]))) {
            var latest = before.read(1, null).getFirst();
            String id = (String) Arrays.stream(latest.getClass().getMethods())
                .filter(m -> m.getName().startsWith("getId-")).findFirst().orElseThrow().invoke(latest);
            boolean validate = args.length > 3 && args[3].equals("validate");
            for (String scope : validate ? List.of("list5") : List.of("list5", "list1000", "show")) {
                int limit = scope.equals("list5") ? 5 : 1000;
                String exactId = scope.equals("show") ? id : null;
                for (int rep = validate ? 0 : -2; rep < (validate ? 1 : 4); rep++) {
                    List<String> order = rep % 2 == 0 ? List.of("before", "after") : List.of("after", "before");
                    String expected = null;
                    for (String name : order) {
                        Variant variant = name.equals("before") ? before : after;
                        long started = System.nanoTime();
                        List<?> result = variant.read(limit, exactId);
                        double seconds = (System.nanoTime() - started) / 1e9;
                        String text = variant.text(result);
                        if (expected != null && !expected.equals(text)) throw new AssertionError("Ledger output differs: " + scope);
                        expected = text;
                        System.out.printf(Locale.ROOT,
                            "{\"scope\":\"%s\",\"rep\":%d,\"variant\":\"%s\",\"seconds\":%.9f,\"rows\":%d,\"sha256\":\"%s\"}%n",
                            scope, rep, name, seconds, result.size(), sha(text));
                        System.out.flush();
                    }
                }
            }
        }
    }
}
