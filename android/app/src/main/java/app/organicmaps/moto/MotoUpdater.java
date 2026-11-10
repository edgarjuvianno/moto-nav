package app.organicmaps.moto;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.BuildConfig;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MotoUpdater
{
  public static final class Release
  {
    public final String version;
    public final String notes;
    public final String apkUrl;
    public final long size;
    @Nullable
    public final String sha256;

    Release(@NonNull String version, @NonNull String notes, @NonNull String apkUrl, long size, @Nullable String sha256)
    {
      this.version = version;
      this.notes = notes;
      this.apkUrl = apkUrl;
      this.size = size;
      this.sha256 = sha256;
    }

    public boolean isNewer()
    {
      return compare(version, currentVersion()) > 0;
    }
  }

  public interface CheckCallback
  {
    void onResult(@Nullable Release release, @Nullable String error);
  }

  public interface InstallCallback
  {
    void onProgress(int percent);

    void onStatus(@NonNull String message, boolean finished);
  }

  private static final String TAG = "MotoUpdater";
  private static final String PREFS = "moto";
  private static final String KEY_LAST_CHECK = "update_last_check";
  private static final String KEY_NOTIFIED = "update_notified";
  private static final String ACTION_INSTALL_STATUS = "id.moto.nav.UPDATE_STATUS";
  private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static volatile boolean sBusy;

  private MotoUpdater() {}

  @NonNull
  public static String currentVersion()
  {
    return BuildConfig.MOTO_VERSION;
  }

  public static void cleanup(@NonNull Context context)
  {
    if (sBusy)
      return;
    File dir = new File(context.getCacheDir(), "update");
    EXECUTOR.execute(() -> {
      File[] files = dir.listFiles();
      if (files != null)
        for (File f : files)
          f.delete();
    });
  }

  public static boolean isBusy()
  {
    return sBusy;
  }

  public static void check(@NonNull CheckCallback callback)
  {
    EXECUTOR.execute(() -> {
      Release release = null;
      String error = null;
      try
      {
        release = fetchLatest();
        if (release == null)
          error = "No release with an APK found";
      }
      catch (Exception e)
      {
        Log.w(TAG, "Update check failed", e);
        error = e instanceof java.io.FileNotFoundException ? "No published release found" : "Couldn't reach GitHub";
      }
      Release r = release;
      String err = error;
      MAIN.post(() -> callback.onResult(r, err));
    });
  }

  public static void checkInBackground(@NonNull Context context, @NonNull CheckCallback onNewer)
  {
    SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    long now = System.currentTimeMillis();
    if (sBusy || now - prefs.getLong(KEY_LAST_CHECK, 0) < 6 * 3600_000L)
      return;
    prefs.edit().putLong(KEY_LAST_CHECK, now).apply();
    check((release, error) -> {
      if (release == null || !release.isNewer())
        return;
      if (release.version.equals(prefs.getString(KEY_NOTIFIED, "")))
        return;
      prefs.edit().putString(KEY_NOTIFIED, release.version).apply();
      onNewer.onResult(release, null);
    });
  }

  @Nullable
  private static Release fetchLatest() throws Exception
  {
    HttpURLConnection c = (HttpURLConnection) new URL(BuildConfig.MOTO_UPDATE_API).openConnection();
    c.setConnectTimeout(10_000);
    c.setReadTimeout(15_000);
    c.setRequestProperty("Accept", "application/vnd.github+json");
    c.setRequestProperty("User-Agent", "MotoNav/" + currentVersion());
    String body;
    try (InputStream in = c.getInputStream())
    {
      body = new String(readAll(in), "UTF-8");
    }
    finally
    {
      c.disconnect();
    }
    JSONObject json = new JSONObject(body);
    String tag = json.optString("tag_name", "");
    JSONArray assets = json.optJSONArray("assets");
    if (tag.isEmpty() || assets == null)
      return null;
    for (int i = 0; i < assets.length(); i++)
    {
      JSONObject a = assets.getJSONObject(i);
      String name = a.optString("name", "");
      if (!name.toLowerCase(Locale.ROOT).endsWith(".apk"))
        continue;
      String digest = a.optString("digest", "");
      String sha = digest.startsWith("sha256:") ? digest.substring(7).toLowerCase(Locale.ROOT) : null;
      return new Release(tag.replaceFirst("^[vV]", ""), json.optString("body", ""),
                         a.getString("browser_download_url"), a.optLong("size", 0), sha);
    }
    return null;
  }

  public static void downloadAndInstall(@NonNull Context context, @NonNull Release release,
                                        @NonNull InstallCallback callback)
  {
    if (sBusy)
      return;
    sBusy = true;
    Context app = context.getApplicationContext();
    EXECUTOR.execute(() -> {
      File dir = new File(app.getCacheDir(), "update");
      File apk = new File(dir, "MotoNav-" + release.version + ".apk");
      try
      {
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null)
          for (File f : old)
            if (!f.equals(apk))
              f.delete();
        download(release, apk, callback);
        if (release.sha256 != null && !release.sha256.equals(sha256(apk)))
          throw new IllegalStateException("Checksum mismatch");
        post(callback, "Installing…", false);
        install(app, apk, callback);
      }
      catch (Exception e)
      {
        Log.w(TAG, "Update failed", e);
        apk.delete();
        sBusy = false;
        post(callback, "Update failed: " + (e.getMessage() == null ? "error" : e.getMessage()), true);
      }
    });
  }

  private static void download(@NonNull Release release, @NonNull File out, @NonNull InstallCallback callback)
      throws Exception
  {
    HttpURLConnection c = (HttpURLConnection) new URL(release.apkUrl).openConnection();
    c.setConnectTimeout(15_000);
    c.setReadTimeout(30_000);
    c.setInstanceFollowRedirects(true);
    c.setRequestProperty("User-Agent", "MotoNav/" + currentVersion());
    long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : release.size;
    try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out))
    {
      byte[] buf = new byte[64 * 1024];
      long done = 0;
      int last = -1;
      int n;
      while ((n = in.read(buf)) > 0)
      {
        os.write(buf, 0, n);
        done += n;
        int pct = total > 0 ? (int) (done * 100 / total) : 0;
        if (pct != last)
        {
          last = pct;
          int p = pct;
          MAIN.post(() -> callback.onProgress(p));
        }
      }
    }
    finally
    {
      c.disconnect();
    }
  }

  private static void install(@NonNull Context context, @NonNull File apk, @NonNull InstallCallback callback)
  {
    MotoRoot.check(granted -> EXECUTOR.execute(() -> {
      if (granted)
        installWithRoot(apk, callback);
      else
        installWithSession(context, apk, callback);
    }));
  }

  private static void installWithRoot(@NonNull File apk, @NonNull InstallCallback callback)
  {
    String out = MotoRoot.runSync("pm install -r -i " + BuildConfig.APPLICATION_ID + " \"" + apk.getAbsolutePath() + "\"");
    sBusy = false;
    apk.delete();
    post(callback, out.contains("Success") ? "Updated, restarting…" : "Update failed: " + out.trim(), true);
  }

  private static void installWithSession(@NonNull Context context, @NonNull File apk, @NonNull InstallCallback callback)
  {
    try
    {
      PackageInstaller installer = context.getPackageManager().getPackageInstaller();
      PackageInstaller.SessionParams params =
          new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
      params.setAppPackageName(context.getPackageName());
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
      int id = installer.createSession(params);
      try (PackageInstaller.Session session = installer.openSession(id))
      {
        try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("base.apk", 0, apk.length()))
        {
          byte[] buf = new byte[64 * 1024];
          int n;
          while ((n = in.read(buf)) > 0)
            out.write(buf, 0, n);
          session.fsync(out);
        }
        Intent status = new Intent(ACTION_INSTALL_STATUS).setPackage(context.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                  | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
        session.commit(PendingIntent.getBroadcast(context, id, status, flags).getIntentSender());
      }
      post(callback, "Confirm the update to finish installing", false);
    }
    catch (Exception e)
    {
      Log.w(TAG, "Session install failed", e);
      sBusy = false;
      post(callback, "Update failed: " + (e.getMessage() == null ? "error" : e.getMessage()), true);
    }
  }

  public static final class StatusReceiver extends BroadcastReceiver
  {
    @Override
    public void onReceive(Context context, Intent intent)
    {
      int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
      if (status == PackageInstaller.STATUS_PENDING_USER_ACTION)
      {
        Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
        if (confirm != null)
          context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return;
      }
      sBusy = false;
      if (status != PackageInstaller.STATUS_SUCCESS)
      {
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Toast.makeText(context, "Update not installed" + (msg == null ? "" : ": " + msg), Toast.LENGTH_LONG).show();
      }
    }
  }

  private static void post(@NonNull InstallCallback callback, @NonNull String message, boolean finished)
  {
    MAIN.post(() -> callback.onStatus(message, finished));
  }

  @NonNull
  private static byte[] readAll(@NonNull InputStream in) throws Exception
  {
    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
    byte[] buf = new byte[16 * 1024];
    int n;
    while ((n = in.read(buf)) > 0)
      bos.write(buf, 0, n);
    return bos.toByteArray();
  }

  @NonNull
  private static String sha256(@NonNull File f) throws Exception
  {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(f))
    {
      byte[] buf = new byte[64 * 1024];
      int n;
      while ((n = in.read(buf)) > 0)
        md.update(buf, 0, n);
    }
    StringBuilder sb = new StringBuilder();
    for (byte b : md.digest())
      sb.append(String.format(Locale.ROOT, "%02x", b));
    return sb.toString();
  }

  static int compare(@NonNull String a, @NonNull String b)
  {
    String[] x = a.split("[.\\-]");
    String[] y = b.split("[.\\-]");
    for (int i = 0; i < Math.max(x.length, y.length); i++)
    {
      int p = i < x.length ? parse(x[i]) : 0;
      int q = i < y.length ? parse(y[i]) : 0;
      if (p != q)
        return Integer.compare(p, q);
    }
    return 0;
  }

  private static int parse(@NonNull String s)
  {
    try
    {
      return Integer.parseInt(s.replaceAll("\\D", ""));
    }
    catch (NumberFormatException e)
    {
      return 0;
    }
  }
}
