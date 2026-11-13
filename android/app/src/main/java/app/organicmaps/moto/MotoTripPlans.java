package app.organicmaps.moto;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.routing.RouteMarkData;
import app.organicmaps.sdk.routing.RouteMarkType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

// Saved multi-stop rides: a start, stops in riding order, and the destination as the last waypoint.
public final class MotoTripPlans
{
  public static final class Waypoint
  {
    public final String title;
    public final double lat;
    public final double lon;

    public Waypoint(@NonNull String title, double lat, double lon)
    {
      this.title = title;
      this.lat = lat;
      this.lon = lon;
    }
  }

  // The line of the route as built when the ride was saved, shown again without routing when it is opened.
  public static final class SavedRoute
  {
    // Flat [lat0, lon0, lat1, lon1, ...].
    public final double[] line;
    public final double meters;
    public final int seconds;

    public SavedRoute(@NonNull double[] line, double meters, int seconds)
    {
      this.line = line;
      this.meters = meters;
      this.seconds = seconds;
    }
  }

  public static final class Plan
  {
    public final String name;
    // Where the ride was planned from; null only for plans saved before the start was stored.
    @Nullable
    public final Waypoint start;
    public final List<Waypoint> waypoints;
    // Null for plans saved before the route line was stored.
    @Nullable
    public final SavedRoute route;

    public Plan(@NonNull String name, @Nullable Waypoint start, @NonNull List<Waypoint> waypoints,
                @Nullable SavedRoute route)
    {
      this.name = name;
      this.start = start;
      this.waypoints = Collections.unmodifiableList(new ArrayList<>(waypoints));
      this.route = route;
    }

    @NonNull
    public Waypoint destination()
    {
      return waypoints.get(waypoints.size() - 1);
    }

    public int stopCount()
    {
      return waypoints.size() - 1;
    }
  }

  private static final String PREFS = "moto_plans";
  private static final String KEY = "plans";
  // A route line within this many meters of the full one looks the same on the map at any zoom a rider uses.
  static final double LINE_TOLERANCE_M = 2.0;
  private static final double EARTH_RADIUS_M = 6_371_008.8;

  private MotoTripPlans() {}

  @NonNull
  static SavedRoute savedRoute(@NonNull double[] line, int seconds)
  {
    double[] simplified = simplify(line, LINE_TOLERANCE_M);
    return new SavedRoute(simplified, lengthMeters(line), seconds);
  }

  static double lengthMeters(@NonNull double[] latLons)
  {
    double total = 0;
    for (int i = 2; i + 1 < latLons.length; i += 2)
      total += distance(latLons[i - 2], latLons[i - 1], latLons[i], latLons[i + 1]);
    return total;
  }

  private static double distance(double lat1, double lon1, double lat2, double lon2)
  {
    double p1 = Math.toRadians(lat1);
    double p2 = Math.toRadians(lat2);
    double dp = p2 - p1;
    double dl = Math.toRadians(lon2 - lon1);
    double h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
    return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
  }

  // Douglas-Peucker on a local flat projection, which is exact enough within one ride.
  @NonNull
  static double[] simplify(@NonNull double[] latLons, double toleranceM)
  {
    int n = latLons.length / 2;
    if (n < 3)
      return latLons.clone();
    double metersPerDegLat = Math.toRadians(EARTH_RADIUS_M);
    double metersPerDegLon = metersPerDegLat * Math.cos(Math.toRadians(latLons[0]));
    double[] x = new double[n];
    double[] y = new double[n];
    for (int i = 0; i < n; i++)
    {
      x[i] = latLons[2 * i + 1] * metersPerDegLon;
      y[i] = latLons[2 * i] * metersPerDegLat;
    }
    boolean[] keep = new boolean[n];
    keep[0] = true;
    keep[n - 1] = true;
    java.util.ArrayDeque<int[]> ranges = new java.util.ArrayDeque<>();
    ranges.push(new int[] {0, n - 1});
    while (!ranges.isEmpty())
    {
      int[] r = ranges.pop();
      int farthest = -1;
      double maxDist = toleranceM;
      for (int i = r[0] + 1; i < r[1]; i++)
      {
        double d = segmentDistance(x[i], y[i], x[r[0]], y[r[0]], x[r[1]], y[r[1]]);
        if (d > maxDist)
        {
          maxDist = d;
          farthest = i;
        }
      }
      if (farthest < 0)
        continue;
      keep[farthest] = true;
      ranges.push(new int[] {r[0], farthest});
      ranges.push(new int[] {farthest, r[1]});
    }
    int kept = 0;
    for (boolean k : keep)
      if (k)
        kept++;
    double[] out = new double[kept * 2];
    for (int i = 0, j = 0; i < n; i++)
      if (keep[i])
      {
        out[j++] = latLons[2 * i];
        out[j++] = latLons[2 * i + 1];
      }
    return out;
  }

