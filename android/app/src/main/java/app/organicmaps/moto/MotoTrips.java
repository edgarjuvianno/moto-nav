package app.organicmaps.moto;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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

  private static final String PREFS = "moto_trips";
  private static final String KEY_TRIPS = "trips";
  private static final String KEY_ACTIVE = "active";
  private static final int MAX_TRIPS = 500;
  private static final float START_SPEED = 10f / 3.6f;
  private static final float MOVING_SPEED = 4f / 3.6f;
  private static final float MAX_ACCURACY = 30f;
  private static final long STOP_AFTER_MS = 5 * 60_000L;
  private static final double MIN_TRIP_METERS = 300;
  private static final long SAVE_EVERY_MS = 30_000L;

  private static MotoTrips sInstance;

  private final SharedPreferences mPrefs;
  private boolean mActive;
  private long mStart;
  private long mLastMove;
  private long mLastFixTime;
  private double mMeters;
  private long mMovingMs;
  private float mMaxKmh;
  private long mLastSave;
  @Nullable
  private Location mLast;

  private MotoTrips(@NonNull Context context)
  {
    mPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
    return mActive;
  }

  public double currentMeters()
  {
    return mActive ? mMeters : 0;
  }

  public long currentStart()
  {
    return mActive ? mStart : 0;
  }

  public void onLocation(@NonNull Location loc)
  {
    if (!loc.hasAccuracy() || loc.getAccuracy() > MAX_ACCURACY)
      return;
    long now = System.currentTimeMillis();
    float speed = loc.hasSpeed() ? loc.getSpeed() : 0f;
    if (!mActive)
    {
      if (speed < START_SPEED)
        return;
      mActive = true;
      mStart = now;
      mLastMove = now;
      mLastFixTime = now;
      mMeters = 0;
      mMovingMs = 0;
      mMaxKmh = 0;
      mLast = loc;
      return;
    }
    long dt = now - mLastFixTime;
    mLastFixTime = now;
    if (speed >= MOVING_SPEED)
    {
      mLastMove = now;
      if (dt > 0 && dt < 60_000L)
        mMovingMs += dt;
    }
    mMaxKmh = Math.max(mMaxKmh, speed * 3.6f);
    if (mLast != null)
    {
      float d = mLast.distanceTo(loc);
      if (d >= Math.max(5f, loc.getAccuracy() * 0.5f) && (speed >= MOVING_SPEED || d > loc.getAccuracy() * 2f))
      {
        mMeters += d;
        mLast = loc;
      }
    }
    else
      mLast = loc;
    if (now - mLastSave > SAVE_EVERY_MS)
      saveActive();
  }

  public void tick()
  {
    if (mActive && System.currentTimeMillis() - mLastMove > STOP_AFTER_MS)
      finish();
  }

  public void finish()
  {
    if (!mActive)
      return;
    mActive = false;
    mLast = null;
    mPrefs.edit().remove(KEY_ACTIVE).apply();
    if (mMeters < MIN_TRIP_METERS)
      return;
    List<Trip> trips = load();
    trips.add(0, new Trip(mStart, mLastMove, mMeters, mMovingMs, mMaxKmh));
    save(trips);
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
  }

  private void save(@NonNull List<Trip> trips)
  {
    JSONArray arr = new JSONArray();
    try
    {
      for (int i = 0; i < Math.min(trips.size(), MAX_TRIPS); i++)
        arr.put(trips.get(i).toJson());
    }
    catch (JSONException ignored)
    {
    }
    mPrefs.edit().putString(KEY_TRIPS, arr.toString()).apply();
  }

  private void saveActive()
  {
    mLastSave = System.currentTimeMillis();
    try
    {
      mPrefs.edit()
          .putString(KEY_ACTIVE, new Trip(mStart, mLastMove, mMeters, mMovingMs, mMaxKmh).toJson().toString())
          .apply();
    }
    catch (JSONException ignored)
    {
    }
  }

  private void restoreActive()
  {
    String raw = mPrefs.getString(KEY_ACTIVE, null);
    if (raw == null)
      return;
    try
    {
      Trip t = Trip.fromJson(new JSONObject(raw));
      if (System.currentTimeMillis() - t.end < STOP_AFTER_MS)
      {
        mActive = true;
        mStart = t.start;
        mLastMove = t.end;
        mLastFixTime = t.end;
        mMeters = t.meters;
        mMovingMs = t.movingMs;
        mMaxKmh = t.maxKmh;
        return;
      }
      mActive = true;
      mStart = t.start;
      mLastMove = t.end;
      mMeters = t.meters;
      mMovingMs = t.movingMs;
      mMaxKmh = t.maxKmh;
      finish();
    }
    catch (JSONException e)
    {
      mPrefs.edit().remove(KEY_ACTIVE).apply();
    }
  }
}
