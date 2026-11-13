package app.organicmaps.moto;

import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// Peaks and per-second charts of one recorded trip.
public final class MotoTripDetailActivity extends AppCompatActivity
{
  static final String EXTRA_START = "start";

  private MotoColors mColors;

  private int dp(int value)
  {
    return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
  }

  @Override
  protected void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    mColors = MotoColors.of(this);

    MotoTrips trips = MotoTrips.get(this);
    long start = getIntent().getLongExtra(EXTRA_START, 0);
    MotoTrips.Trip trip = null;
    for (MotoTrips.Trip t : trips.load())
      if (t.start == start)
        trip = t;
    if (trip == null)
    {
      finish();
      return;
    }

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(mColors.background);

    LinearLayout header = new LinearLayout(this);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(12), dp(10), dp(12), dp(10));
    TextView back = text("Back", 15, false, mColors.text);
    back.setGravity(Gravity.CENTER);
    back.setPadding(dp(16), 0, dp(16), 0);
    back.setBackground(MotoColors.rounded(mColors.surfaceHigh, dp(16)));
    back.setOnClickListener(v -> finish());
    header.addView(back, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));
    SimpleDateFormat day = new SimpleDateFormat("EEE, d MMM", Locale.ENGLISH);
    SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.ENGLISH);
    TextView title = text(day.format(new Date(trip.start)) + " · " + time.format(new Date(trip.start)) + " – "
                              + time.format(new Date(trip.end)),
                          20, true, mColors.text);
    title.setPadding(dp(16), 0, 0, 0);
    header.addView(title);
    root.addView(header);

    ScrollView scroll = new ScrollView(this);
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(12), 0, dp(12), dp(12));
    scroll.addView(content);
    root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

    MotoTripSamples.Series series = trips.series(trip);
    if (series == null)
    {
      TextView none = text("Charts are recorded for trips ridden from this version on.", 16, false, mColors.textDim);
      none.setPadding(dp(4), dp(20), dp(4), 0);
      content.addView(none);
    }
    else
    {
      content.addView(peaks(series));
      content.addView(chartRow(chart("Speed", series.speedKmh, "km/h", false, 0, null, null),
                               chart("Altitude", series.altitudeM, "m", false, 0, null, null)));
      content.addView(chartRow(chart("Lean angle", series.leanDeg, "°", true, 0, "L", "R"),
                               chart("Acceleration", series.accel, "m/s²", true, 1, null, null)));
    }

    ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
      Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
      v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
      return WindowInsetsCompat.CONSUMED;
    });
    setContentView(root);
  }

  @Override
  protected void onResume()
  {
    super.onResume();
    WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
    bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    bars.hide(WindowInsetsCompat.Type.systemBars());
  }

  @NonNull
  private LinearLayout peaks(@NonNull MotoTripSamples.Series s)
  {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.addView(peak("Top speed", max(s.speedKmh), "km/h", 0));
    row.addView(peak("Max acceleration", max(s.accel), "m/s²", 1));
    row.addView(peak("Max braking", -max(negate(s.accel)), "m/s²", 1));
    row.addView(peak("Max lean left", max(s.leanDeg), "°", 0));
    row.addView(peak("Max lean right", max(negate(s.leanDeg)), "°", 0));
    return row;
  }

  @NonNull
  private LinearLayout peak(@NonNull String label, float value, @NonNull String unit, int decimals)
  {
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dp(14), dp(10), dp(14), dp(10));
    card.setBackground(MotoColors.rounded(mColors.card, dp(18)));
    card.addView(text(label, 13, false, mColors.textDim));
    String shown = Float.isNaN(value) ? "–"
                 : (decimals == 0 ? String.valueOf(Math.round(value))
                                  : String.format(Locale.US, "%." + decimals + "f", value))
                       + MotoChartView.unitSuffix(unit);
    card.addView(text(shown, 22, true, mColors.text));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    lp.setMargins(dp(4), dp(4), dp(4), dp(4));
    card.setLayoutParams(lp);
    return card;
  }

  @NonNull
  private LinearLayout chartRow(@NonNull MotoChartView a, @NonNull MotoChartView b)
  {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    for (MotoChartView chart : new MotoChartView[] {a, b})
    {
      chart.setBackground(MotoColors.rounded(mColors.card, dp(18)));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(220), 1f);
      lp.setMargins(dp(4), dp(4), dp(4), dp(4));
      row.addView(chart, lp);
    }
    return row;
  }

  @NonNull
  private MotoChartView chart(@NonNull String title, @NonNull float[] values, @NonNull String unit, boolean signed,
                              int decimals, @Nullable String topTag, @Nullable String bottomTag)
  {
    MotoChartView chart = new MotoChartView(this, mColors);
    chart.setData(title, values, unit, signed, decimals, topTag, bottomTag);
    return chart;
  }

  private static float max(@NonNull float[] values)
  {
    float best = Float.NaN;
    for (float v : values)
      if (!Float.isNaN(v) && (Float.isNaN(best) || v > best))
        best = v;
    return best;
  }

  @NonNull
  private static float[] negate(@NonNull float[] values)
  {
    float[] out = new float[values.length];
    for (int i = 0; i < values.length; i++)
      out[i] = -values[i];
    return out;
  }

  @NonNull
  private TextView text(@NonNull String s, int sp, boolean bold, int color)
  {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    t.setTextColor(color);
    if (bold)
      t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    return t;
  }
}
