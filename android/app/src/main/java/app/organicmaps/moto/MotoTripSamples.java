package app.organicmaps.moto;

import androidx.annotation.NonNull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

// One sample per second of a ride behind the trip charts: GPS speed and altitude, and the phone's tilt.
// Kept free of Android types so the encoding and the maths can be unit tested.
final class MotoTripSamples
{
  private static final int VERSION = 1;
  private static final short MISSING = Short.MIN_VALUE;
  private static final float G = 9.81f;
  // Below this speed the bike may stand on its side stand, so its tilt does not tell where upright is.
  private static final float RIDING_KMH = 15f;

  private MotoTripSamples() {}

  // Raw per-second values; NaN where nothing was measured in that second.
  static final class Raw
  {
    final float[] speedKmh;
    final float[] altitudeM;
    // Angle of gravity in the screen plane, in degrees; positive when the top of the screen leans left.
    final float[] tiltDeg;

    Raw(@NonNull float[] speedKmh, @NonNull float[] altitudeM, @NonNull float[] tiltDeg)
    {
      this.speedKmh = speedKmh;
      this.altitudeM = altitudeM;
      this.tiltDeg = tiltDeg;
    }

    int size()
    {
      return speedKmh.length;
    }
  }

  // What the charts show.
  static final class Series
  {
    final float[] speedKmh;
    final float[] altitudeM;
    // Positive leans left, negative right; NaN when the phone was not upright enough to tell.
    final float[] leanDeg;
    // Along the road in m/s², positive when speeding up, negative when braking.
    final float[] accel;

    Series(float[] speedKmh, float[] altitudeM, float[] leanDeg, float[] accel)
    {
      this.speedKmh = speedKmh;
      this.altitudeM = altitudeM;
      this.leanDeg = leanDeg;
      this.accel = accel;
    }
  }

  static final class Recorder
  {
    private final long mStart;
    private float[] mSpeed = new float[0];
    private float[] mAltitude = new float[0];
    private float[] mTilt = new float[0];
    private int mSize;
    private int mTiltSecond = -1;
    private double mTiltSum;
    private int mTiltCount;

    Recorder(long start)
    {
      mStart = start;
    }

    // Continues a ride whose samples were saved before the process was restarted.
    Recorder(long start, @NonNull Raw saved)
    {
      this(start);
      int n = saved.size();
      grow(n);
      System.arraycopy(saved.speedKmh, 0, mSpeed, 0, n);
      System.arraycopy(saved.altitudeM, 0, mAltitude, 0, n);
      System.arraycopy(saved.tiltDeg, 0, mTilt, 0, n);
    }

    long start()
    {
      return mStart;
    }

    void onFix(long timeMs, float speedMps, float altitudeM)
    {
      int i = second(timeMs);
      if (i < 0)
        return;
      grow(i + 1);
      mSpeed[i] = speedMps * 3.6f;
      mAltitude[i] = altitudeM;
    }

    // Gravity as the sensor reports it (pointing up) in device axes, and the display rotation (0-3).
    void onGravity(long timeMs, float x, float y, int rotation)
    {
      int i = second(timeMs);
      if (i < 0)
        return;
      if (i != mTiltSecond)
        flushTilt();
      mTiltSecond = i;
      float tilt = tiltDegrees(x, y, rotation);
      if (Float.isNaN(tilt))
        return;
      mTiltSum += tilt;
      mTiltCount++;
    }

    @NonNull
    Raw raw()
    {
      flushTilt();
      return new Raw(Arrays.copyOf(mSpeed, mSize), Arrays.copyOf(mAltitude, mSize), Arrays.copyOf(mTilt, mSize));
    }

    private void flushTilt()
    {
      if (mTiltSecond >= 0 && mTiltCount > 0)
      {
        grow(mTiltSecond + 1);
        mTilt[mTiltSecond] = (float) (mTiltSum / mTiltCount);
      }
      mTiltSum = 0;
      mTiltCount = 0;
    }

    private int second(long timeMs)
    {
      return (int) ((timeMs - mStart) / 1000L);
    }