  private static double segmentDistance(double px, double py, double ax, double ay, double bx, double by)
  {
    double dx = bx - ax;
    double dy = by - ay;
    double len2 = dx * dx + dy * dy;
    double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
    return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
  }

  @Nullable
  static Waypoint startFrom(@NonNull RouteMarkData[] points, @NonNull String myPositionTitle)
  {
    for (RouteMarkData p : points)
      if (p.mPointType == RouteMarkType.Start)
        return new Waypoint(p.mIsMyPosition ? myPositionTitle : titleOf(p), p.mLat, p.mLon);
    return null;
  }

  @NonNull
  private static String titleOf(@NonNull RouteMarkData p)
  {
    return p.mTitle == null || p.mTitle.isEmpty() ? "Point" : p.mTitle;
  }

  // Stops and the destination in riding order, without the start.
  @NonNull
  static List<Waypoint> waypointsFrom(@NonNull RouteMarkData[] points)
  {
    List<RouteMarkData> ordered = new ArrayList<>(Arrays.asList(points));
    ordered.removeIf(p -> p.mPointType == RouteMarkType.Start);
    ordered.sort(Comparator.comparingInt((RouteMarkData p) -> p.mPointType == RouteMarkType.Finish ? 1 : 0)
                     .thenComparingInt(p -> p.mIntermediateIndex));
    List<Waypoint> out = new ArrayList<>();
    for (RouteMarkData p : ordered)
      out.add(new Waypoint(titleOf(p), p.mLat, p.mLon));
    return out;
  }

  @NonNull
  private static SharedPreferences prefs(@NonNull Context c)
  {
    return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  @NonNull
  public static List<Plan> load(@NonNull Context c)
  {
    List<Plan> out = new ArrayList<>();
    try
    {
      JSONArray arr = new JSONArray(prefs(c).getString(KEY, "[]"));
      for (int i = 0; i < arr.length(); i++)
      {
        JSONObject o = arr.getJSONObject(i);
        JSONArray pts = o.getJSONArray("w");
        List<Waypoint> waypoints = new ArrayList<>();
        for (int j = 0; j < pts.length(); j++)
        {
          JSONObject w = pts.getJSONObject(j);
          waypoints.add(new Waypoint(w.optString("t"), w.optDouble("la"), w.optDouble("lo")));
        }
        JSONObject s = o.optJSONObject("s");
        Waypoint start = s == null ? null : new Waypoint(s.optString("t"), s.optDouble("la"), s.optDouble("lo"));
        if (!waypoints.isEmpty())
          out.add(new Plan(o.optString("n"), start, waypoints, readRoute(o.optJSONObject("r"))));
      }
    }
    catch (JSONException ignored)
    {
    }
    return out;
  }

  @Nullable
  private static SavedRoute readRoute(@Nullable JSONObject r) throws JSONException
  {
    if (r == null)
      return null;
    JSONArray l = r.getJSONArray("l");
    double[] line = new double[l.length()];
    for (int i = 0; i < line.length; i++)
      line[i] = l.getDouble(i);
    return line.length < 4 ? null : new SavedRoute(line, r.optDouble("m"), r.optInt("t"));
  }

  private static void save(@NonNull Context c, @NonNull List<Plan> plans)
  {
    JSONArray arr = new JSONArray();
    try
    {
      for (Plan p : plans)
      {
        JSONArray pts = new JSONArray();
        for (Waypoint w : p.waypoints)
          pts.put(new JSONObject().put("t", w.title).put("la", w.lat).put("lo", w.lon));
        JSONObject o = new JSONObject().put("n", p.name).put("w", pts);
        if (p.start != null)
          o.put("s", new JSONObject().put("t", p.start.title).put("la", p.start.lat).put("lo", p.start.lon));
        if (p.route != null)
        {
          JSONArray line = new JSONArray();
          for (double v : p.route.line)
            line.put(Math.round(v * 1e6) / 1e6);
          o.put("r", new JSONObject().put("l", line).put("m", p.route.meters).put("t", p.route.seconds));
        }
        arr.put(o);
      }
    }
    catch (JSONException ignored)
    {
    }
    prefs(c).edit().putString(KEY, arr.toString()).apply();
  }

  // A plan with the same name is replaced, so re-saving an edited ride updates it.
  public static void add(@NonNull Context c, @NonNull Plan plan)
  {
    List<Plan> plans = load(c);
    plans.removeIf(p -> p.name.equalsIgnoreCase(plan.name));
    plans.add(0, plan);
    save(c, plans);
  }

  public static void delete(@NonNull Context c, @NonNull String name)
  {
    List<Plan> plans = load(c);
    plans.removeIf(p -> p.name.equals(name));
    save(c, plans);
  }
}
