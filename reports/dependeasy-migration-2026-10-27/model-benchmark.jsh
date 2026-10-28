import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import ai.bestbuds.reaktor.workbench.agent.*;
import dev.shibasis.dependeasy.model.*;
var root = new File("/Users/ovd/dev/bestbuds");
var file = root.toPath().resolve(BuildModelReader.RelativePath);
var before = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
void measure(String name, Runnable work) {
    for (int i = 0; i < 5; i++) work.run();
    double[] samples = new double[20];
    for (int i = 0; i < samples.length; i++) {
        long started = System.nanoTime();
        work.run();
        samples[i] = (System.nanoTime() - started) / 1000000.0;
    }
    double[] sorted = samples.clone(); Arrays.sort(sorted);
    System.out.println("MEASURE " + name + " medianMs=" + (sorted[9]+sorted[10])/2 + " minMs=" + sorted[0] + " maxMs=" + sorted[19] + " rawMs=" + Arrays.toString(samples));
}
System.out.println("MODEL_BYTES="+Files.size(file));
measure("cached-content-validated-model", () -> BuildModels.INSTANCE.read(root));
measure("parse-model", () -> BuildModelReader.INSTANCE.readAllowingStale(root));
var model = BuildModelReader.INSTANCE.readAllowingStale(root);
measure("declaration-freshness", () -> BuildModelReader.INSTANCE.changedDeclarations(root, model));
System.out.println("MODEL_STABLE=" + Arrays.equals(before, MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
/exit
