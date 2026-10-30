import dev.shibasis.dependeasy.model.BuildModelReader;
import kotlinx.serialization.json.*;
import java.io.File;
import java.nio.file.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ReceiptReadBenchmark {
    private static String text(JsonElement element) { return ((JsonPrimitive)element).getContent(); }
    private static String field(JsonObject value, String name) { return text(value.get(name)); }
    private static String identity(JsonElement value) { return value instanceof JsonObject object ? field(object,"name") : text(value); }
    private static Map<String, String> latest(JsonArray receipts, String environment) {
        Map<String, JsonObject> result = new TreeMap<>();
        for (var value : receipts) {
            var receipt = (JsonObject)value;
            if (!identity(receipt.get("environment")).equals(environment)) continue;
            String service = identity(receipt.get("service"));
            var previous = result.get(service);
            if (previous == null || Long.parseLong(field(receipt,"finishedAtEpochMillis")) > Long.parseLong(field(previous,"finishedAtEpochMillis"))) result.put(service,receipt);
        }
        Map<String,String> normalized = new TreeMap<>();
        result.forEach((key,value) -> normalized.put(key,value.toString()));
        return normalized;
    }
    public static void main(String[] args) throws Exception {
        var root = new File(args[0]);
        var nodes = Files.readAllLines(Path.of(args[1]));
        var reader = BuildModelReader.INSTANCE;
        Map<String, Map<String,String>> expected = null;
        for (int rep=0; rep<=4; rep++) for (String variant : rep%2==0 ? List.of("per-node","batch") : List.of("batch","per-node")) {
            long start = System.nanoTime();
            Map<String, Map<String,String>> joined = new TreeMap<>();
            if (variant.equals("batch")) {
                var result = reader.deploymentReceipts(root,nodes,"dev");
                var receipts = (JsonObject)result.get("receipts");
                for(var node:nodes) joined.put(node,latest((JsonArray)receipts.get(node),"dev"));
            } else {
                for(var node:nodes) {
                    var result = reader.nodeDetails(root,node,null,0,10);
                    var receipts = (JsonArray)result.get("deploymentReceipts");
                    joined.put(node,latest(receipts==null ? new JsonArray(List.of()) : receipts,"dev"));
                }
            }
            double seconds=(System.nanoTime()-start)/1e9;
            if(expected==null)expected=joined;
            boolean equal=expected.equals(joined);
            var digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(joined.toString().getBytes(StandardCharsets.UTF_8)));
            System.out.printf(Locale.ROOT,"{\"rep\":%d,\"variant\":\"%s\",\"seconds\":%.9f,\"nodes\":%d,\"joinedServices\":%d,\"joinsEqual\":%s,\"joinSha256\":\"%s\"}%n",rep,variant,seconds,nodes.size(),joined.values().stream().mapToInt(Map::size).sum(),equal,digest);
            System.out.flush();
        }
    }
}
