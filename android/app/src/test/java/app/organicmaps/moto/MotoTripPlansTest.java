package app.organicmaps.moto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import app.organicmaps.sdk.routing.RouteMarkData;
import app.organicmaps.sdk.routing.RouteMarkType;
import java.util.List;
import org.junit.Test;

public class MotoTripPlansTest
{
  private static RouteMarkData point(String title, RouteMarkType type, int index, double lat)
  {
    return new RouteMarkData(title, "", type, index, true, type == RouteMarkType.Start, false, lat, 107.0);
  }

  @Test
  public void waypointsFollowRidingOrderWithoutStart()
  {
    RouteMarkData[] points = {
        point("Home", RouteMarkType.Finish, 0, 4), point("Second stop", RouteMarkType.Intermediate, 1, 3),
        point("My position", RouteMarkType.Start, 0, 1), point("First stop", RouteMarkType.Intermediate, 0, 2)};

    List<MotoTripPlans.Waypoint> waypoints = MotoTripPlans.waypointsFrom(points);

    assertEquals(3, waypoints.size());
    assertEquals("First stop", waypoints.get(0).title);
    assertEquals("Second stop", waypoints.get(1).title);
    assertEquals("Home", waypoints.get(2).title);
    assertEquals(4, waypoints.get(2).lat, 0);
  }

  @Test
  public void untitledPointsGetAPlaceholder()
  {
    RouteMarkData[] points = {point("", RouteMarkType.Start, 0, 1), point(null, RouteMarkType.Finish, 0, 2)};

    List<MotoTripPlans.Waypoint> waypoints = MotoTripPlans.waypointsFrom(points);

    assertEquals(1, waypoints.size());
    assertEquals("Point", waypoints.get(0).title);
  }

  @Test
  public void startKeepsItsCoordinatesAndTitle()
  {
    RouteMarkData home = new RouteMarkData("Home", "", RouteMarkType.Start, 0, true, false, false, 1, 107.0);
    RouteMarkData[] points = {home, point("Lake", RouteMarkType.Finish, 0, 2)};

    MotoTripPlans.Waypoint start = MotoTripPlans.startFrom(points, "Jalan Dago 1");

    assertEquals("Home", start.title);
    assertEquals(1, start.lat, 0);
  }

  @Test
  public void startFromGpsIsNamedByTheCaller()
  {
    // point() marks Start as "my position", whose own title is meaningless once saved.
    RouteMarkData[] points = {point("", RouteMarkType.Start, 0, 1), point("Lake", RouteMarkType.Finish, 0, 2)};

    assertEquals("Jalan Dago 1", MotoTripPlans.startFrom(points, "Jalan Dago 1").title);
  }

  @Test
  public void noStartGivesNull()
  {
    RouteMarkData[] points = {point("Lake", RouteMarkType.Finish, 0, 2)};

    assertNull(MotoTripPlans.startFrom(points, "Jalan Dago 1"));
  }

  @Test
  public void planDestinationIsTheLastWaypoint()
  {
    MotoTripPlans.Plan plan = new MotoTripPlans.Plan(
        "Weekend", null,
        List.of(new MotoTripPlans.Waypoint("Coffee", 1, 2), new MotoTripPlans.Waypoint("Lake", 3, 4)), null);

    assertEquals("Lake", plan.destination().title);
    assertEquals(1, plan.stopCount());
  }

  // ~111 m per 0.001 degree of latitude.
  private static final double STEP = 0.001;

  @Test
  public void straightLineKeepsOnlyItsEnds()
  {
    double[] line = new double[20];
    for (int i = 0; i < 10; i++)
    {
      line[2 * i] = -6.9 + i * STEP;
      line[2 * i + 1] = 107.6;
    }

    double[] simplified = MotoTripPlans.simplify(line, MotoTripPlans.LINE_TOLERANCE_M);

    assertArrayEquals(new double[] {-6.9, 107.6, -6.9 + 9 * STEP, 107.6}, simplified, 1e-12);
  }

  @Test
  public void turnsSurviveSimplification()
  {
    // North for three points, then east: the corner must stay.
    double[] line = {-6.9, 107.6, -6.9 + STEP, 107.6, -6.9 + 2 * STEP, 107.6, -6.9 + 2 * STEP, 107.6 + STEP,
                     -6.9 + 2 * STEP, 107.6 + 2 * STEP};

    double[] simplified = MotoTripPlans.simplify(line, MotoTripPlans.LINE_TOLERANCE_M);

    assertArrayEquals(new double[] {-6.9, 107.6, -6.9 + 2 * STEP, 107.6, -6.9 + 2 * STEP, 107.6 + 2 * STEP},
                      simplified, 1e-12);
  }

  @Test
  public void wobbleBelowToleranceIsDropped()
  {
    // A 1 m sideways wobble (about 0.000009 degrees) on a straight road.
    double[] line = {-6.9, 107.6, -6.9 + STEP, 107.6 + 0.000009, -6.9 + 2 * STEP, 107.6};

    assertEquals(4, MotoTripPlans.simplify(line, MotoTripPlans.LINE_TOLERANCE_M).length);
  }

  @Test
  public void lengthIsMeasuredOnTheFullLine()
  {
    double[] line = {0, 0, 0.001, 0, 0.002, 0};

    assertEquals(222.4, MotoTripPlans.lengthMeters(line), 0.5);
  }

  @Test
  public void savedRouteKeepsFullLengthButFewerPoints()
  {
    double[] line = new double[200];
    for (int i = 0; i < 100; i++)
    {
      line[2 * i] = i * STEP / 10;
      line[2 * i + 1] = 0;
    }

    MotoTripPlans.SavedRoute route = MotoTripPlans.savedRoute(line, 600);

    assertEquals(4, route.line.length);
    assertEquals(MotoTripPlans.lengthMeters(line), route.meters, 1e-9);
    assertEquals(600, route.seconds);
  }
}
