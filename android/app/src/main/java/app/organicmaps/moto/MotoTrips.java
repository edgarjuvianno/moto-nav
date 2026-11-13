package app.organicmaps.moto;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Build;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class MotoTrips
{
  public static final class Trip
  {
    public final long start;
    public final long end;
    public final double meters;
    public final long movingMs;
    public final float maxKmh;

    Trip(long start, long end, double meters, long movingMs, float maxKmh)
    {
      this.start = start;
      this.end = end;
      this.meters = meters;
      this.movingMs = movingMs;
      this.maxKmh = maxKmh;
    }

    public float avgKmh()
    {
      return movingMs <= 0 ? 0f : (float) (meters / (movingMs / 1000.0) * 3.6);
    }

    @NonNull
    JSONObject toJson() throws JSONException
    {
      return new JSONObject().put("s", start).put("e", end).put("m", meters).put("mv", movingMs).put("x", maxKmh);
    }

    @NonNull
    static Trip fromJson(@NonNull JSONObject o)
    {
      return new Trip(o.optLong("s"), o.optLong("e"), o.optDouble("m"), o.optLong("mv"), (float) o.optDouble("x"));
    }
  }

  private static final String TAG = "MotoTrips";
  private static final String PREFS = "moto_trips";
  private static final String KEY_TRIPS = "trips";
  private static final String KEY_ACTIVE = "active";
  private static final int MAX_TRIPS = 500;
  private static final double MIN_TRIP_METERS = 300;
  private static final long SAVE_EVERY_MS = 30_000L;

  private static MotoTrips sInstance;

  private final SharedPreferences mPrefs;
  private final File mSamplesDir;
  private final MotoTripTracker mTracker = new MotoTripTracker();
  private long mLastSave;
  // Per-second chart data of the ride in progress.
  @Nullable
  private MotoTripSamples.Recorder mSamples;

  private MotoTrips(@NonNull Context context)
  {
    mPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    mSamplesDir = new File(context.getApplicationContext().getFilesDir(), "trips");
    restoreActive();
  }

  @NonNull
  public static synchronized MotoTrips get(@NonNull Context context)
  {
    if (sInstance == null)
      sInstance = new MotoTrips(context);
    return sInstance;
  }

  public boolean isRecording()
  {
    return mTracker.active;
  }

  public double currentMeters()
  {
    return mTracker.active ? mTracker.meters : 0;
  }

  public long currentStart()
  {
    return mTracker.active ? mTracker.start : 0;
  }

  public void onLocation(@NonNull Location loc)
  {
    if (!loc.hasAccuracy())
      return;
    long now = System.currentTimeMillis();
    float speedAccuracy = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && loc.hasSpeedAccuracy()
                            ? loc.getSpeedAccuracyMetersPerSecond()
                            : Float.NaN;
    mTracker.onFix(new MotoTripTracker.Fix(now, loc.getLatitude(), loc.getLongitude(), loc.getAccuracy(),
                                           loc.hasSpeed() ? loc.getSpeed() : 0f, speedAccuracy));
    if (!mTracker.active)
      return;
    if (mSamples == null || mSamples.start() != mTracker.start)
      mSamples = new MotoTripSamples.Recorder(mTracker.start);
    mSamples.onFix(now, loc.hasSpeed() ? loc.getSpeed() : Float.NaN,
                   loc.hasAltitude() ? (float) loc.getAltitude() : Float.NaN);
    if (now - mLastSave > SAVE_EVERY_MS)
      saveActive();
  }

  // Gravity in device axes and the display rotation, for the lean chart.
  public void onGravity(float x, float y, int rotation)
  {
    if (mTracker.active && mSamples != null)
      mSamples.onGravity(System.currentTimeMillis(), x, y, rotation);
  }

  // Chart data of a finished trip, or null for trips recorded before charts existed.
  @Nullable
  public MotoTripSamples.Series series(@NonNull Trip trip)
  {
    MotoTripSamples.Raw raw = readSamples(trip.start);
    return raw == null ? null : MotoTripSamples.analyze(raw);
  }

  @Nullable
  private MotoTripSamples.Raw readSamples(long start)
  {
    File file = samplesFile(start);
    if (!file.exists())
      return null;
    try (FileInputStream in = new FileInputStream(file))
    {
      byte[] data = new byte[(int) file.length()];
      int read = 0;
      while (read < data.length)
      {
        int n = in.read(data, read, data.length - read);
        if (n < 0)
          break;
        read += n;
      }
      return MotoTripSamples.decode(data);
    }
    catch (IOException e)
    {
      Log.w(TAG, "Unable to read trip samples", e);
      return null;
    }
  }

  @NonNull
  private File samplesFile(long start)
  {
    return new File(mSamplesDir, start + ".bin");
  }

  private void writeSamples()
  {
    MotoTripSamples.Recorder samples = mSamples;
    if (samples == null)
      return;
    if (!mSamplesDir.isDirectory() && !mSamplesDir.mkdirs())
      return;
    try (FileOutputStream out = new FileOutputStream(samplesFile(samples.start())))
    {
      out.write(MotoTripSamples.encode(samples.raw()));
    }
    catch (IOException e)
    {
      Log.w(TAG, "Unable to save trip samples", e);
    }
  }

  public void tick()
  {
    if (mTracker.shouldFinish(System.currentTimeMillis()))
      finish();
  }

  public void finish()
  {
    if (!mTracker.active)
      return;
    mTracker.stop();
    mPrefs.edit().remove(KEY_ACTIVE).apply();
    if (mTracker.meters < MIN_TRIP_METERS)
    {
      samplesFile(mTracker.start).delete();
      mSamples = null;
      return;
    }
    writeSamples();
    mSamples = null;
    List<Trip> trips = load();
    trips.add(0, snapshot());
    save(trips);
  }

  @NonNull
  private Trip snapshot()
  {
    return new Trip(mTracker.start, mTracker.lastMove, mTracker.meters, mTracker.movingMs, mTracker.maxKmh);
  }

  @NonNull
  public List<Trip> load()
  {
    List<Trip> result = new ArrayList<>();
    try
    {
      JSONArray arr = new JSONArray(mPrefs.getString(KEY_TRIPS, "[]"));
      for (int i = 0; i < arr.length(); i++)
        result.add(Trip.fromJson(arr.getJSONObject(i)));
    }
    catch (JSONException ignored)
    {
    }
    return result;
  }

  public void delete(@NonNull Trip trip)
  {
    List<Trip> trips = load();
    trips.removeIf(t -> t.start == trip.start);
    save(trips);
    samplesFile(trip.start).delete();
  }

  private void save(@NonNull List<Trip> trips)
  {
    JSONArray arr = new JSONArray();
    try
    {
      for (int i = 0; i < Math.min(trips.size(), MAX_TRIPS); i++)
        arr.put(trips.get(i).toJson());
      for (int i = MAX_TRIPS; i < trips.size(); i++)
        samplesFile(trips.get(i).start).delete();
    }
    catch (JSONException ignored)
    {
    }
    mPrefs.edit().putString(KEY_TRIPS, arr.toString()).apply();
  }

  private void saveActive()
  {
    mLastSave = System.currentTimeMillis();
    writeSamples();
    try
    {
      mPrefs.edit()
          .putString(KEY_ACTIVE, snapshot().toJson().toString())
          .apply();
    }
    catch (JSONException ignored)
    {
    }
  }

  private void restoreSamples(long start)
  {
    MotoTripSamples.Raw raw = readSamples(start);
    if (raw != null)
      mSamples = new MotoTripSamples.Recorder(start, raw);
  }

  private void restoreActive()
  {
    String raw = mPrefs.getString(KEY_ACTIVE, null);
    if (raw == null)
      return;
    try
    {
      Trip t = Trip.fromJson(new JSONObject(raw));
      mTracker.restore(t.start, t.end, t.meters, t.movingMs, t.maxKmh);
      restoreSamples(t.start);
      tick();
    }
    catch (JSONException e)
    {
      mPrefs.edit().remove(KEY_ACTIVE).apply();
    }
  }
}
