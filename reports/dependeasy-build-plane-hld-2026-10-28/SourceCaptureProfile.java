import dev.shibasis.reaktor.conductor.workspace.*;
import java.nio.file.*;
import java.util.*;
import jdk.jfr.*;
public class SourceCaptureProfile {
 public static void main(String[] args) throws Exception {
  Path data=Files.createTempDirectory(Path.of("/Users/ovd/dev/tmp"),"dependeasy-source-capture-");
  try(Recording recording=new Recording(Configuration.getConfiguration("profile"))) {
   recording.start();
   SourceCandidates source=new SourceCandidates(Path.of("/Users/ovd/dev/bestbuds").toFile(),new LocalAgentArtifacts(data));
   for(int i=0;i<3;i++) {long started=System.nanoTime(); AgentCandidate c=source.capture(Collections.emptyList());
    System.out.println("sample="+i+" seconds="+(System.nanoTime()-started)/1e9+" complete="+c.getComplete()+" id="+c.getId()+" notices="+c.getNotices()+" files="+c.getChangedFiles().size());
   }
   recording.stop();recording.dump(Path.of(args[0]));
  } finally {try(var walk=Files.walk(data)){for(Path file:walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);}}
 }
}