    private void grow(int size)
    {
      if (size <= mSize)
        return;
      if (size > mSpeed.length)
      {
        int capacity = Math.max(size, mSpeed.length * 2 + 60);
        mSpeed = grown(mSpeed, capacity);
        mAltitude = grown(mAltitude, capacity);
        mTilt = grown(mTilt, capacity);
      }
      mSize = size;
    }

    @NonNull
    private static float[] grown(@NonNull float[] values, int capacity)
    {
      float[] out = Arrays.copyOf(values, capacity);
      Arrays.fill(out, values.length, capacity, Float.NaN);
      return out;
    }
  }

  // Rotates device axes into screen axes so left and right follow how the head unit is mounted.
  static float tiltDegrees(float x, float y, int rotation)
  {
    float sx;
    float sy;
    switch (rotation)
    {
    case 1 ->
    {
      sx = -y;
      sy = x;
    }
    case 2 ->
    {
      sx = -x;
      sy = -y;
    }
    case 3 ->
    {
      sx = y;
      sy = -x;
    }
    default ->
    {
      sx = x;
      sy = y;
    }
    }
    // A phone lying flat has gravity across the screen plane, so it shows no lean.
    if (Math.hypot(sx, sy) < 0.5f * G)
      return Float.NaN;
    return (float) Math.toDegrees(Math.atan2(sx, sy));
  }

  @NonNull
  static Series analyze(@NonNull Raw raw)
  {
    int n = raw.size();
    float upright = uprightTilt(raw);
    float[] lean = new float[n];
    float[] accel = new float[n];
    for (int i = 0; i < n; i++)
    {
      lean[i] = Float.isNaN(upright) ? Float.NaN : raw.tiltDeg[i] - upright;
      // Central difference over two seconds smooths the GPS speed jitter.
      boolean edge = i == 0 || i == n - 1;
      accel[i] = edge ? Float.NaN : (raw.speedKmh[i + 1] - raw.speedKmh[i - 1]) / 3.6f / 2f;
    }
    return new Series(raw.speedKmh, raw.altitudeM, lean, accel);
  }

  // A bike rides upright most of the time, so the median tilt while moving is where the mount sits upright.
  private static float uprightTilt(@NonNull Raw raw)
  {
    float[] moving = new float[raw.size()];
    int count = 0;
    for (int i = 0; i < raw.size(); i++)
      if (!Float.isNaN(raw.tiltDeg[i]) && raw.speedKmh[i] >= RIDING_KMH)
        moving[count++] = raw.tiltDeg[i];
    if (count == 0)
      return Float.NaN;
    Arrays.sort(moving, 0, count);
    return moving[count / 2];
  }

  @NonNull
  static byte[] encode(@NonNull Raw raw) throws IOException
  {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes)))
    {
      out.writeByte(VERSION);
      out.writeInt(raw.size());
      write(out, raw.speedKmh, 10);
      write(out, raw.altitudeM, 1);
      write(out, raw.tiltDeg, 10);
    }
    return bytes.toByteArray();
  }

  @NonNull
  static Raw decode(@NonNull byte[] data) throws IOException
  {
    try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data))))
    {
      int version = in.readUnsignedByte();
      if (version != VERSION)
        throw new IOException("Unknown trip samples version " + version);
      int n = in.readInt();
      return new Raw(read(in, n, 10), read(in, n, 1), read(in, n, 10));
    }
  }

  private static void write(@NonNull DataOutputStream out, @NonNull float[] values, int scale) throws IOException
  {
    for (float v : values)
    {
      if (Float.isNaN(v))
        out.writeShort(MISSING);
      else
        out.writeShort((int) Math.max(MISSING + 1, Math.min(Short.MAX_VALUE, Math.round(v * scale))));
    }
  }

  @NonNull
  private static float[] read(@NonNull DataInputStream in, int n, int scale) throws IOException
  {
    float[] values = new float[n];
    for (int i = 0; i < n; i++)
    {
      short s = in.readShort();
      values[i] = s == MISSING ? Float.NaN : (float) s / scale;
    }
    return values;
  }
}
