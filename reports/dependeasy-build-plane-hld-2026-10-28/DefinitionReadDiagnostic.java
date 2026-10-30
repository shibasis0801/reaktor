import dev.shibasis.reaktor.tooling.ProcessQuery;
import java.io.File;
import java.util.List;
public class DefinitionReadDiagnostic {
 public static void main(String[] args) {
  for(int i=0;i<3;i++) {
   long start=System.nanoTime();
   var r=ProcessQuery.INSTANCE.read(List.of("git","ls-files","--others","--ignored","--exclude-standard","--directory","--no-empty-directory","-z","--","."),new File("/Users/ovd/dev/reaktor"),10000,1048576);
   System.out.println("sample="+i+" status="+r.getStatus()+" chars="+r.getOutput().length()+" ms="+(System.nanoTime()-start)/1000000.0);
  }
 }
}