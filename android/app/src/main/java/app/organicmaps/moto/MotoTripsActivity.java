package app.organicmaps.moto;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MotoTripsActivity extends AppCompatActivity
{
  private MotoColors mColors;
  private LinearLayout mContent;

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
    TextView title = text("Trips", 20, true, mColors.text);
    title.setPadding(dp(16), 0, 0, 0);
    header.addView(title);
    root.addView(header);

    ScrollView scroll = new ScrollView(this);
    mContent = new LinearLayout(this);
    mContent.setOrientation(LinearLayout.VERTICAL);
    mContent.setPadding(dp(12), 0, dp(12), dp(12));
    scroll.addView(mContent);
    root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

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
    androidx.core.view.WindowInsetsControllerCompat bars =
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
    bars.setSystemBarsBehavior(androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    bars.hide(WindowInsetsCompat.Type.systemBars());
    render();
  }

  private void render()
  {
    mContent.removeAllViews();
    MotoTrips recorder = MotoTrips.get(this);
    List<MotoTrips.Trip> trips = recorder.load();

    LinearLayout summary = new LinearLayout(this);
    summary.setOrientation(LinearLayout.HORIZONTAL);
    long now = System.currentTimeMillis();
    summary.addView(summaryCard("Today", trips, startOfDay(0), now));
    summary.addView(summaryCard("Last 7 days", trips, startOfDay(6), now));
    summary.addView(summaryCard("This month", trips, startOfMonth(), now));
    summary.addView(summaryCard("All time", trips, 0, now));
    mContent.addView(summary);

    if (recorder.isRecording())
    {
      TextView live = text("● Recording · " + km(recorder.currentMeters()) + " since "
                               + time(recorder.currentStart()), 15, true, mColors.ok);
      live.setPadding(dp(4), dp(14), 0, dp(4));
      mContent.addView(live);
    }

    if (trips.isEmpty())
    {
      TextView empty = text("No trips yet. A trip starts automatically when you ride faster than 10 km/h and ends "
                                + "after 5 minutes stopped.", 15, false, mColors.textDim);
      empty.setPadding(dp(4), dp(20), dp(4), 0);
      mContent.addView(empty);
      return;
    }

    String lastDay = null;
    SimpleDateFormat dayFmt = new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH);
    for (MotoTrips.Trip t : trips)
    {
      String day = dayFmt.format(new Date(t.start));
      if (!day.equals(lastDay))
      {
        lastDay = day;
        TextView h = text(day, 14, true, mColors.textDim);
        h.setPadding(dp(4), dp(18), 0, dp(6));
        mContent.addView(h);
      }
      mContent.addView(tripRow(t));
    }
  }

  @NonNull
  private View summaryCard(@NonNull String label, @NonNull List<MotoTrips.Trip> trips, long from, long to)
  {
    double meters = 0;
    long ms = 0;
    int count = 0;
    for (MotoTrips.Trip t : trips)
      if (t.start >= from && t.start <= to)
      {
        meters += t.meters;
        ms += t.end - t.start;
        count++;
      }
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dp(16), dp(12), dp(16), dp(12));
    card.setBackground(MotoColors.rounded(mColors.card, dp(18)));
    card.addView(text(label, 13, false, mColors.textDim));
    card.addView(text(km(meters), 26, true, mColors.text));
    card.addView(text(count + (count == 1 ? " trip · " : " trips · ") + duration(ms), 13, false, mColors.textDim));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    lp.setMargins(dp(4), dp(4), dp(4), dp(4));
    card.setLayoutParams(lp);
    return card;
  }

  @NonNull
  private View tripRow(@NonNull MotoTrips.Trip t)
  {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(16), dp(12), dp(16), dp(12));
    row.setBackground(MotoColors.rounded(mColors.card, dp(16)));
    TextView when = text(time(t.start) + " – " + time(t.end), 16, true, mColors.text);
    row.addView(when, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f));
    row.addView(stat(km(t.meters), "distance"));
    row.addView(stat(duration(t.end - t.start), "duration"));
    row.addView(stat(Math.round(t.avgKmh()) + " km/h", "average"));
    row.addView(stat(Math.round(t.maxKmh) + " km/h", "top speed"));
    row.setOnLongClickListener(v -> {
      new AlertDialog.Builder(this)
          .setMessage("Delete this trip?")
          .setPositiveButton("Delete", (d, w) -> {
            MotoTrips.get(this).delete(t);
            render();
          })
          .setNegativeButton("Cancel", null)
          .show();
      return true;
    });
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                 ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.setMargins(dp(4), dp(4), dp(4), dp(4));
    row.setLayoutParams(lp);
    return row;
  }

  @NonNull
  private View stat(@NonNull String value, @NonNull String label)
  {
    LinearLayout col = new LinearLayout(this);
    col.setOrientation(LinearLayout.VERTICAL);
    col.addView(text(value, 16, true, mColors.text));
    col.addView(text(label, 12, false, mColors.textDim));
    col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    return col;
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

  @NonNull
  private static String km(double meters)
  {
    return meters < 1000 ? Math.round(meters) + " m" : String.format(Locale.ENGLISH, "%.1f km", meters / 1000.0);
  }

  @NonNull
  private static String duration(long ms)
  {
    long min = Math.max(0, Math.round(ms / 60000.0));
    return min >= 60 ? (min / 60) + " h " + (min % 60) + " min" : min + " min";
  }

  @NonNull
  private static String time(long ms)
  {
    return new SimpleDateFormat("HH:mm", Locale.ENGLISH).format(new Date(ms));
  }

  private static long startOfDay(int daysAgo)
  {
    Calendar c = Calendar.getInstance();
    c.set(Calendar.HOUR_OF_DAY, 0);
    c.set(Calendar.MINUTE, 0);
    c.set(Calendar.SECOND, 0);
    c.set(Calendar.MILLISECOND, 0);
    c.add(Calendar.DAY_OF_MONTH, -daysAgo);
    return c.getTimeInMillis();
  }

  private static long startOfMonth()
  {
    Calendar c = Calendar.getInstance();
    c.set(Calendar.DAY_OF_MONTH, 1);
    c.set(Calendar.HOUR_OF_DAY, 0);
    c.set(Calendar.MINUTE, 0);
    c.set(Calendar.SECOND, 0);
    c.set(Calendar.MILLISECOND, 0);
    return c.getTimeInMillis();
  }
}
