import ai.bestbuds.reaktor.kernel.mcp.KernelReadModel;
import ai.bestbuds.reaktor.workbench.operations.OperationalSnapshot;
import kotlinx.serialization.json.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.lang.reflect.Method;

public class RunProjectionBenchmark {
    private static KernelReadModel reader;
    private static Method bounded;
    private static JsonArray read(String variant, String id) throws Exception {
        if (variant.equals("bounded")) {
            @SuppressWarnings("unchecked") var rows = (List<JsonObject>) bounded.invoke(reader, id);
            return new JsonArray(rows);
        }
        var rows = (JsonArray) ((JsonObject) reader.controlPlaneJson(null, null)).get("runs");
        if (id == null) return rows;
        return new JsonArray(rows.stream().filter(row ->
            ((JsonPrimitive) ((JsonObject) row).get("id")).getContent().equals(id)).toList());
    }
    public static void main(String[] args) throws Exception {
        var snapshot = Json.Default.decodeFromString(OperationalSnapshot.Companion.serializer(), Files.readString(Path.of(args[0])));
        reader = new KernelReadModel(() -> snapshot);
        bounded = Arrays.stream(KernelReadModel.class.getDeclaredMethods())
            .filter(m -> m.getName().startsWith("runsJson") && m.getParameterCount() == 1).findFirst().orElseThrow();
        for (String scope : List.of("list", "show")) {
            String id = scope.equals("show") ? "run-249" : null;
            JsonArray expected = read("snapshot", id);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(expected.toString().getBytes(StandardCharsets.UTF_8)));
            for (int rep = -4; rep <= 8; rep++) {
                for (String variant : rep % 2 == 0 ? List.of("snapshot", "bounded") : List.of("bounded", "snapshot")) {
                    JsonArray actual = null;
                    long start = System.nanoTime();
                    for (int iteration = 0; iteration < 4; iteration++) actual = read(variant, id);
                    double seconds = (System.nanoTime() - start) / 4e9;
                    if (!expected.equals(actual)) throw new AssertionError("Projection changed: " + scope + " " + variant);
                    System.out.printf(Locale.ROOT,
                        "{\"scope\":\"%s\",\"rep\":%d,\"variant\":\"%s\",\"seconds\":%.9f,\"rows\":%d,\"equal\":true,\"sha256\":\"%s\"}%n",
                        scope, rep, variant, seconds, actual.size(), digest);
                }
            }
        }
    }
}
