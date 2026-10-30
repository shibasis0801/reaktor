import dev.shibasis.reaktor.conductor.workspace.*;
import java.nio.file.*;
import java.util.*;
import java.security.*;
import jdk.jfr.*;
public class SourceCaptureCompare {
 static String hash(byte[] bytes) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
 public static void main(String[] args) throws Exception {
  Path data=Files.createTempDirectory(Path.of("/Users/ovd/dev/tmp"),"dependeasy-source-capture-");
  try(Recording recording=new Recording(Configuration.getConfiguration("profile"))) {
   recording.start();
   LocalAgentArtifacts artifacts=new LocalAgentArtifacts(data);
   SourceCandidates source=new SourceCandidates(Path.of("/Users/ovd/dev/bestbuds").toFile(),artifacts);
   long started=System.nanoTime(); AgentCandidate c=source.capture(Collections.emptyList());double elapsed=(System.nanoTime()-started)/1e9;
   String normalized=c.copy(c.getId(),c.getWorkspaceRoot(),c.getBaseCommit(),c.getSourceDigest(),0L,c.getChangedFiles(),c.getDiff(),c.getComplete(),c.getNotices(),c.getSubjects(),c.getSourceRoots(),c.getFingerprintedBySize()).toString();
   String diff=c.getDiff()==null ? "" : artifacts.text(c.getDiff());
   Files.writeString(Path.of(args[1]+".candidate.txt"),normalized);
   Files.writeString(Path.of(args[1]+".result.txt"),elapsed+"\n"+c.getId()+"\n"+hash(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"\n"+hash(diff.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"\n"+c.getComplete()+"\n"+c.getChangedFiles().size()+"\n");
   System.out.println(args[0]+" seconds="+elapsed+" id="+c.getId()+" complete="+c.getComplete()+" notices="+c.getNotices());
   recording.stop();recording.dump(Path.of(args[1]+".jfr"));
  } finally {try(var walk=Files.walk(data)){for(Path file:walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);}}
 }
}
