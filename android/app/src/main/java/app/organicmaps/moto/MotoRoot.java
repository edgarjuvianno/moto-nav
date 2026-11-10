package app.organicmaps.moto;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MotoRoot
{
  private static final String TAG = "MotoRoot";
  private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

  public interface Callback
  {
    void onResult(boolean granted);
  }

  @Nullable
  private static volatile Boolean sGranted;

  private MotoRoot() {}

  @NonNull
  public static String runSync(@NonNull String command)
  {
    try
    {
      Process p = Runtime.getRuntime().exec(new String[] {"su", "-c", command + " 2>&1"});
      StringBuilder sb = new StringBuilder();
      try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream())))
      {
        String line;
        while ((line = r.readLine()) != null)
          sb.append(line).append('\n');
      }
      p.waitFor(180, TimeUnit.SECONDS);
      Log.i(TAG, command + " -> " + sb.toString().trim());
      return sb.toString();
    }
    catch (Exception e)
    {
      Log.w(TAG, "Root command failed", e);
      return "no root access";
    }
  }

  public static void check(@NonNull Callback callback)
  {
    Handler main = new Handler(Looper.getMainLooper());
    Boolean cached = sGranted;
    if (cached != null)
    {
      main.post(() -> callback.onResult(cached));
      return;
    }
    EXECUTOR.execute(() -> {
      boolean ok = false;
      try
      {
        Process p = Runtime.getRuntime().exec(new String[] {"su", "-c", "id -u"});
        String line;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream())))
        {
          line = r.readLine();
        }
        ok = p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0 && line != null && line.trim().equals("0");
        if (!ok)
          p.destroy();
      }
      catch (Exception e)
      {
        Log.i(TAG, "No root: " + e.getMessage());
      }
      sGranted = ok;
      boolean result = ok;
      main.post(() -> callback.onResult(result));
    });
  }

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
