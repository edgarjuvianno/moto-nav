package app.organicmaps.moto;

import android.content.Context;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.MwmApplication;
import app.organicmaps.sdk.search.SearchEngine;
import app.organicmaps.sdk.search.SearchListener;
import app.organicmaps.sdk.search.SearchResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class MotoNameMatcher implements SearchListener
{
  public interface Callback
  {
    void onMatched(@Nullable String name);
  }

  private static final String TAG = "MotoNameMatch";
  private static final long PROBE_TIMEOUT_MS = 1500L;
  private static final double MIN_SCORE = 0.6;
  private static final String[] COMMON = {"gedung", "jalan", "masjid", "pasar", "taman", "rumah sakit", "universitas",
                                          "stasiun", "terminal", "alun alun", "mall", "hotel", "kantor", "sekolah",
                                          "gereja", "museum", "bandara", "pantai", "gunung", "danau", "kampus"};

  private final Context mContext;
  private final String mQuery;
  private final Callback mCallback;
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private final List<String> mProbes = new ArrayList<>();
  private int mProbe = -1;
  private long mTimestamp;
  @Nullable
  private String mBest;
  private double mBestScore;
  private boolean mDone;
  private final Runnable mTimeout = this::next;
  private final Runnable mDeadline = this::finish;
  @Nullable
  private static MotoNameMatcher sActive;

  public static void cancelActive()
  {
    MotoNameMatcher m = sActive;
    sActive = null;
    if (m != null)
      m.abort();
  }

  private void abort()
  {
    if (mDone)
      return;
    mDone = true;
    mHandler.removeCallbacks(mTimeout);
    mHandler.removeCallbacks(mDeadline);
    SearchEngine.INSTANCE.removeListener(this);
    Log.i(TAG, "\"" + mQuery + "\" cancelled");
  }

  private MotoNameMatcher(@NonNull Context context, @NonNull String query, @NonNull Callback callback)
  {
    mContext = context;
    mQuery = clean(query);
    mCallback = callback;
    Set<String> probes = new LinkedHashSet<>();
    probes.add(query);
    probes.add(mQuery);
    String[] words = mQuery.split(" ");
    if (words.length >= 2)
    {
      String bestCommon = null;
      double bestSim = 0;
      for (String c : COMMON)
      {
        double sc = Math.max(sim(words[0], c), sim(skeleton(words[0]), skeleton(c)));
        if (sc > bestSim)
        {
          bestSim = sc;
          bestCommon = c;
        }
      }
      if (bestCommon != null && bestSim >= 0.5 && !bestCommon.equals(words[0]))
      {
        String rest = String.join(" ", java.util.Arrays.copyOfRange(words, 1, words.length));
        probes.add(bestCommon + " " + rest);
        probes.add(bestCommon);
      }
    }
    for (String w : mQuery.split(" "))
      if (w.length() >= 4)
        probes.add(w);
    for (String p : probes)
      if (!p.trim().isEmpty() && mProbes.size() < 5)
        mProbes.add(p.trim());
  }

  public static void match(@NonNull Context context, @NonNull String query, @NonNull Callback callback)
  {
    cancelActive();
    MotoNameMatcher m = new MotoNameMatcher(context, query, callback);
    sActive = m;
    m.start();
  }

  private void start()
  {
    SearchEngine.INSTANCE.addListener(this);
    mHandler.postDelayed(mDeadline, 3000L);
    next();
  }

  private void next()
  {
    mHandler.removeCallbacks(mTimeout);
    if (mDone)
      return;
    mProbe++;
    if (mProbe >= mProbes.size() || mBestScore >= 0.92)
    {
      finish();
      return;
    }
    Location loc = MwmApplication.from(mContext).getLocationHelper().getSavedLocation();
    mTimestamp = System.nanoTime();
    boolean started = SearchEngine.INSTANCE.search(mContext, mProbes.get(mProbe), false, mTimestamp, loc != null,
                                                   loc == null ? 0 : loc.getLatitude(),
                                                   loc == null ? 0 : loc.getLongitude());
    if (!started)
    {
      next();
      return;
    }
    mHandler.postDelayed(mTimeout, PROBE_TIMEOUT_MS);
  }

  private void finish()
  {
    if (mDone)
      return;
    mDone = true;
    if (sActive == this)
      sActive = null;
    mHandler.removeCallbacks(mTimeout);
    mHandler.removeCallbacks(mDeadline);
    SearchEngine.INSTANCE.removeListener(this);
    Log.i(TAG, "\"" + mQuery + "\" -> " + (mBest == null ? "no match" : "\"" + mBest + "\" " + mBestScore));
    mCallback.onMatched(mBestScore >= MIN_SCORE ? mBest : null);
  }

  @Override
  public void onResultsUpdate(@NonNull SearchResult[] results, long timestamp)
  {
    if (timestamp != mTimestamp || mDone)
      return;
    for (SearchResult r : results)
    {
      if (r.type != SearchResult.TYPE_RESULT || r.name == null || r.name.isEmpty())
        continue;
      double sc = score(mQuery, clean(r.name));
      if (sc > mBestScore)
      {
        mBestScore = sc;
        mBest = r.name;
      }
    }
  }

  @Override
  public void onResultsEnd(long timestamp)
  {
    if (timestamp == mTimestamp && !mDone)
      mHandler.post(this::next);
  }

  @NonNull
  static String clean(@NonNull String s)
  {
    return MotoVoice.normalize(s).replaceAll("([a-z])\\1+", "$1").trim();
  }

  @NonNull
  private static String skeleton(@NonNull String s)
  {
    return s.replaceAll("[aeiou ]", "").replace('k', 'g').replace('p', 'b').replace('t', 'd').replace('f', 'v');
  }

  static double score(@NonNull String query, @NonNull String name)
  {
    if (query.isEmpty() || name.isEmpty())
      return 0;
    String[] qw = query.split(" ");
    String[] nw = name.split(" ");
    String head = nw.length > qw.length ? String.join(" ", java.util.Arrays.copyOfRange(nw, 0, qw.length)) : name;
    double full = Math.max(sim(query, name), sim(query, head) - 0.05);
    double sk = Math.max(sim(skeleton(query), skeleton(name)), sim(skeleton(query), skeleton(head)) - 0.05);
    return Math.max(full, sk * 0.95);
  }

  private static double sim(@NonNull String a, @NonNull String b)
  {
    String x = a.replace(" ", "");
    String y = b.replace(" ", "");
    if (x.isEmpty() || y.isEmpty())
      return 0;
    int[] prev = new int[y.length() + 1];
    int[] cur = new int[y.length() + 1];
    for (int j = 0; j <= y.length(); j++)
      prev[j] = j;
    for (int i = 1; i <= x.length(); i++)
    {
      cur[0] = i;
      for (int j = 1; j <= y.length(); j++)
      {
        int cost = x.charAt(i - 1) == y.charAt(j - 1) ? 0 : 1;
        cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
      }
      int[] t = prev;
      prev = cur;
      cur = t;
    }
    return 1.0 - (double) prev[y.length()] / Math.max(x.length(), y.length());
  }
}
