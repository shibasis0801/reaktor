import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;
import kotlinx.serialization.KSerializer;
import kotlinx.serialization.descriptors.SerialDescriptor;
import dev.shibasis.reaktor.service.OperationKt;

public class SchemaFingerprintBenchmark {
    private static volatile long sink;

    public static void main(String[] args) throws Exception {
        List<SerialDescriptor> descriptors = new ArrayList<>();
        Map<String,String> results = new TreeMap<>();
        List<String> generic = new ArrayList<>();
        @SuppressWarnings("unchecked")
        Map<String,SerialDescriptor> cases = (Map<String,SerialDescriptor>)Class
            .forName("dev.shibasis.reaktor.service.SchemaFingerprintFixturesKt")
            .getMethod("fingerprintCases").invoke(null);
        cases.forEach((name,descriptor) -> {
            descriptors.add(descriptor);
            results.put("fixture:"+name, OperationKt.fingerprint(descriptor));
        });
        for (String name : args) {
            try (JarFile jar = new JarFile(name)) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String entry = entries.nextElement().getName();
                    if (!entry.endsWith("$$serializer.class")) continue;
                    String binary = entry.substring(0, entry.length()-6).replace('/', '.');
                    try {
                        Class<?> type = Class.forName(binary);
                        Object instance = type.getField("INSTANCE").get(null);
                        SerialDescriptor descriptor = ((KSerializer<?>)instance).getDescriptor();
                        descriptors.add(descriptor);
                        results.put(binary, OperationKt.fingerprint(descriptor));
                    } catch (NoSuchFieldException ex) {
                        generic.add(binary);
                    }
                }
            }
        }
        if (descriptors.size() < 100) throw new AssertionError(descriptors.size());
        StringBuilder golden = new StringBuilder();
        results.forEach((key,value) -> golden.append(key).append('=').append(value).append('\n'));
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(golden.toString().getBytes(StandardCharsets.UTF_8)));
        int repeats = Integer.parseInt(System.getenv().getOrDefault("FINGERPRINT_REPEATS", "20"));
        for (int n=0;n<5;n++) exercise(descriptors, 10);
        var bean = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        long beforeBytes = bean.getThreadAllocatedBytes(thread);
        long start = System.nanoTime();
        exercise(descriptors, repeats);
        long nanos = System.nanoTime()-start;
        long allocated = bean.getThreadAllocatedBytes(thread)-beforeBytes;
        System.out.println("{\"descriptors\":"+descriptors.size()+",\"distinct\":"+results.size()
            +",\"genericSkipped\":"+generic.size()+",\"fingerprintsSha256\":\""+digest
            +"\",\"measuredCalls\":"+(descriptors.size()*repeats)+",\"nanos\":"+nanos
            +",\"allocatedBytes\":"+allocated+",\"sink\":"+sink+"}");
        if (System.getenv("FINGERPRINT_GOLDEN") != null)
            Files.writeString(Path.of(System.getenv("FINGERPRINT_GOLDEN")),golden.toString());
    }

    private static void exercise(List<SerialDescriptor> descriptors, int repeats) {
        long sum=0;
        for (int n=0;n<repeats;n++)
            for (SerialDescriptor descriptor : descriptors)
                sum += OperationKt.fingerprint(descriptor).hashCode();
        sink=sum;
    }
}
