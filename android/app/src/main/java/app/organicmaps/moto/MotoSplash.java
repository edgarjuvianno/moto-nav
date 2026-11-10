package app.organicmaps.moto;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MotoSplash
{
  public interface Callback
  {
    void onDone(boolean ok);
  }

  public static final int WIDTH = 2160;
  public static final int HEIGHT = 1080;
  private static final String TAG = "MotoSplash";
  private static final String PREFS = "moto";
  private static final String KEY_PETALS = "splash_petals";
  private static final String[] EXTS = {".webp", ".png", ".jpg"};
  private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
  private static final Handler MAIN = new Handler(Looper.getMainLooper());

  private MotoSplash() {}

  @NonNull
  static String name(boolean dark, boolean backdrop)
  {
    return "moto_splash_" + (dark ? "dark" : "light") + (backdrop ? "_bg" : "");
  }

  @Nullable
  private static File dir(@NonNull Context context)
  {
    return context.getExternalFilesDir("splash");
  }

  @Nullable
  static File find(@NonNull Context context, @NonNull String name)
  {
    File dir = dir(context);
    if (dir == null)
      return null;
    for (String ext : EXTS)
    {
      File f = new File(dir, name + ext);
      if (f.isFile())
        return f;
    }
    return null;
  }

  public static boolean hasCustom(@NonNull Context context, boolean dark)
  {
    return find(context, name(dark, false)) != null;
  }

  public static boolean hasAnyCustom(@NonNull Context context)
  {
    return hasCustom(context, false) || hasCustom(context, true);
  }

  public static boolean petals(@NonNull Context context)
  {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PETALS, true);
  }

  public static void setPetals(@NonNull Context context, boolean on)
  {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_PETALS, on).apply();
  }

  @Nullable
  public static Bitmap thumbnail(@NonNull Context context, boolean dark)
  {
    File f = find(context, name(dark, false));
    if (f == null)
      return null;
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inSampleSize = 8;
    return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
  }

  public static void save(@NonNull Context context, @NonNull Uri uri, boolean dark, @NonNull Callback callback)
  {
    Context app = context.getApplicationContext();
    EXECUTOR.execute(() -> {
      boolean ok = false;
      try
      {
        Bitmap src = decode(app, uri);
        if (src != null)
        {
          Bitmap art = cropFill(src, WIDTH, HEIGHT);
          src.recycle();
          Bitmap small = Bitmap.createScaledBitmap(art, WIDTH / 16, HEIGHT / 16, true);
          Bitmap backdrop = Bitmap.createScaledBitmap(small, WIDTH, HEIGHT, true);
          small.recycle();
          write(app, name(dark, false), art);
          write(app, name(dark, true), backdrop);
          art.recycle();
          backdrop.recycle();
          ok = true;
        }
      }
      catch (Exception e)
      {
        Log.w(TAG, "Unable to save splash", e);
      }
      boolean result = ok;
      MAIN.post(() -> callback.onDone(result));
    });
  }

  public static void reset(@NonNull Context context)
  {
    File dir = dir(context);
    File[] files = dir == null ? null : dir.listFiles();
    if (files != null)
      for (File f : files)
        f.delete();
  }

  @Nullable
  private static Bitmap decode(@NonNull Context context, @NonNull Uri uri) throws Exception
  {
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    try (InputStream in = context.getContentResolver().openInputStream(uri))
    {
      BitmapFactory.decodeStream(in, null, bounds);
    }
    int sample = 1;
    while (bounds.outWidth / (sample * 2) >= WIDTH && bounds.outHeight / (sample * 2) >= HEIGHT)
      sample *= 2;
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inSampleSize = sample;
    try (InputStream in = context.getContentResolver().openInputStream(uri))
    {
      return BitmapFactory.decodeStream(in, null, o);
    }
  }

  @NonNull
  private static Bitmap cropFill(@NonNull Bitmap src, int w, int h)
  {
    float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
    int cw = Math.round(w / scale);
    int ch = Math.round(h / scale);
    int cx = (src.getWidth() - cw) / 2;
    int cy = (src.getHeight() - ch) / 2;
    Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    new Canvas(out).drawBitmap(src, new Rect(cx, cy, cx + cw, cy + ch), new Rect(0, 0, w, h),
                               new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
    return out;
  }

  private static void write(@NonNull Context context, @NonNull String name, @NonNull Bitmap bitmap) throws Exception
  {
    File dir = dir(context);
    if (dir == null)
      throw new IllegalStateException("No storage");
    for (String ext : EXTS)
      new File(dir, name + ext).delete();
    File tmp = new File(dir, name + ".tmp");
    Bitmap.CompressFormat format = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                                     ? Bitmap.CompressFormat.WEBP_LOSSY
                                     : Bitmap.CompressFormat.WEBP;
    try (FileOutputStream out = new FileOutputStream(tmp))
    {
      bitmap.compress(format, 90, out);
    }
    if (!tmp.renameTo(new File(dir, name + ".webp")))
      throw new IllegalStateException("Rename failed");
  }
}
