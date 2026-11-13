package app.organicmaps.moto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MotoTripTrackerTest
{
  private static final double LAT = -6.886103;
  private static final double LON = 107.570424;
  private static final double METERS_PER_DEGREE = 6_371_008.8 * Math.PI / 180;

  // Fixes recorded from a phone lying still on a desk: speed accuracy is reported and position drifts ~25 m.
  private static final double[][] DESK = {
      {-6.886103, 107.570424, 10.72, 1.19, 2.02}, {-6.886107, 107.570430, 10.72, 0.0, 0.79},
      {-6.886109, 107.570438, 11.79, 1.36, 2.06}, {-6.886112, 107.570423, 10.72, 0.54, 1.99},
      {-6.886128, 107.570444, 11.79, 1.37, 2.04}, {-6.886149, 107.570437, 10.72, 1.25, 1.54},
      {-6.886174, 107.570438, 10.72, 0.98, 1.66}, {-6.886191, 107.570441, 10.72, 0.71, 1.75},
      {-6.886245, 107.570437, 10.72, 0.72, 1.54}, {-6.886313, 107.570454, 10.72, 0.90, 1.47}};

  private static MotoTripTracker.Fix fix(long t, double lat, double lon, float acc, float speed, float speedAcc)
  {
    return new MotoTripTracker.Fix(t, lat, lon, acc, speed, speedAcc);
  }

  // A fix `meters` north of the origin moving at `kmh`.
  private static MotoTripTracker.Fix north(long t, double meters, float kmh)
  {
    return fix(t, LAT + meters / METERS_PER_DEGREE, LON, 5f, kmh / 3.6f, 0.3f);
  }

  private static void feedDesk(MotoTripTracker tracker, long from, long durationMs)
  {
    for (long t = 0, i = 0; t < durationMs; t += 1000, i++)
    {
      double[] d = DESK[(int) (i % DESK.length)];
      tracker.onFix(fix(from + t, d[0], d[1], (float) d[2], (float) d[3], (float) d[4]));
    }
  }

  @Test
  public void stationaryNoiseNeverStartsTrip()
  {
    MotoTripTracker tracker = new MotoTripTracker();
    feedDesk(tracker, 0, 10 * 60_000L);
    assertFalse(tracker.active);
  }

  @Test
  public void speedSpikeWithoutDisplacementDoesNotStartTrip()
  {
    MotoTripTracker tracker = new MotoTripTracker();
    // A receiver without speed accuracy reporting a 15 km/h spike, then the usual jitter in place.
    tracker.onFix(fix(0, LAT, LON, 8f, 15f / 3.6f, Float.NaN));
    for (int i = 1; i < 120; i++)
      tracker.onFix(fix(i * 1000L, LAT + (i % 3) * 3 / METERS_PER_DEGREE, LON, 8f, 1.5f, Float.NaN));
    assertFalse(tracker.active);
  }

  @Test
  public void inaccurateFixesAreIgnored()
  {
    MotoTripTracker tracker = new MotoTripTracker();
    for (int i = 0; i < 60; i++)
      tracker.onFix(fix(i * 1000L, LAT + i * 6 / METERS_PER_DEGREE, LON, 35f, 6f, 0.3f));
    assertFalse(tracker.active);
  }

  @Test
  public void rideStartsTripAndCountsDistance()
  {
    MotoTripTracker tracker = new MotoTripTracker();
    // 36 km/h = 10 m/s for two minutes.
    boolean started = false;
    for (int s = 0; s <= 120; s++)
      started |= tracker.onFix(north(s * 1000L, s * 10.0, 36f));
    assertTrue(started);
    assertTrue(tracker.active);
    assertEquals(0, tracker.start);
    assertEquals(1200, tracker.meters, 20);
    assertEquals(120_000, tracker.movingMs);
    assertEquals(36f, tracker.maxKmh, 0.01f);
  }

  @Test
  public void stopWithNoiseDoesNotAddDistanceAndTripEnds()
  {
    MotoTripTracker tracker = new MotoTripTracker();
    for (int s = 0; s <= 60; s++)
      tracker.onFix(north(s * 1000L, s * 10.0, 36f));
    double rideMeters = tracker.meters;
    long lastMove = tracker.lastMove;
    // Parked: same desk noise, re-centred on where the ride ended.
    double dLat = 600 / METERS_PER_DEGREE + LAT - DESK[0][0];
    for (long t = 0, i = 0; t < 6 * 60_000L; t += 1000, i++)
    {
      double[] d = DESK[(int) (i % DESK.length)];
      tracker.onFix(fix(61_000 + t, d[0] + dLat, d[1], (float) d[2], (float) d[3], (float) d[4]));
    }
    assertEquals(rideMeters, tracker.meters, 0.001);
    assertEquals(lastMove, tracker.lastMove);
    assertTrue(tracker.shouldFinish(61_000 + 6 * 60_000L));
  }

  @Test
  public void candidateExpiresWithoutMovement()
  {
    MotoTripTracker tracker = new MotoTripTracker();
    tracker.onFix(north(0, 0, 20f));
    // Displacement only arrives after the candidate window, so it must not confirm the old candidate.
    assertFalse(tracker.onFix(north(MotoTripTracker.CANDIDATE_MS + 1000, 100, 20f)));
    assertFalse(tracker.active);
  }
}
