package app.organicmaps.moto;

import androidx.annotation.Nullable;

// Pure trip state machine, kept free of Android types so GPS noise handling can be unit tested.
final class MotoTripTracker
{
  static final class Fix
  {
    final long time;
    final double lat;
    final double lon;
    final float accuracy;
    final float speed;
    // NaN when the receiver does not report it.
    final float speedAccuracy;

    Fix(long time, double lat, double lon, float accuracy, float speed, float speedAccuracy)
    {
      this.time = time;
      this.lat = lat;
      this.lon = lon;
      this.accuracy = accuracy;
      this.speed = speed;
      this.speedAccuracy = speedAccuracy;
    }

    // A stationary receiver reports speeds of 1-1.5 m/s with a ~2 m/s speed accuracy; subtracting it removes that.
    float effectiveSpeed()
    {
      return Float.isNaN(speedAccuracy) ? speed : Math.max(0f, speed - speedAccuracy);
    }
  }

  static final float MAX_ACCURACY = 20f;
  static final float START_SPEED = 10f / 3.6f;
  static final float MOVING_SPEED = 4f / 3.6f;
  static final double START_DISTANCE = 50;
  static final long CANDIDATE_MS = 60_000L;
  static final long STOP_AFTER_MS = 5 * 60_000L;

  boolean active;
  long start;
  long lastMove;
  long lastFixTime;
  double meters;
  long movingMs;
  float maxKmh;

  @Nullable
  private Fix mCandidate;
  private float mCandidateMaxKmh;
  // Last point counted into the distance; it stays put while stopped so position drift is not added.
  @Nullable
  private Fix mAnchor;

  // Returns true when this fix confirmed a new trip.
  boolean onFix(Fix f)
  {
    if (f.accuracy > MAX_ACCURACY)
      return false;
    float speed = f.effectiveSpeed();
    boolean moving = speed >= MOVING_SPEED;
    if (!active)
      return tryStart(f, speed, moving);
    long dt = f.time - lastFixTime;
    lastFixTime = f.time;
    if (!moving)
      return false;
    lastMove = f.time;
    if (dt > 0 && dt < 60_000L)
      movingMs += dt;
    maxKmh = Math.max(maxKmh, f.speed * 3.6f);
    if (mAnchor == null)
      mAnchor = f;
    else
    {
      double d = distance(mAnchor, f);
      if (d >= Math.max(5f, f.accuracy * 0.5f))
      {
        meters += d;
        mAnchor = f;
      }
    }
    return false;
  }

  private boolean tryStart(Fix f, float speed, boolean moving)
  {
    if (mCandidate != null && f.time - mCandidate.time > CANDIDATE_MS)
      mCandidate = null;
    if (mCandidate == null)
    {
      if (speed >= START_SPEED)
      {
        mCandidate = f;
        mCandidateMaxKmh = f.speed * 3.6f;
      }
      return false;
    }
    if (moving)
      mCandidateMaxKmh = Math.max(mCandidateMaxKmh, f.speed * 3.6f);
    double d = distance(mCandidate, f);
    if (!moving || d < Math.max(START_DISTANCE, 2 * (mCandidate.accuracy + f.accuracy)))
      return false;
    active = true;
    start = mCandidate.time;
    lastMove = f.time;
    lastFixTime = f.time;
    meters = d;
    movingMs = f.time - mCandidate.time;
    maxKmh = mCandidateMaxKmh;
    mAnchor = f;
    mCandidate = null;
    return true;
  }

  boolean shouldFinish(long now)
  {
    return active && now - lastMove > STOP_AFTER_MS;
  }

  void restore(long start, long lastMove, double meters, long movingMs, float maxKmh)
  {
    active = true;
    this.start = start;
    this.lastMove = lastMove;
    this.lastFixTime = lastMove;
    this.meters = meters;
    this.movingMs = movingMs;
    this.maxKmh = maxKmh;
    mAnchor = null;
    mCandidate = null;
  }

  void stop()
  {
    active = false;
    mAnchor = null;
    mCandidate = null;
  }

  static double distance(Fix a, Fix b)
  {
    double lat1 = Math.toRadians(a.lat);
    double lat2 = Math.toRadians(b.lat);
    double dLat = lat2 - lat1;
    double dLon = Math.toRadians(b.lon - a.lon);
    double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
             + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * 6_371_008.8 * Math.asin(Math.min(1, Math.sqrt(h)));
  }
}
