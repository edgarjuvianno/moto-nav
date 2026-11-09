package app.organicmaps.moto;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MotoVoices
{
  public static final class Voice
  {
    public final String id;
    public final String name;
    public final String description;

    Voice(String id, String name, String description)
    {
      this.id = id;
      this.name = name;
      this.description = description;
    }

    @NonNull
    String repo()
    {
      return "https://huggingface.co/csukuangfj/vits-piper-en_US-" + id + "/resolve/main/";
    }

    @NonNull
    String modelFile()
    {
      return "en_US-" + id + ".onnx";
    }
  }

  public interface Listener
  {
    void onVoicesChanged();
  }

  public static final List<Voice> CATALOG = Arrays.asList(
      new Voice("amy-medium", "Amy", "Female · warm and friendly"),
      new Voice("lessac-medium", "Lessac", "Female · clear and neutral"),
      new Voice("kristin-medium", "Kristin", "Female · bright"),
      new Voice("hfc_female-medium", "HFC Female", "Female · natural"),
      new Voice("ryan-medium", "Ryan", "Male · calm"),
      new Voice("joe-medium", "Joe", "Male · relaxed"),
      new Voice("john-medium", "John", "Male · deep"),
      new Voice("hfc_male-medium", "HFC Male", "Male · natural"));

  private static final String TAG = "MotoVoices";
  private static final String PREFS = "moto";
  private static final String KEY_VOICE = "voice_id";
  private static final String KEY_SPEED = "voice_speed";
  private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
  private static final Map<String, Integer> PROGRESS = new ConcurrentHashMap<>();
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  @Nullable
  private static Listener sListener;

  private MotoVoices() {}

  public static void setListener(@Nullable Listener listener)
  {
    sListener = listener;
  }

  @NonNull
  static File dir(@NonNull Context context, @NonNull Voice v)
  {
    return new File(context.getFilesDir(), "voices/" + v.id);
  }

  @Nullable
  public static Voice find(@Nullable String id)
  {
    for (Voice v : CATALOG)
      if (v.id.equals(id))
        return v;
    return null;
  }

  public static boolean isInstalled(@NonNull Context context, @NonNull Voice v)
  {
    File d = dir(context, v);
    return new File(d, v.modelFile()).isFile() && new File(d, "tokens.txt").isFile();
  }

  public static int progress(@NonNull Voice v)
  {
    Integer p = PROGRESS.get(v.id);
    return p == null ? -1 : p;
  }

  @Nullable
  public static Voice selected(@NonNull Context context)
  {
    Voice v = find(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VOICE, null));
    return v != null && isInstalled(context, v) ? v : null;
  }

  public static void select(@NonNull Context context, @NonNull Voice v)
  {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_VOICE, v.id).apply();
    MotoTtsService.reset();
    notifyChanged();
  }

  public static float speed(@NonNull Context context)
  {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_SPEED, 1.0f);
  }

  public static void setSpeed(@NonNull Context context, float speed)
  {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_SPEED, speed).apply();
  }

  public static void delete(@NonNull Context context, @NonNull Voice v)
  {
    boolean wasSelected = selected(context) == v;
    File d = dir(context, v);
    File[] files = d.listFiles();
    if (files != null)
      for (File f : files)
        if (!f.delete())
          Log.w(TAG, "Unable to delete " + f);
    if (!d.delete())
      Log.w(TAG, "Unable to delete " + d);
    if (wasSelected)
      MotoTtsService.reset();
    notifyChanged();
  }

  public static void download(@NonNull Context context, @NonNull Voice v)
  {
    if (PROGRESS.containsKey(v.id))
      return;
    Context app = context.getApplicationContext();
    PROGRESS.put(v.id, 0);
    notifyChanged();
    EXECUTOR.execute(() -> {
      File d = dir(app, v);
      try
      {
        if (!d.exists() && !d.mkdirs())
          throw new IOException("mkdirs " + d);
        fetch(v.repo() + "tokens.txt", new File(d, "tokens.txt"), null);
        fetch(v.repo() + v.modelFile(), new File(d, v.modelFile()), p -> {
          PROGRESS.put(v.id, p);
          notifyChanged();
        });
        PROGRESS.remove(v.id);
        if (selected(app) == null)
          select(app, v);
        else
          notifyChanged();
      }
      catch (IOException e)
      {
        Log.w(TAG, "Download failed for " + v.id, e);
        PROGRESS.remove(v.id);
        new File(d, v.modelFile()).delete();
        MAIN.post(() -> android.widget.Toast.makeText(app, "Download failed. Check your connection.",
                                                      android.widget.Toast.LENGTH_SHORT).show());
        notifyChanged();
      }
    });
  }

  private interface ProgressSink
  {
    void onProgress(int percent);
  }

  private static void fetch(@NonNull String url, @NonNull File target, @Nullable ProgressSink sink) throws IOException
  {
    File tmp = new File(target.getPath() + ".part");
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setInstanceFollowRedirects(true);
    c.setConnectTimeout(15000);
    c.setReadTimeout(30000);
    int code = c.getResponseCode();
    if (code / 100 == 3)
    {
      String next = c.getHeaderField("Location");
      c.disconnect();
      if (next == null)
        throw new IOException("Redirect without location");
      c = (HttpURLConnection) new URL(new URL(url), next).openConnection();
      c.setConnectTimeout(15000);
      c.setReadTimeout(30000);
      code = c.getResponseCode();
    }
    if (code != 200)
      throw new IOException("HTTP " + code + " for " + url);
    long total = c.getContentLengthLong();
    long done = 0;
    int last = -1;
    try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp))
    {
      byte[] buf = new byte[65536];
      int n;
      while ((n = in.read(buf)) > 0)
      {
        out.write(buf, 0, n);
        done += n;
        if (sink != null && total > 0)
        {
          int p = (int) (done * 100 / total);
          if (p != last)
          {
            last = p;
            sink.onProgress(p);
          }
        }
      }
    }
    finally
    {
      c.disconnect();
    }
    if (!tmp.renameTo(target))
      throw new IOException("rename " + tmp);
  }

  private static void notifyChanged()
  {
    MAIN.post(() -> {
      Listener l = sListener;
      if (l != null)
        l.onVoicesChanged();
    });
  }
}
