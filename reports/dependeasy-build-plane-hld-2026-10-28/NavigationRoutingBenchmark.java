import dev.shibasis.reaktor.blueprint.Card;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.*;
import java.security.MessageDigest;

public class NavigationRoutingBenchmark {
    record Route(double sx, double sy, double tx, double ty, List<Card> boxes) {}
    static Method method;
    static List<Route> routes = new ArrayList<>();
    static Object route(Route r) throws Exception { return method.invoke(null, r.sx, r.sy, r.tx, r.ty, r.boxes); }
    public static void main(String[] args) throws Exception {
        method = Class.forName("dev.shibasis.reaktor.blueprint.NavigationLayoutKt").getDeclaredMethod("navigationPath", double.class, double.class, double.class, double.class, List.class);
        method.setAccessible(true);
        for (int size : new int[]{6, 17, 39, 64, 96}) {
            List<Card> boxes = new ArrayList<>();
            int columns = (int)Math.ceil(Math.sqrt(size));
            for(int i=0;i<size;i++) boxes.add(new Card("s"+i, (i%columns)*340.0, (i/columns)*650.0, 280+(i%3)*5, 480+(i%5)*16, List.of(), 0));
            for(int i=0;i<size;i++) {
                Card a=boxes.get(i), b=boxes.get((i*7+3)%size);
                routes.add(new Route(a.getX()+a.getWidth(), a.getY()+15+(i%4)*50, b.getX(), b.getY()+15, boxes));
            }
        }
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(Route r:routes) digest.update((route(r).toString()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for(int round=0;round<3;round++) for(Route r:routes) route(r);
        com.sun.management.ThreadMXBean bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        long thread=Thread.currentThread().threadId(), bytes=bean.getThreadAllocatedBytes(thread), start=System.nanoTime();
        long points=0;
        for(int round=0;round<5;round++) for(Route r:routes) points+=((List<?>)route(r)).size();
        long elapsed=System.nanoTime()-start, allocated=bean.getThreadAllocatedBytes(thread)-bytes;
        System.out.println("{\"routes\":"+routes.size()+",\"repetitions\":5,\"nanoseconds\":"+elapsed+",\"allocatedBytes\":"+allocated+",\"points\":"+points+",\"sha256\":\""+HexFormat.of().formatHex(digest.digest())+"\"}");
    }
}
