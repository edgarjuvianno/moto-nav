package app.organicmaps.moto;

import android.util.Log;
import androidx.annotation.NonNull;
import java.io.DataOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MotoRoot
{
  private static final String TAG = "MotoRoot";
  private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

  private MotoRoot() {}

  public static void run(@NonNull String... commands)
  {
    EXECUTOR.execute(() -> {
      try
      {
        Process p = Runtime.getRuntime().exec("su");
        try (DataOutputStream out = new DataOutputStream(p.getOutputStream()))
        {
          for (String c : commands)
            out.writeBytes(c + "\n");
          out.writeBytes("exit\n");
          out.flush();
        }
        int code = p.waitFor();
        Log.i(TAG, "su exit " + code + " for " + String.join("; ", commands));
      }
      catch (Exception e)
      {
        Log.w(TAG, "Root command failed", e);
      }
    });
  }
}
