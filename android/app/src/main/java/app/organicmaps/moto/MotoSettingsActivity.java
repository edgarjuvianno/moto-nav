package app.organicmaps.moto;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import app.organicmaps.sdk.sound.TtsPlayer;
import app.organicmaps.sdk.util.Config;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

public final class MotoSettingsActivity extends AppCompatActivity implements MotoVoices.Listener, MotoAsr.ModelsListener
{
  private MotoColors mColors;
  private LinearLayout mVoiceList;
  private LinearLayout mAsrList;
  private LinearLayout mPlacesList;
  private LinearLayout mThemeRow;
  private TextView mStatus;
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private final Runnable mStatusTick = new Runnable() {
    @Override
    public void run()
    {
      renderStatus();
      mHandler.postDelayed(this, 2000L);
    }
  };

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
    header.addView(pill("Back", v -> finish()));
    TextView title = text("Settings", 20, true, mColors.text);
    title.setPadding(dp(16), 0, 0, 0);
    header.addView(title);
    root.addView(header);

    ScrollView scroll = new ScrollView(this);
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.HORIZONTAL);
    content.setPadding(dp(8), 0, dp(8), dp(16));
    LinearLayout left = column();
    LinearLayout right = column();
    content.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f));
    content.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    scroll.addView(content);
    root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

    left.addView(voiceCard());
    left.addView(asrCard());
    right.addView(placesCard());
    right.addView(themeCard());
    right.addView(ignitionCard());
    right.addView(mapsCard());
    right.addView(statusCard());
    right.addView(updateCard());

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
    MotoVoices.setListener(this);
    MotoAsr.setModelsListener(this);
    renderVoices();
    renderAsr();
    renderTheme();
    renderPlaces();
    mHandler.post(mStatusTick);
  }

  @Override
  protected void onPause()
  {
    super.onPause();
    MotoVoices.setListener(null);
    MotoAsr.setModelsListener(null);
    mHandler.removeCallbacks(mStatusTick);
  }

  @Override
  public void onVoicesChanged()
  {
    renderVoices();
  }

  @NonNull
  private View voiceCard()
  {
    LinearLayout card = card("Voice guidance");
    card.addView(text("Download a voice to hear turn-by-turn directions. Voices work offline.", 13, false,
                      mColors.textDim));
    mVoiceList = new LinearLayout(this);
    mVoiceList.setOrientation(LinearLayout.VERTICAL);
    mVoiceList.setPadding(0, dp(8), 0, dp(4));
    card.addView(mVoiceList);

    card.addView(label("Speed"));
    TextView speedValue = text("", 13, true, mColors.text);
    SeekBar speed = new SeekBar(this);
    speed.setMax(10);
    speed.setProgress(Math.round((MotoVoices.speed(this) - 0.7f) / 0.08f));
    speedValue.setText(String.format(Locale.ENGLISH, "%.2fx", MotoVoices.speed(this)));
    speed.setOnSeekBarChangeListener(new SimpleSeek() {
      @Override
      public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
      {
        float s = 0.7f + progress * 0.08f;
        speedValue.setText(String.format(Locale.ENGLISH, "%.2fx", s));
        if (fromUser)
          MotoVoices.setSpeed(MotoSettingsActivity.this, s);
      }
    });
    card.addView(sliderRow(speed, speedValue));

    card.addView(label("Volume"));
    TextView volumeValue = text("", 13, true, mColors.text);
    SeekBar volume = new SeekBar(this);
    volume.setMax(10);
    volume.setProgress(Math.round(Config.TTS.getVolume() * 10));
    volumeValue.setText(Math.round(Config.TTS.getVolume() * 100) + "%");
    volume.setOnSeekBarChangeListener(new SimpleSeek() {
      @Override
      public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
      {
        volumeValue.setText(progress * 10 + "%");
        if (fromUser)
          TtsPlayer.INSTANCE.setVolume(progress / 10f);
      }
    });
    card.addView(sliderRow(volume, volumeValue));

    card.addView(switchRow("Announce street names", "Reads the next street name with each turn",
                           Config.TTS.getAnnounceStreets(), Config.TTS::setAnnounceStreets));

    TextView test = pill("Test voice", v -> {
      if (MotoVoices.selected(this) == null)
        Toast.makeText(this, "Download a voice first", Toast.LENGTH_SHORT).show();
      else if (TtsPlayer.getState() == TtsPlayer.State.READY_ON)
        TtsPlayer.INSTANCE.speak("In two hundred meters, turn right. Then, you have arrived.");
      else
        Toast.makeText(this, "Voice is loading, try again in a moment", Toast.LENGTH_SHORT).show();
    });
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
    lp.topMargin = dp(12);
    card.addView(test, lp);
    return card;
  }

  private void renderVoices()
  {
    if (mVoiceList == null)
      return;
    mVoiceList.removeAllViews();
    MotoVoices.Voice selected = MotoVoices.selected(this);
    for (MotoVoices.Voice v : MotoVoices.CATALOG)
    {
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(12), dp(8), dp(8), dp(8));
      boolean isSelected = v == selected;
      row.setBackground(MotoColors.rounded(isSelected ? mColors.surfaceHigh : 0x00000000, dp(14)));
      LinearLayout texts = new LinearLayout(this);
      texts.setOrientation(LinearLayout.VERTICAL);
      texts.addView(text(v.name, 16, true, mColors.text));
      texts.addView(text(v.description, 12, false, mColors.textDim));
      row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
      boolean installed = MotoVoices.isInstalled(this, v);
      int progress = MotoVoices.progress(v);
      TextView action;
      if (progress >= 0)
        action = text("Downloading " + progress + "%", 13, true, mColors.textDim);
      else if (isSelected)
        action = text("In use", 13, true, mColors.ok);
      else if (installed)
        action = pill("Use", x -> MotoVoices.select(this, v));
      else
        action = pill("Download · 63 MB", x -> MotoVoices.download(this, v));
      row.addView(action);
      if (installed && progress < 0)
        row.setOnLongClickListener(x -> {
          new android.app.AlertDialog.Builder(this)
              .setMessage("Delete the " + v.name + " voice?")
              .setPositiveButton("Delete", (d, w) -> MotoVoices.delete(this, v))
              .setNegativeButton("Cancel", null)
              .show();
          return true;
        });
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                   ViewGroup.LayoutParams.WRAP_CONTENT);
      lp.topMargin = dp(4);
      mVoiceList.addView(row, lp);
    }
    if (selected == null)
      mVoiceList.addView(text("No voice installed yet. Navigation stays silent until you download one.", 12,
                              false, mColors.warn));
    else
      mVoiceList.addView(text("Long-press a downloaded voice to delete it.", 12, false, mColors.textDim));
  }

  @Override
  public void onModelsChanged()
  {
    renderAsr();
  }

  @NonNull
  private View asrCard()
  {
    LinearLayout card = card("Voice input");
    card.addView(text("Built-in English speech recognition for the mic button and the intercom. Works offline. "
                          + "Try \"navigate to …\", \"find a gas station\", \"next song\", \"pause music\", "
                          + "\"open Musicolet\".", 13, false, mColors.textDim));
    card.addView(label("Model"));
    mAsrList = new LinearLayout(this);
    mAsrList.setOrientation(LinearLayout.VERTICAL);
    card.addView(mAsrList);
    return card;
  }

  private void renderAsr()
  {
    if (mAsrList == null)
      return;
    mAsrList.removeAllViews();
    MotoAsr.Model selected = MotoAsr.selected(this);
    for (MotoAsr.Model m : MotoAsr.MODELS)
    {
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(12), dp(8), dp(8), dp(8));
      boolean isSelected = m == selected;
      row.setBackground(MotoColors.rounded(isSelected ? mColors.surfaceHigh : 0x00000000, dp(14)));
      LinearLayout texts = new LinearLayout(this);
      texts.setOrientation(LinearLayout.VERTICAL);
      texts.addView(text(m.name, 16, true, mColors.text));
      texts.addView(text(m.description, 12, false, mColors.textDim));
      row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
      boolean installed = MotoAsr.isInstalled(this, m);
      int progress = MotoAsr.progress(m);
      TextView action;
      if (progress >= 0)
        action = text("Downloading " + progress + "%", 13, true, mColors.textDim);
      else if (isSelected)
        action = text("In use", 13, true, mColors.ok);
      else if (installed)
        action = pill("Use", x -> MotoAsr.select(this, m));
      else
        action = pill("Download · " + m.sizeMb + " MB", x -> MotoAsr.download(this, m));
      row.addView(action);
      if (installed && progress < 0)
        row.setOnLongClickListener(x -> {
          new android.app.AlertDialog.Builder(this)
              .setMessage("Delete " + m.name + "?")
              .setPositiveButton("Delete", (d, w) -> MotoAsr.delete(this, m))
              .setNegativeButton("Cancel", null)
              .show();
          return true;
        });
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                   ViewGroup.LayoutParams.WRAP_CONTENT);
      lp.topMargin = dp(4);
      mAsrList.addView(row, lp);
    }
    if (selected == null)
      mAsrList.addView(text("No model yet. The mic falls back to FUTO Voice Input if it is installed.", 12, false,
                            mColors.warn));
  }

  @NonNull
  private View placesCard()
  {
    LinearLayout card = card("Saved places");
    card.addView(text("Say \"navigate to <name>\" or tap the star on the map. Tap a place to rename it, long-press to "
                          + "delete.", 13, false, mColors.textDim));
    mPlacesList = new LinearLayout(this);
    mPlacesList.setOrientation(LinearLayout.VERTICAL);
    mPlacesList.setPadding(0, dp(6), 0, 0);
    card.addView(mPlacesList);
    return card;
  }

  private void renderPlaces()
  {
    if (mPlacesList == null)
      return;
    mPlacesList.removeAllViews();
    java.util.List<MotoPlaces.Place> places = MotoPlaces.load(this);
    if (places.isEmpty())
    {
      mPlacesList.addView(text("No saved places. Use \u2606 Save during navigation or the star button on the map.", 12,
                               false, mColors.textDim));
      return;
    }
    for (MotoPlaces.Place p : places)
    {
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.VERTICAL);
      row.setPadding(dp(12), dp(8), dp(12), dp(8));
      row.setBackground(MotoColors.rounded(mColors.surfaceHigh, dp(14)));
      row.addView(text(p.nickname, 16, true, mColors.text));
      String sub = p.title.isEmpty() ? String.format(Locale.ENGLISH, "%.5f, %.5f", p.lat, p.lon) : p.title;
      row.addView(text(sub, 12, false, mColors.textDim));
      row.setOnClickListener(v -> {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setText(p.nickname);
        input.setSelectAllOnFocus(true);
        new android.app.AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Save", (d, w) -> {
              String nick = input.getText().toString().trim();
              if (!nick.isEmpty())
                MotoPlaces.rename(this, p, nick);
              renderPlaces();
            })
            .setNegativeButton("Cancel", null)
            .show();
      });
      row.setOnLongClickListener(v -> {
        new android.app.AlertDialog.Builder(this)
            .setMessage("Delete \"" + p.nickname + "\"?")
            .setPositiveButton("Delete", (d, w) -> {
              MotoPlaces.remove(this, p);
              renderPlaces();
            })
            .setNegativeButton("Cancel", null)
            .show();
        return true;
      });
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                   ViewGroup.LayoutParams.WRAP_CONTENT);
      lp.topMargin = dp(6);
      mPlacesList.addView(row, lp);
    }
  }

  @NonNull
  private View themeCard()
  {
    LinearLayout card = card("Theme");
    card.addView(text("Auto follows the light sensor.", 13, false, mColors.textDim));
    mThemeRow = new LinearLayout(this);
    mThemeRow.setOrientation(LinearLayout.HORIZONTAL);
    mThemeRow.setPadding(0, dp(10), 0, 0);
    card.addView(mThemeRow);
    return card;
  }

  private void renderTheme()
  {
    mThemeRow.removeAllViews();
    String current = getSharedPreferences("moto", Context.MODE_PRIVATE).getString("forced_theme", "auto");
    String[][] modes = {{"auto", "Auto"}, {"light", "Light"}, {"dark", "Dark"}};
    for (String[] m : modes)
    {
      boolean on = m[0].equals(current);
      TextView t = text(m[1], 15, true, on ? 0xFFFFFFFF : mColors.text);
      t.setGravity(Gravity.CENTER);
      t.setBackground(MotoColors.rounded(on ? mColors.accent : mColors.surfaceHigh, dp(20)));
      t.setOnClickListener(v -> {
        getSharedPreferences("moto", Context.MODE_PRIVATE).edit().putString("forced_theme", m[0]).apply();
        renderTheme();
      });
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
      lp.rightMargin = dp(8);
      mThemeRow.addView(t, lp);
    }
  }

  @NonNull
  private View ignitionCard()
  {
    LinearLayout card = card("Ignition automation");
    card.setVisibility(View.GONE);
    MotoRoot.check(granted -> {
      if (!isFinishing())
        card.setVisibility(granted ? View.VISIBLE : View.GONE);
    });
    card.addView(switchRow("Follow the ignition",
                           "Charger plugged: wake, location on, airplane off. Unplugged: screen off, then location "
                               + "off and airplane on after 15 s. Powers off after 48 h unplugged. Needs root.",
                           MotoIgnitionService.isEnabled(this), on -> MotoIgnitionService.setEnabled(this, on)));
    return card;
  }

  @NonNull
  private View mapsCard()
  {
    LinearLayout card = card("Maps");
    card.addView(text("Offline maps update automatically over Wi-Fi.", 13, false, mColors.textDim));
    TextView update = pill("Manage and update maps", v -> {
      Intent intent = new Intent(this, app.organicmaps.downloader.DownloaderActivity.class);
      intent.putExtra(app.organicmaps.downloader.DownloaderActivity.EXTRA_OPEN_DOWNLOADED, true);
      startActivity(intent);
    });
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
    lp.topMargin = dp(10);
    card.addView(update, lp);
    return card;
  }

  @NonNull
  private View updateCard()
  {
    LinearLayout card = card("App update");
    card.addView(text("Moto Nav " + MotoUpdater.currentVersion(), 14, false, mColors.text));
    TextView status = text("Checks GitHub for the latest release.", 13, false, mColors.textDim);
    status.setMaxLines(4);
    status.setPadding(0, dp(4), 0, 0);
    card.addView(status);
    TextView action = pill("Check for updates", v -> {});
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
    lp.topMargin = dp(10);
    card.addView(action, lp);
    MotoUpdater.InstallCallback progress = new MotoUpdater.InstallCallback() {
      @Override
      public void onProgress(int percent)
      {
        action.setText("Downloading · " + percent + "%");
      }

      @Override
      public void onStatus(@NonNull String message, boolean finished)
      {
        status.setText(message);
        action.setText(finished ? "Check for updates" : "Please wait…");
        action.setEnabled(finished);
        if (finished)
          action.setOnClickListener(v -> checkUpdate(status, action, this));
      }
    };
    action.setOnClickListener(v -> checkUpdate(status, action, progress));
    if (MotoUpdater.isBusy())
    {
      action.setText("Please wait…");
      action.setEnabled(false);
    }
    return card;
  }

  private void checkUpdate(@NonNull TextView status, @NonNull TextView action,
                           @NonNull MotoUpdater.InstallCallback progress)
  {
    action.setEnabled(false);
    action.setText("Checking…");
    MotoUpdater.check((release, error) -> {
      if (isFinishing())
        return;
      action.setEnabled(true);
      if (release == null)
      {
        status.setText(error == null ? "Couldn't check for updates" : error);
        action.setText("Check again");
        return;
      }
      if (!release.isNewer())
      {
        status.setText("You're on the latest version.");
        action.setText("Check again");
        return;
      }
      String size = release.size > 0 ? " · " + Math.max(1, release.size / (1024 * 1024)) + " MB" : "";
      String notes = release.notes.replaceAll("[#*`]", "").replaceAll("\\s+", " ").trim();
      status.setText("Version " + release.version + " is available" + size + (notes.isEmpty() ? "" : "\n" + notes));
      action.setText("Download and install");
      action.setOnClickListener(v -> {
        action.setEnabled(false);
        action.setText("Downloading…");
        MotoUpdater.downloadAndInstall(this, release, progress);
      });
    });
  }

  @NonNull
  private View statusCard()
  {
    LinearLayout card = card("Device status");
    mStatus = text("", 14, false, mColors.text);
    mStatus.setLineSpacing(dp(3), 1f);
    card.addView(mStatus);
    return card;
  }

  private void renderStatus()
  {
    Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    StringBuilder sb = new StringBuilder();
    if (b != null)
    {
      int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
      int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
      float temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
      float volt = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f;
      int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
      int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
      String state = status == BatteryManager.BATTERY_STATUS_CHARGING ? "charging"
                   : status == BatteryManager.BATTERY_STATUS_FULL     ? "full"
                   : plugged != 0                                      ? "plugged, not charging"
                                                                       : "on battery";
      sb.append(String.format(Locale.ENGLISH, "Battery  %d%% · %s\n", level * 100 / Math.max(1, scale), state));
      sb.append(String.format(Locale.ENGLISH, "Battery temperature  %.1f °C\n", temp));
      sb.append(String.format(Locale.ENGLISH, "Battery voltage  %.2f V\n", volt));
    }
    float cpu = MotoThermal.cpuTemp();
    if (cpu > 0)
      sb.append(String.format(Locale.ENGLISH, "CPU temperature  %.1f °C\n", cpu));
    ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
    if (am != null)
    {
      ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
      am.getMemoryInfo(mi);
      sb.append(String.format(Locale.ENGLISH, "Memory  %.1f of %.1f GB free\n", mi.availMem / 1e9, mi.totalMem / 1e9));
    }
    StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
    sb.append(String.format(Locale.ENGLISH, "Storage  %.1f of %.1f GB free\n", fs.getAvailableBytes() / 1e9,
                            fs.getTotalBytes() / 1e9));
    long up = SystemClock.elapsedRealtime() / 60000;
    sb.append(String.format(Locale.ENGLISH, "Uptime  %dh %02dm\n", up / 60, up % 60));
    sb.append("Android ").append(Build.VERSION.RELEASE).append(" · ").append(Build.MODEL);
    mStatus.setText(sb.toString());
  }

  private interface BoolSink
  {
    void set(boolean value);
  }

  private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener
  {
    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {}

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {}
  }

  @NonNull
  private View switchRow(@NonNull String title, @NonNull String desc, boolean value, @NonNull BoolSink sink)
  {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(10), 0, 0);
    LinearLayout texts = new LinearLayout(this);
    texts.setOrientation(LinearLayout.VERTICAL);
    texts.addView(text(title, 15, true, mColors.text));
    texts.addView(text(desc, 12, false, mColors.textDim));
    row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    SwitchCompat sw = new SwitchCompat(this);
    sw.setChecked(value);
    sw.setOnCheckedChangeListener((b, on) -> sink.set(on));
    row.addView(sw);
    return row;
  }

  @NonNull
  private View sliderRow(@NonNull SeekBar bar, @NonNull TextView value)
  {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.addView(bar, new LinearLayout.LayoutParams(0, dp(40), 1f));
    value.setMinWidth(dp(52));
    value.setGravity(Gravity.END);
    row.addView(value);
    return row;
  }

  @NonNull
  private TextView label(@NonNull String s)
  {
    TextView t = text(s, 13, true, mColors.textDim);
    t.setPadding(0, dp(10), 0, 0);
    return t;
  }

  @NonNull
  private LinearLayout column()
  {
    LinearLayout c = new LinearLayout(this);
    c.setOrientation(LinearLayout.VERTICAL);
    return c;
  }

  @NonNull
  private LinearLayout card(@NonNull String title)
  {
    LinearLayout c = new LinearLayout(this);
    c.setOrientation(LinearLayout.VERTICAL);
    c.setPadding(dp(18), dp(14), dp(18), dp(16));
    c.setBackground(MotoColors.rounded(mColors.card, dp(20)));
    TextView t = text(title, 18, true, mColors.text);
    t.setPadding(0, 0, 0, dp(4));
    c.addView(t);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                 ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.setMargins(dp(4), dp(4), dp(4), dp(8));
    c.setLayoutParams(lp);
    return c;
  }

  @NonNull
  private TextView pill(@NonNull String label, @NonNull View.OnClickListener onClick)
  {
    TextView t = text(label, 14, true, mColors.text);
    t.setGravity(Gravity.CENTER);
    t.setPadding(dp(16), dp(10), dp(16), dp(10));
    t.setBackground(MotoColors.rounded(mColors.surfaceHigh, dp(18)));
    t.setOnClickListener(onClick);
    return t;
  }

  @NonNull
  private TextView text(@NonNull String s, int sp, boolean bold, int color)
  {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    t.setTextColor(color);
    t.setEllipsize(TextUtils.TruncateAt.END);
    if (bold)
      t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    return t;
  }
}
