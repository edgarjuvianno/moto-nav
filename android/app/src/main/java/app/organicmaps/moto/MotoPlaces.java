package app.organicmaps.moto;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class MotoPlaces
{
  public static final class Place
  {
    public final String nickname;
    public final String title;
    public final double lat;
    public final double lon;

    public Place(@NonNull String nickname, @NonNull String title, double lat, double lon)
    {
      this.nickname = nickname;
      this.title = title;
      this.lat = lat;
      this.lon = lon;
    }
  }

  private static final String PREFS = "moto_places";
  private static final String KEY = "places";
  private static final double MIN_SCORE = 0.62;

  private MotoPlaces() {}

  @NonNull
  private static SharedPreferences prefs(@NonNull Context c)
  {
    return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  @NonNull
  public static List<Place> load(@NonNull Context c)
  {
    List<Place> out = new ArrayList<>();
    try
    {
      JSONArray arr = new JSONArray(prefs(c).getString(KEY, "[]"));
      for (int i = 0; i < arr.length(); i++)
      {
        JSONObject o = arr.getJSONObject(i);
        out.add(new Place(o.optString("n"), o.optString("t"), o.optDouble("la"), o.optDouble("lo")));
      }
    }
    catch (JSONException ignored)
    {
    }
    return out;
  }

  private static void save(@NonNull Context c, @NonNull List<Place> places)
  {
    JSONArray arr = new JSONArray();
    try
    {
      for (Place p : places)
        arr.put(new JSONObject().put("n", p.nickname).put("t", p.title).put("la", p.lat).put("lo", p.lon));
    }
    catch (JSONException ignored)
    {
    }
    prefs(c).edit().putString(KEY, arr.toString()).apply();
  }

  public static void add(@NonNull Context c, @NonNull Place place)
  {
    List<Place> places = load(c);
    String key = MotoNameMatcher.clean(place.nickname);
    places.removeIf(p -> MotoNameMatcher.clean(p.nickname).equals(key));
    places.add(0, place);
    save(c, places);
  }

  public static void remove(@NonNull Context c, @NonNull Place place)
  {
    List<Place> places = load(c);
    places.removeIf(p -> p.nickname.equals(place.nickname) && p.lat == place.lat && p.lon == place.lon);
    save(c, places);
  }

  public static void rename(@NonNull Context c, @NonNull Place place, @NonNull String nickname)
  {
    List<Place> places = load(c);
    for (int i = 0; i < places.size(); i++)
    {
      Place p = places.get(i);
      if (p.nickname.equals(place.nickname) && p.lat == place.lat && p.lon == place.lon)
        places.set(i, new Place(nickname, p.title, p.lat, p.lon));
    }
    save(c, places);
  }

  @Nullable
  public static Place match(@NonNull Context c, @NonNull String spoken)
  {
    String q = MotoNameMatcher.clean(spoken);
    if (q.equals("home") || q.equals("house") || q.equals("rumah"))
      q = "home";
    if (q.equals("work") || q.equals("kantor"))
      q = "office";
    Place best = null;
    double bestScore = 0;
    for (Place p : load(c))
    {
      double sc = Math.max(MotoNameMatcher.score(q, MotoNameMatcher.clean(p.nickname)),
                           MotoNameMatcher.score(q, MotoNameMatcher.clean(p.title)) - 0.05);
      if (sc > bestScore)
      {
        bestScore = sc;
        best = p;
      }
    }
    return bestScore >= MIN_SCORE ? best : null;
  }
}
