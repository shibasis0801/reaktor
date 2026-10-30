import dev.shibasis.reaktor.tooling.infra.*;
import java.util.Map;

class SpanReadDiagnostic {
    public static void main(String[] args) {
        try (var session = new InfrastructureSession()) {
            var operation = new InfrastructureOperation.SpanRead(
                new DatabaseConnection.KubernetesService(
                    "/Users/ovd/dev/bestbuds/cloud/k3s/kubeconfig", "reaktor", "clickhouse", 8123),
                "bestbuds", 60, 1, new SpanReadFilter());
            var receipt = new SpanJvmClient(session).read(operation, Map.of(), 15000);
            System.out.println("SpanRead succeeded; receipt length=" + receipt.length());
        } catch (Throwable failure) {
            for (var cause = failure; cause != null; cause = cause.getCause()) {
                System.out.println(cause.getClass().getName());
                for (var frame : cause.getStackTrace()) {
                    if (frame.getClassName().startsWith("dev.shibasis.reaktor.tooling")) {
                        System.out.println("  " + frame);
                    }
                }
            }
            System.exit(1);
        }
    }
}
