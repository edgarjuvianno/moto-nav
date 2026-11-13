package app.organicmaps.moto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MotoTripSamplesTest
{
  private static final float G = 9.81f;
  private static final long START = 1_000_000L;

  private static float[] gravityTiltedLeft(double degrees)
  {
    // Upright portrait reads (0, g); leaning the top of the screen left turns gravity towards +x.
    double r = Math.toRadians(degrees);
    return new float[] {(float) (G * Math.sin(r)), (float) (G * Math.cos(r))};
  }

  @Test
  public void tiltIsZeroWhenUprightInEveryRotation()
  {
    assertEquals(0, MotoTripSamples.tiltDegrees(0, G, 0), 1e-4);
    // Landscape with the device top to the left: its right side points up.
    assertEquals(0, MotoTripSamples.tiltDegrees(G, 0, 1), 1e-4);
    assertEquals(0, MotoTripSamples.tiltDegrees(0, -G, 2), 1e-4);
    assertEquals(0, MotoTripSamples.tiltDegrees(-G, 0, 3), 1e-4);
  }

  @Test
  public void leftLeanIsPositiveAndRightNegative()
  {
    float[] left = gravityTiltedLeft(30);
    assertEquals(30, MotoTripSamples.tiltDegrees(left[0], left[1], 0), 1e-3);
    float[] right = gravityTiltedLeft(-25);
    assertEquals(-25, MotoTripSamples.tiltDegrees(right[0], right[1], 0), 1e-3);
  }

  @Test
  public void phoneLyingFlatShowsNoTilt()
  {
    assertTrue(Float.isNaN(MotoTripSamples.tiltDegrees(0.5f, 0.3f, 0)));
  }

  @Test
  public void recorderKeepsOneSamplePerSecondAndAveragesTilt()
  {
    MotoTripSamples.Recorder recorder = new MotoTripSamples.Recorder(START);
    recorder.onFix(START + 100, 10f, 700f);
    float[] a = gravityTiltedLeft(10);
    float[] b = gravityTiltedLeft(20);
    recorder.onGravity(START + 200, a[0], a[1], 0);
    recorder.onGravity(START + 600, b[0], b[1], 0);
    // Nothing in second 1, then a fix in second 2.
    recorder.onFix(START + 2_500, 12f, 702f);

    MotoTripSamples.Raw raw = recorder.raw();

    assertEquals(3, raw.size());
    assertEquals(36f, raw.speedKmh[0], 1e-4);
    assertEquals(15f, raw.tiltDeg[0], 1e-3);
    assertTrue(Float.isNaN(raw.speedKmh[1]));
    assertTrue(Float.isNaN(raw.tiltDeg[1]));
    assertEquals(702f, raw.altitudeM[2], 1e-4);
  }

  @Test
  public void leanIsMeasuredFromTheUprightMountWhileRiding()
  {
    // The mount sits 8 degrees off upright; parked on the side stand it reads 40 degrees.
    float[] speed = {0, 30, 30, 30, 30, 30};
    float[] tilt = {40, 8, 8, 8, 38, -12};
    MotoTripSamples.Raw raw = new MotoTripSamples.Raw(speed, new float[6], tilt);

    MotoTripSamples.Series series = MotoTripSamples.analyze(raw);

    assertEquals(0, series.leanDeg[1], 1e-4);
    assertEquals(30, series.leanDeg[4], 1e-4);
    assertEquals(-20, series.leanDeg[5], 1e-4);
  }

  @Test
  public void accelerationComesFromSpeedChange()
  {
    // 36 -> 43.2 -> 50.4 km/h: +2 m/s each second.
    float[] speed = {36f, 43.2f, 50.4f, 43.2f};
    MotoTripSamples.Raw raw = new MotoTripSamples.Raw(speed, new float[4], new float[4]);

    MotoTripSamples.Series series = MotoTripSamples.analyze(raw);

    assertTrue(Float.isNaN(series.accel[0]));
    assertEquals(2f, series.accel[1], 1e-3);
    assertEquals(0f, series.accel[2], 1e-3);
    assertTrue(Float.isNaN(series.accel[3]));
  }

  @Test
  public void encodingRoundTripsWithinItsResolution() throws Exception
  {
    float[] speed = {0f, 12.34f, Float.NaN, 131.06f};
    float[] altitude = {720.4f, Float.NaN, 1730f, -3f};
    float[] tilt = {-41.27f, 0f, 12.5f, Float.NaN};

    MotoTripSamples.Raw back =
        MotoTripSamples.decode(MotoTripSamples.encode(new MotoTripSamples.Raw(speed, altitude, tilt)));

    assertEquals(4, back.size());
    assertEquals(12.3f, back.speedKmh[1], 1e-4);
    assertTrue(Float.isNaN(back.speedKmh[2]));
    assertEquals(131.1f, back.speedKmh[3], 1e-4);
    assertEquals(720f, back.altitudeM[0], 1e-4);
    assertTrue(Float.isNaN(back.altitudeM[1]));
    assertEquals(-41.3f, back.tiltDeg[0], 1e-4);
    assertTrue(Float.isNaN(back.tiltDeg[3]));
  }

  @Test
  public void halfHourRideStaysSmall() throws Exception
  {
    int n = 1800;
    float[] speed = new float[n];
    float[] altitude = new float[n];
    float[] tilt = new float[n];
    java.util.Random random = new java.util.Random(7);
    for (int i = 0; i < n; i++)
    {
      speed[i] = 40 + 20 * (float) Math.sin(i / 60.0) + random.nextFloat();
      altitude[i] = 700 + 50 * (float) Math.sin(i / 300.0);
      tilt[i] = 5 + 25 * (float) Math.sin(i / 15.0) + random.nextFloat();
    }

    byte[] data = MotoTripSamples.encode(new MotoTripSamples.Raw(speed, altitude, tilt));

    assertTrue("got " + data.length + " bytes", data.length < 12_000);
  }
}
