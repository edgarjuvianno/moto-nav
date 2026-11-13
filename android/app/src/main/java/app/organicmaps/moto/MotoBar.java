package app.organicmaps.moto;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import app.organicmaps.MwmActivity;
import app.organicmaps.MwmApplication;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.util.Config;
import app.organicmaps.util.Utils;
import app.organicmaps.util.ThemeSwitcher;
import app.organicmaps.R;
import java.text.SimpleDateFormat;
import app.organicmaps.sdk.sound.TtsPlayer;
import android.util.Log;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@SuppressLint("ViewConstructor")
public final class MotoBar extends LinearLayout
    implements DefaultLifecycleObserver, MotoMedia.Listener, MotoChronos.Listener
{
  private static final String FUTO_PACKAGE = "org.futo.voiceinput";
  private static final Locale INDONESIA = Locale.ENGLISH;

  private final MwmActivity mActivity;
  private final MotoMedia mMedia;
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private final LocationManager mLocationManager;
  private final ActivityResultLauncher<Intent> mVoiceLauncher;
  private MotoColors mColors;

  private final TextView mClock;
  private final TextView mDate;
  private final ImageView mBatteryIcon;
  private final TextView mBatteryText;
  private final ImageView mPhoneIcon;
  private final TextView mPhoneText;
  private boolean mPhoneLowWarned;
  private String mRainWarnedKey = "";
  private final TextView mSpeed;
  private final ImageView mGpsIcon;
  private final TextView mSpeedUnit;
  private final TextView mGpsText;
  private int mSatsUsed;
  private int mSatsSeen;
  private boolean mGnssRegistered;

  private final GnssStatus.Callback mGnssCallback = new GnssStatus.Callback() {
    @Override
    public void onSatelliteStatusChanged(@NonNull GnssStatus status)
    {
      int used = 0;
      for (int i = 0; i < status.getSatelliteCount(); i++)
        if (status.usedInFix(i))
          used++;
      mSatsUsed = used;
      mSatsSeen = status.getSatelliteCount();
    }

    @Override
    public void onStopped()
    {
      mSatsUsed = 0;
      mSatsSeen = 0;
    }
  };
  private final LinearLayout mMediaBox;
  private final LinearLayout mSide;
  private final MotoCornerMask mMapMask;
  @Nullable
  private View mRoot;
  private final LinearLayout mLoader;
  private final android.widget.ProgressBar mLoaderSpinner;
  private final TextView mLoaderText;
  private final TextView mLoaderCancel;

  private final Runnable mLoaderPoll = new Runnable() {
    @Override
    public void run()
    {
      updateLoader();
      mHandler.postDelayed(this, 250L);
    }
  };
  private android.widget.FrameLayout mMapCard;
  private static MotoBar sBar;
  private boolean mFocus;
  private MotoPanels mPanels;
  private android.widget.LinearLayout mPlacesList;
  private int mInsetRight;
  private final LinearLayout mDock;
  private final ImageView mArt;
  private final android.widget.FrameLayout mInfoCard;
  private final LinearLayout mIdleBox;
  private final View mDivider;
  private View mVDivider;
  @Nullable
  private Boolean mBarsNavMode;
  private final TextView mNavSpeed;
  private final TextView mNavSpeedUnit;
  private final TextView mEtaTime;
  private final TextView mEndNav;
  private final android.widget.FrameLayout mListen;
  private final View mListenRing;
  private final TextView mListenTitle;
  private final TextView mListenSub;
  private final LinearLayout mHeat;
  private final TextView mHeatText;
  private static boolean sSetupReminded;
  private boolean mHeatDismissed;
  private long mLastHeatCheck;
  @Nullable
  private View mWakeSplash;
  private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent)
    {
      String action = intent.getAction();
      if (Intent.ACTION_SCREEN_OFF.equals(action))
      {
        if (!mActivity.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
          MotoRoot.run("am start -n " + mActivity.getPackageName() + "/app.organicmaps.MwmActivity");
      }
      else if (Intent.ACTION_SCREEN_ON.equals(action))
      {
        KeyguardManager km = (KeyguardManager) mActivity.getSystemService(Context.KEYGUARD_SERVICE);
        if (km == null || !km.isKeyguardLocked())
          showWakeSplash();
      }
      else if (Intent.ACTION_USER_PRESENT.equals(action))
        showWakeSplash();
    }
  };
  private final LinearLayout mBanner;
  private final TextView mBannerApp;
  private final TextView mBannerTitle;
  private final TextView mBannerText;
  private final TextView mBannerAction;
  @Nullable
  private Runnable mBannerActionRun;
  private boolean mBannerCall;
  private final Runnable mHideBanner = this::hideBanner;

  private void hideBanner()
  {
    mBannerCall = false;
    mBannerActionRun = null;
    mBanner.animate().alpha(0f).translationY(-dp(40)).setDuration(220L)
        .withEndAction(() -> mBanner.setVisibility(GONE)).start();
  }
  private final ImageView mFuel;
  private final ImageView mStar;
  private final TextView mSaveDest;
  private final LinearLayout mNavBox;
  private final ImageView mTurnIcon;
  private final TextView mTurnDist;
  private final TextView mNextStreet;
  private final TextView mEta;
  private final ImageView mPrev;
  private final ImageView mMusicIcon;
  private final MotoPager mPager;
  private final LinearLayout mDots;
  private final TextView mWeatherIcon;
  private final TextView mWeatherTemp;
  private final TextView mWeatherUnit;
  private final TextView mWeatherDesc;
  private final TextView mWeatherSub;
  private final LinearLayout mNotifList;
  private final android.widget.ScrollView mNotifScroll;
  private final List<String[]> mNotifs = new ArrayList<>();
  private Typeface mCondensed;
  private static final long PAGER_RETURN_MS = 15_000L;
  private final Runnable mPagerReturn = this::returnPager;

  private void returnPager()
  {
    if (mPager.getPage() != 0)
      mPager.setPage(0, true);
  }
  private final TextView mTitle;
  private final TextView mArtist;
  private final ImageView mPlay;
  private final ImageView mNext;
  private final List<ImageView> mShortcutViews = new ArrayList<>();
  private final List<ImageView> mActionViews = new ArrayList<>();
  private List<String> mDockItems = new ArrayList<>();
  private final ImageView mApps;
  private final ImageView mMic;
  private final LinearLayout mSearchBox;
  private final ImageView mSearchIcon;
  private final TextView mSearchHint;

  private static final float DARK_LUX = 15f;
  private static final float LIGHT_LUX = 60f;
  private static final long THEME_DELAY_MS = 6000L;

  @Nullable
  private final SensorManager mSensorManager;
  @Nullable
  private final Sensor mLightSensor;
  @Nullable
  private Boolean mLightDark;
  @Nullable
  private Boolean mPendingDark;
  private long mPendingSince;

  private final SensorEventListener mLightListener = new SensorEventListener() {
    @Override
    public void onSensorChanged(SensorEvent event)
    {
      onLux(event.values[0]);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
  };

  @Nullable
  private Location mLocation;
  private long mFixTime;
  private boolean mProviderEnabled = true;
  private boolean mLocationRunning;
  private int mBatteryPercent = -1;
  private long mLastMapTouch;
  private long mLastRecenter;
  private boolean mPlugged;
  private boolean mCharging;
  private boolean mMediaAccess;
  private boolean mPlaying;

  private final Runnable mTicker = new Runnable() {
    @Override
    public void run()
    {
      updateClock();
      updateSpeed();
      updateNav();
      enforceBars();
      autoRecenter();
      MotoTrips.get(mActivity).tick();
      checkMapUpdate();
      checkHeat();
      applyKeepScreenOn();
      mHandler.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L);
    }
  };

  // The screen stays on while charging (on the bike). MwmActivity also sets the flag on start and when
  // navigation stops, ignoring the charger, so the 1 s ticker restores it.
  private void applyKeepScreenOn()
  {
    boolean on = mPlugged || Config.isKeepScreenOnEnabled() || RoutingController.get().isNavigating();
    boolean isOn = (mActivity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0;
    if (on != isOn)
      Utils.keepScreenOn(on, mActivity.getWindow());
  }

  private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent)
    {
      int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
      int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
      int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
      mPlugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
      mCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
      mBatteryPercent = level >= 0 && scale > 0 ? level * 100 / scale : -1;
      applyKeepScreenOn();
      updateBattery();
    }
  };

  private final LocationListener mLocationListener = new LocationListener() {
    @Override
    public void onLocationChanged(@NonNull Location location)
    {
      mLocation = location;
      mFixTime = SystemClock.elapsedRealtime();
      mProviderEnabled = true;
      MotoTrips.get(mActivity).onLocation(location);
      updateSpeed();
      mActivity.onMotoLocation();
    }

    @Override
    public void onProviderEnabled(@NonNull String provider)
    {
      mProviderEnabled = true;
      updateSpeed();
    }

    @Override
    public void onProviderDisabled(@NonNull String provider)
    {
      mProviderEnabled = false;
      updateSpeed();
    }
  };

  public static void install(@NonNull MwmActivity activity)
  {
    disableOverlayLayers();
    setupVoiceGuidance(activity);
    MotoIgnitionService.start(activity);
    MotoUpdater.cleanup(activity);
    ViewGroup content = activity.findViewById(android.R.id.content);
    View coordinator = activity.findViewById(R.id.coordinator);
    if (content == null || coordinator == null)
      return;
    content.removeView(coordinator);
    LinearLayout column = new LinearLayout(activity);
    column.setOrientation(VERTICAL);
    MotoBar bar = new MotoBar(activity);
    android.widget.FrameLayout row = new android.widget.FrameLayout(activity);
    android.widget.FrameLayout mapCard = new android.widget.FrameLayout(activity) {
      @Override
      public boolean dispatchTouchEvent(android.view.MotionEvent ev)
      {
        bar.mLastMapTouch = SystemClock.elapsedRealtime();
        return super.dispatchTouchEvent(ev);
      }
    };
    mapCard.addView(coordinator, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                             ViewGroup.LayoutParams.MATCH_PARENT));
    bar.mPanels = new MotoPanels(activity, bar.panelHost());
    mapCard.addView(bar.mPanels.mRoot, new android.widget.FrameLayout.LayoutParams(
                                          bar.dp(300), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));
    mapCard.addView(bar.mMapMask, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                              ViewGroup.LayoutParams.MATCH_PARENT));
    android.widget.FrameLayout.LayoutParams endLp = new android.widget.FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
    endLp.leftMargin = bar.dp(14);
    endLp.bottomMargin = bar.dp(4);
    bar.mEndNav.setPadding(bar.dp(16), 0, bar.dp(16), 0);
    bar.mEndNav.setElevation(bar.dp(12));
    LinearLayout pills = new LinearLayout(activity);
    pills.setOrientation(HORIZONTAL);
    pills.addView(bar.mEndNav, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, bar.dp(38)));
    bar.mSaveDest.setPadding(bar.dp(16), 0, bar.dp(16), 0);
    bar.mSaveDest.setElevation(bar.dp(12));
    LayoutParams saveLp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, bar.dp(38));
    saveLp.leftMargin = bar.dp(8);
    pills.addView(bar.mSaveDest, saveLp);
    pills.setClipToPadding(false);
    pills.setClipChildren(false);
    pills.setPadding(0, 0, 0, bar.dp(12));
    mapCard.addView(pills, endLp);
    bar.mPills = pills;
    mapCard.addView(bar.mListen, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                             ViewGroup.LayoutParams.MATCH_PARENT));
    android.widget.FrameLayout.LayoutParams loaderLp =
        new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    loaderLp.topMargin = bar.dp(16);
    mapCard.addView(bar.mLoader, loaderLp);
    bar.mMapCard = mapCard;
    bar.mRoot = column;
    sBar = bar;
    new androidx.lifecycle.ViewModelProvider(activity)
        .get(app.organicmaps.search.SearchPageViewModel.class)
        .getSearchEnabled()
        .observe(activity, enabled -> {
          if (Boolean.TRUE.equals(enabled))
          {
            String q = app.organicmaps.sdk.search.SearchEngine.INSTANCE.getQuery();
            activity.forceCloseSearchFragment();
            bar.mPanels.openSearch(q);
          }
        });
    new androidx.lifecycle.ViewModelProvider(activity)
        .get(app.organicmaps.widget.placepage.PlacePageViewModel.class)
        .getMapObject()
        .observe(activity, obj -> bar.mPanels.setPlace(obj != null && !obj.isTrackRecording() ? obj : null));
    coordinator.getViewTreeObserver().addOnGlobalLayoutListener(bar::stylePanels);
    row.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
      if (r - l != or - ol || b - t != ob - ot)
        bar.mHandler.post(bar::applyStage);
    });
    column.setBackgroundColor(bar.mColors.screen);
    row.addView(mapCard, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                     ViewGroup.LayoutParams.MATCH_PARENT));
    row.addView(bar.mSide, new android.widget.FrameLayout.LayoutParams(bar.dp(300), ViewGroup.LayoutParams.MATCH_PARENT,
                                                                       Gravity.END));
    column.addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    column.addView(bar, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    content.addView(column, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                       ViewGroup.LayoutParams.MATCH_PARENT));
    android.widget.FrameLayout.LayoutParams bannerLp = new android.widget.FrameLayout.LayoutParams(
        bar.dp(520), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    bannerLp.topMargin = bar.dp(12);
    content.addView(bar.mBanner, bannerLp);
    android.widget.FrameLayout.LayoutParams heatLp = new android.widget.FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    heatLp.topMargin = bar.dp(12);
    content.addView(bar.mHeat, content.indexOfChild(bar.mBanner), heatLp);
    ViewCompat.setOnApplyWindowInsetsListener(column, (v, insets) -> {
      Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
      Insets nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
      bar.setInsets(bars.left, bars.right, bars.bottom);
      bar.mInsetRight = bars.right;
      bar.mSide.setPadding(bar.dp(12), bar.dp(12) + bars.top, bar.dp(12) + bars.right, 0);
      ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) bar.mMapCard.getLayoutParams();
      mlp.leftMargin = bar.dp(12) + bars.left;
      mlp.topMargin = bar.dp(12) + bars.top;
      mlp.rightMargin = bar.dp(12) + bars.right;
      bar.mMapCard.setLayoutParams(mlp);
      bar.mHandler.post(bar::applyStage);
      WindowInsetsCompat child = new WindowInsetsCompat.Builder(insets)
                                     .setInsets(WindowInsetsCompat.Type.systemBars(),
                                                Insets.of(0, 0, 0, 0))
                                     .setInsets(WindowInsetsCompat.Type.navigationBars(),
                                                Insets.of(0, 0, 0, 0))
                                     .build();
      ViewCompat.dispatchApplyWindowInsets(coordinator, child);
      return WindowInsetsCompat.CONSUMED;
    });
    activity.getLifecycle().addObserver(bar);
    IntentFilter screenFilter = new IntentFilter();
    screenFilter.addAction(Intent.ACTION_SCREEN_ON);
    screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
    screenFilter.addAction(Intent.ACTION_USER_PRESENT);
    ContextCompat.registerReceiver(activity, bar.mScreenReceiver, screenFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
    column.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
      if (hasFocus)
        bar.hideStatusBar();
    });
    bar.attachSearchBox(activity.findViewById(R.id.map_container));
  }

  private void attachSearchBox(@Nullable ViewGroup mapContainer)
  {
    if (!(mapContainer instanceof RelativeLayout))
      return;
    RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(dp(380), dp(54));
    lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
    lp.addRule(RelativeLayout.ALIGN_PARENT_START);
    lp.leftMargin = dp(12);
    lp.topMargin = dp(12);
    mapContainer.addView(mSearchBox, lp);
    RelativeLayout.LayoutParams fuelLp = new RelativeLayout.LayoutParams(dp(54), dp(54));
    fuelLp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
    fuelLp.addRule(RelativeLayout.ALIGN_PARENT_START);
    fuelLp.leftMargin = dp(12 + 380 + 10);
    fuelLp.topMargin = dp(12);
    mapContainer.addView(mFuel, fuelLp);
    RelativeLayout.LayoutParams starLp = new RelativeLayout.LayoutParams(dp(54), dp(54));
    starLp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
    starLp.addRule(RelativeLayout.ALIGN_PARENT_START);
    starLp.leftMargin = dp(12 + 380 + 10 + 54 + 10);
    starLp.topMargin = dp(12);
    mapContainer.addView(mStar, starLp);
    ViewCompat.setOnApplyWindowInsetsListener(mSearchBox, (v, insets) -> {
      Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
      lp.topMargin = bars.top + dp(12);
      lp.leftMargin = bars.left + dp(12);
      v.setLayoutParams(lp);
      fuelLp.topMargin = bars.top + dp(12);
      fuelLp.leftMargin = bars.left + dp(12 + 380 + 10);
      mFuel.setLayoutParams(fuelLp);
      starLp.topMargin = bars.top + dp(12);
      starLp.leftMargin = bars.left + dp(12 + 380 + 10 + 54 + 10);
      mStar.setLayoutParams(starLp);
      return insets;
    });
  }

  private static boolean sVoiceListener;

  private static void setupVoiceGuidance(@NonNull Context context)
  {
    app.organicmaps.sdk.util.Config.TTS.setLanguage("en");
    app.organicmaps.sdk.util.Config.TTS.setEnabled(isVoiceWanted());
    TtsPlayer.State st = TtsPlayer.getState();
    if (st == TtsPlayer.State.READY_ON || st == TtsPlayer.State.READY_OFF || st == TtsPlayer.State.NEEDS_LANGUAGE)
      TtsPlayer.INSTANCE.refreshLanguages();
    if (!sVoiceListener)
    {
      sVoiceListener = true;
      TtsPlayer.addStateChangedListener(() -> {
        if (TtsPlayer.getState() == TtsPlayer.State.READY_OFF && isVoiceWanted())
          TtsPlayer.setEnabled(true);
      });
    }
  }

  private static boolean isVoiceWanted()
  {
    return true;
  }

  private long mLastUpdateCheck = SystemClock.elapsedRealtime() - 29 * 60_000L;

  private void checkMapUpdate()
  {
    long now = SystemClock.elapsedRealtime();
    if (now - mLastUpdateCheck < 30 * 60_000L)
      return;
    mLastUpdateCheck = now;
    if (RoutingController.get().isNavigating() || mRiding || !isUnmeteredWifi())
      return;
    MotoUpdater.checkInBackground(mActivity, (release, error) -> {
      if (release != null && !mRiding && !RoutingController.get().isNavigating())
        showBanner("Moto Nav", "Update available", "Version " + release.version + " is ready to install", false,
                   10_000L, "Open",
                   () -> mActivity.startActivity(new Intent(mActivity, MotoSettingsActivity.class)));
    });
    try
    {
      if (app.organicmaps.sdk.downloader.MapManager.nativeIsDownloading())
        return;
      String root = app.organicmaps.sdk.downloader.MapManager.nativeGetRoot();
      app.organicmaps.sdk.downloader.UpdateInfo info = app.organicmaps.sdk.downloader.MapManager.nativeGetUpdateInfo(root);
      if (info == null || info.filesCount <= 0)
        return;
      if (!app.organicmaps.sdk.downloader.MapManager.nativeHasSpaceToUpdate(root))
      {
        toast("Map update available, but storage is full");
        return;
      }
      app.organicmaps.sdk.downloader.MapManager.startUpdate(root);
      toast("Updating offline maps · " + Math.max(1, info.totalSize / (1024 * 1024)) + " MB over Wi-Fi");
    }
    catch (RuntimeException ignored)
    {
    }
  }

  private boolean isUnmeteredWifi()
  {
    android.net.ConnectivityManager cm =
        (android.net.ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
    if (cm == null)
      return false;
    android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
    return caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
  }

  private static final float RIDING_ON_KMH = 15f;
  private static final float RIDING_OFF_KMH = 5f;
  private static final long RIDING_OFF_MS = 20_000L;
  private boolean mRiding;
  private static volatile boolean sRiding;

  public static boolean isRiding()
  {
    return sRiding;
  }
  private long mSlowSince;

  private void updateRiding(boolean fresh, float kmh)
  {
    long now = SystemClock.elapsedRealtime();
    if (!mRiding)
    {
      if (fresh && kmh >= RIDING_ON_KMH)
      {
        mRiding = true;
        mSlowSince = 0;
        applyRiding();
      }
      return;
    }
    if (fresh && kmh >= RIDING_OFF_KMH)
    {
      mSlowSince = 0;
      return;
    }
    if (mSlowSince == 0)
      mSlowSince = now;
    else if (now - mSlowSince > RIDING_OFF_MS)
    {
      mRiding = false;
      applyRiding();
    }
  }

  private void applyRiding()
  {
    sRiding = mRiding;
    if (mRiding && mPanels != null && mPanels.mode() == MotoPanels.Mode.SAVED)
      mPanels.close();
    updateFocus();
    updatePending();
    mApps.setAlpha(mRiding ? 0.35f : 1f);
    View zoom = mActivity.findViewById(R.id.zoom_buttons_container);
    if (zoom != null)
      zoom.setVisibility(mRiding || RoutingController.get().isNavigating() ? GONE : VISIBLE);
  }

  private void searchFuel()
  {
    runUnlocked(() -> mActivity.showSearch("Gas station ", "en", false));
  }

  private static void disableOverlayLayers()
  {
    if (app.organicmaps.sdk.Framework.nativeIsTransitSchemeEnabled())
    {
      app.organicmaps.sdk.Framework.nativeSaveSettingSchemeEnabled(false);
      app.organicmaps.sdk.Framework.nativeSetTransitSchemeEnabled(false);
    }
    if (app.organicmaps.sdk.Framework.nativeIsIsolinesLayerEnabled())
      app.organicmaps.sdk.Framework.nativeSetIsolinesLayerEnabled(false);
    if (app.organicmaps.sdk.Framework.nativeIsOutdoorsLayerEnabled())
      app.organicmaps.sdk.Framework.nativeSetOutdoorsLayerEnabled(false);
  }

  private MotoBar(@NonNull MwmActivity activity)
  {
    super(activity);
    mActivity = activity;
    mMedia = new MotoMedia(activity);
    mLocationManager = activity.getSystemService(LocationManager.class);
    mSensorManager = activity.getSystemService(SensorManager.class);
    mLightSensor = mSensorManager == null ? null : mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
    mVoiceLauncher = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
      ArrayList<String> heard = result.getData() == null
                                    ? null
                                    : result.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
      if (result.getResultCode() == Activity.RESULT_OK && heard != null && !heard.isEmpty())
        handleVoice(heard);
    });
    mColors = MotoColors.of(activity);
    String forced = activity.getSharedPreferences("moto", Context.MODE_PRIVATE).getString("forced_theme", "auto");
    sForcedDark = "dark".equals(forced) ? Boolean.TRUE : "light".equals(forced) ? Boolean.FALSE : null;

    setOrientation(HORIZONTAL);
    setGravity(Gravity.CENTER_VERTICAL);
    setMinimumHeight(dp(64));
    setPadding(dp(16), 0, dp(12), 0);
    mMapMask = new MotoCornerMask(activity, dp(24));
    // A compact pill on top of the map, so the route being built stays visible underneath.
    mLoader = new LinearLayout(activity);
    mLoader.setOrientation(HORIZONTAL);
    mLoader.setGravity(Gravity.CENTER_VERTICAL);
    mLoader.setPadding(dp(16), dp(8), dp(8), dp(8));
    mLoader.setElevation(dp(8));
    mLoader.setVisibility(GONE);
    mLoaderSpinner = new android.widget.ProgressBar(activity);
    mLoaderSpinner.setIndeterminate(true);
    mLoader.addView(mLoaderSpinner, new LayoutParams(dp(28), dp(28)));
    mLoaderText = text(17, true);
    mLoaderText.setPadding(dp(12), 0, dp(16), 0);
    mLoader.addView(mLoaderText, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    mLoaderCancel = text(15, true);
    mLoaderCancel.setText("Cancel");
    mLoaderCancel.setGravity(Gravity.CENTER);
    mLoaderCancel.setPadding(dp(16), 0, dp(16), 0);
    mLoaderCancel.setOnClickListener(v -> endNavigation());
    mLoader.addView(mLoaderCancel, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));

    mListen = new android.widget.FrameLayout(activity);
    mListen.setVisibility(GONE);
    mListen.setClickable(true);
    mListen.setBackgroundColor(0xB3000000);
    LinearLayout listenBox = new LinearLayout(activity);
    listenBox.setOrientation(VERTICAL);
    listenBox.setGravity(Gravity.CENTER_HORIZONTAL);
    android.widget.FrameLayout micStack = new android.widget.FrameLayout(activity);
    mListenRing = new View(activity);
    mListenRing.setBackground(MotoColors.oval(0x55F01826));
    micStack.setClipChildren(false);
    micStack.addView(mListenRing, new android.widget.FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER));
    ImageView micBig = new ImageView(activity);
    micBig.setImageResource(R.drawable.ic_moto_mic);
    micBig.setColorFilter(0xFFFFFFFF);
    micBig.setPadding(dp(22), dp(22), dp(22), dp(22));
    micBig.setBackground(MotoColors.oval(0xFFF01826));
    micStack.addView(micBig, new android.widget.FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER));
    listenBox.setClipChildren(false);
    listenBox.addView(micStack, new LayoutParams(dp(180), dp(180)));
    mListenTitle = text(22, true);
    mListenTitle.setTextColor(0xFFFFFFFF);
    mListenTitle.setGravity(Gravity.CENTER);
    listenBox.addView(mListenTitle, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    mListenSub = text(16, false);
    mListenSub.setTextColor(0xCCFFFFFF);
    mListenSub.setGravity(Gravity.CENTER);
    mListenSub.setPadding(dp(24), dp(4), dp(24), 0);
    listenBox.addView(mListenSub, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    TextView listenCancel = text(16, true);
    listenCancel.setText("Cancel");
    listenCancel.setTextColor(0xFFFFFFFF);
    listenCancel.setGravity(Gravity.CENTER);
    listenCancel.setBackground(MotoColors.rounded(0x33FFFFFF, dp(24)));
    listenCancel.setOnClickListener(v -> {
      MotoAsr.cancel();
      hideListen();
    });
    LayoutParams lcLp = new LayoutParams(dp(140), dp(48));
    lcLp.topMargin = dp(18);
    listenBox.addView(listenCancel, lcLp);
    mListen.setClipChildren(false);
    mListen.addView(listenBox, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                           ViewGroup.LayoutParams.WRAP_CONTENT,
                                                                           Gravity.CENTER));

    mMic = roundButton(R.drawable.ic_moto_mic, 46);
    mMic.setContentDescription("Voice command");
    mMic.setOnClickListener(v -> runUnlocked(this::startVoice));
    LinearLayout clockCol = new LinearLayout(activity);
    clockCol.setOrientation(HORIZONTAL);
    clockCol.setGravity(Gravity.CENTER_VERTICAL);
    mClock = text(40, true);
    LinearLayout dateRow = new LinearLayout(activity);
    dateRow.setOrientation(VERTICAL);
    dateRow.setPadding(dp(12), 0, 0, 0);
    mDate = text(15, false);
    mDate.setSingleLine(true);
    dateRow.addView(mDate);
    LinearLayout battRow = new LinearLayout(activity);
    battRow.setOrientation(HORIZONTAL);
    battRow.setGravity(Gravity.CENTER_VERTICAL);
    battRow.setPadding(0, dp(2), 0, 0);
    mBatteryIcon = icon(R.drawable.ic_moto_battery);
    battRow.addView(mBatteryIcon, new LayoutParams(dp(16), dp(16)));
    mBatteryText = text(15, true);
    mBatteryText.setSingleLine(true);
    battRow.addView(mBatteryText);
    mPhoneIcon = icon(R.drawable.ic_moto_phone);
    LayoutParams phoneLp = new LayoutParams(dp(16), dp(16));
    phoneLp.leftMargin = dp(10);
    battRow.addView(mPhoneIcon, phoneLp);
    mPhoneText = text(15, true);
    mPhoneText.setSingleLine(true);
    battRow.addView(mPhoneText);
    dateRow.addView(battRow);
    clockCol.addView(mClock);
    clockCol.addView(dateRow);
    LayoutParams clockLp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    clockLp.rightMargin = dp(16);

    mSide = new LinearLayout(activity);
    mSide.setOrientation(VERTICAL);
    mSide.setPadding(dp(12), dp(12), dp(12), 0);

    mInfoCard = new android.widget.FrameLayout(activity);
    mInfoCard.setPadding(dp(16), dp(12), dp(16), dp(12));

    mIdleBox = new LinearLayout(activity);
    mIdleBox.setOrientation(VERTICAL);
    mIdleBox.setGravity(Gravity.CENTER);
    mSpeed = text(70, true);
    mSpeed.setGravity(Gravity.CENTER);
    mIdleBox.addView(mSpeed, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    LinearLayout unitRow = new LinearLayout(activity);
    unitRow.setOrientation(HORIZONTAL);
    unitRow.setGravity(Gravity.CENTER_VERTICAL);
    mGpsIcon = icon(R.drawable.ic_moto_gps);
    unitRow.addView(mGpsIcon, new LayoutParams(dp(16), dp(16)));
    mSpeedUnit = text(16, true);
    mSpeedUnit.setText("km/h");
    mSpeedUnit.setPadding(dp(6), 0, 0, 0);
    unitRow.addView(mSpeedUnit, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    mIdleBox.addView(unitRow, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    mGpsText = text(13, false);
    mGpsText.setPadding(0, dp(6), 0, 0);
    mIdleBox.addView(mGpsText, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    mInfoCard.addView(mIdleBox, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                            ViewGroup.LayoutParams.MATCH_PARENT));

    mNavBox = new LinearLayout(activity);
    mNavBox.setOrientation(VERTICAL);
    LinearLayout turnRow = new LinearLayout(activity);
    turnRow.setOrientation(HORIZONTAL);
    turnRow.setGravity(Gravity.CENTER_VERTICAL);
    mTurnIcon = new ImageView(activity);
    turnRow.setGravity(Gravity.CENTER);
    turnRow.addView(mTurnIcon, new LayoutParams(dp(64), dp(64)));
    mTurnDist = text(40, true);
    mTurnDist.setPadding(dp(12), 0, 0, 0);
    mTurnDist.setSingleLine(true);
    turnRow.addView(mTurnDist, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    mNavBox.addView(turnRow);
    mNextStreet = text(16, true);
    mNextStreet.setSingleLine(true);
    mNextStreet.setEllipsize(TextUtils.TruncateAt.END);
    mNextStreet.setPadding(0, dp(4), 0, 0);
    mNextStreet.setVisibility(GONE);
    mDivider = new View(activity);
    LayoutParams divLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
    divLp.topMargin = dp(12);
    divLp.bottomMargin = dp(12);
    mNavBox.addView(mDivider, divLp);
    LinearLayout bottomRow = new LinearLayout(activity);
    bottomRow.setOrientation(HORIZONTAL);
    bottomRow.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout speedCol = new LinearLayout(activity);
    speedCol.setOrientation(VERTICAL);
    speedCol.setGravity(Gravity.CENTER_HORIZONTAL);
    mNavSpeed = text(26, true);
    speedCol.addView(mNavSpeed, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    mNavSpeedUnit = text(13, true);
    mNavSpeedUnit.setText("km/h");
    speedCol.addView(mNavSpeedUnit, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    bottomRow.addView(speedCol, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    View vDiv = new View(activity);
    mVDivider = vDiv;
    bottomRow.addView(vDiv, new LayoutParams(dp(1), dp(48)));
    LinearLayout etaCol = new LinearLayout(activity);
    etaCol.setOrientation(VERTICAL);
    etaCol.setGravity(Gravity.CENTER_HORIZONTAL);
    mEtaTime = text(26, true);
    mEtaTime.setSingleLine(true);
    etaCol.addView(mEtaTime, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    mEta = text(14, false);
    mEta.setSingleLine(true);
    etaCol.addView(mEta, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    bottomRow.addView(etaCol, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
    mEndNav = new TextView(activity);
    mEndNav.setText("End");
    mEndNav.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
    mEndNav.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    mEndNav.setGravity(Gravity.CENTER);
    mEndNav.setTextColor(0xFFFFFFFF);
    mEndNav.setOnClickListener(v -> endNavigation());
    mEndNav.setVisibility(GONE);
    mHeat = new LinearLayout(activity);
    mHeat.setOrientation(HORIZONTAL);
    mHeat.setGravity(Gravity.CENTER_VERTICAL);
    mHeat.setPadding(dp(16), dp(8), dp(6), dp(8));
    mHeat.setElevation(dp(10));
    mHeat.setVisibility(GONE);
    mHeat.setBackground(MotoColors.rounded(0xFFFFB300, dp(22)));
    mHeatText = text(15, true);
    mHeatText.setTextColor(0xFF2B1D00);
    mHeat.addView(mHeatText);
    TextView heatClose = text(18, true);
    heatClose.setText("\u2715");
    heatClose.setTextColor(0xFF2B1D00);
    heatClose.setGravity(Gravity.CENTER);
    heatClose.setOnClickListener(v -> {
      mHeatDismissed = true;
      mHeat.setVisibility(GONE);
    });
    LayoutParams closeLp = new LayoutParams(dp(40), dp(40));
    closeLp.leftMargin = dp(8);
    mHeat.addView(heatClose, closeLp);
    mBanner = new LinearLayout(activity);
    mBanner.setOrientation(HORIZONTAL);
    mBanner.setGravity(Gravity.CENTER_VERTICAL);
    mBanner.setPadding(dp(18), dp(12), dp(14), dp(14));
    LinearLayout bannerText = new LinearLayout(activity);
    bannerText.setOrientation(VERTICAL);
    mBanner.addView(bannerText, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    mBanner.setElevation(dp(14));
    mBanner.setVisibility(GONE);
    mBannerApp = text(13, true);
    mBannerApp.setSingleLine(true);
    bannerText.addView(mBannerApp);
    mBannerTitle = text(18, true);
    mBannerTitle.setSingleLine(true);
    mBannerTitle.setEllipsize(TextUtils.TruncateAt.END);
    bannerText.addView(mBannerTitle);
    mBannerText = text(15, false);
    mBannerText.setMaxLines(2);
    mBannerText.setEllipsize(TextUtils.TruncateAt.END);
    bannerText.addView(mBannerText);
    mBannerAction = new TextView(activity);
    mBannerAction.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
    mBannerAction.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    mBannerAction.setTextColor(0xFFFFFFFF);
    mBannerAction.setGravity(Gravity.CENTER);
    mBannerAction.setPadding(dp(22), 0, dp(22), 0);
    mBannerAction.setVisibility(GONE);
    mBannerAction.setOnClickListener(v -> runBannerAction());
    LayoutParams actionLp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
    actionLp.leftMargin = dp(12);
    mBanner.addView(mBannerAction, actionLp);
    mBanner.setOnClickListener(v -> {
      if (mBannerActionRun != null)
      {
        runBannerAction();
        return;
      }
      mHandler.removeCallbacks(mHideBanner);
      mHideBanner.run();
    });
    mFuel = new ImageView(activity);
    mFuel.setImageResource(R.drawable.ic_moto_fuel);
    mFuel.setPadding(dp(14), dp(14), dp(14), dp(14));
    mFuel.setElevation(dp(6));
    mFuel.setContentDescription("Nearest gas station");
    mFuel.setOnClickListener(v -> searchFuel());
    mStar = new ImageView(activity);
    mStar.setImageResource(R.drawable.ic_moto_star);
    mStar.setPadding(dp(14), dp(14), dp(14), dp(14));
    mStar.setElevation(dp(6));
    mStar.setContentDescription("Saved places");
    mStar.setOnClickListener(v -> showPlaces());
    mSaveDest = new TextView(activity);
    mSaveDest.setText("+ Add stop");
    mSaveDest.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
    mSaveDest.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    mSaveDest.setGravity(Gravity.CENTER);
    mSaveDest.setTextColor(0xFFFFFFFF);
    mSaveDest.setVisibility(GONE);
    mSaveDest.setOnClickListener(v -> mPanels.openAddStop());
    mIdleBox.setOnClickListener(v -> runUnlocked(
        () -> mActivity.startActivity(new Intent(mActivity, MotoTripsActivity.class))));
    mNavBox.addView(bottomRow, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    mNavBox.setVisibility(GONE);
    android.widget.FrameLayout.LayoutParams navLp = new android.widget.FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL);
    mInfoCard.addView(mNavBox, navLp);
    LinearLayout.LayoutParams infoLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
    mSide.addView(mInfoCard, infoLp);

    Typeface condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    mCondensed = condensed;
    mMediaBox = new LinearLayout(activity);
    mMediaBox.setOrientation(VERTICAL);
    mMediaBox.setPadding(dp(16), dp(8), dp(16), dp(8));
    mPager = new MotoPager(activity);

    LinearLayout music = new LinearLayout(activity);
    music.setOrientation(VERTICAL);
    music.setGravity(Gravity.CENTER);
    mArt = new ImageView(activity);
    mArt.setVisibility(GONE);
    mMusicIcon = mArt;
    mTitle = text(24, true);
    mTitle.setTypeface(condensed);
    mTitle.setGravity(Gravity.CENTER);
    mTitle.setSingleLine(true);
    mTitle.setEllipsize(TextUtils.TruncateAt.END);
    mArtist = text(14, true);
    mArtist.setGravity(Gravity.CENTER);
    mArtist.setSingleLine(true);
    mArtist.setEllipsize(TextUtils.TruncateAt.END);
    music.addView(mTitle, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    music.addView(mArtist, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    mTitle.setOnClickListener(v -> {
      if (!mMediaAccess)
        runUnlocked(() -> MotoApps.start(mActivity, new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
    });
    LinearLayout controls = new LinearLayout(activity);
    controls.setOrientation(HORIZONTAL);
    controls.setGravity(Gravity.CENTER);
    controls.setPadding(0, dp(4), 0, 0);
    mPrev = roundButton(R.drawable.ic_moto_prev, 40);
    mPrev.setOnClickListener(v -> mMedia.previous());
    controls.addView(mPrev, new LayoutParams(dp(40), dp(40)));
    mPlay = roundButton(R.drawable.ic_moto_play, 48);
    mPlay.setOnClickListener(v -> mMedia.playPause());
    controls.addView(mPlay, margins(new LayoutParams(dp(48), dp(48)), 18));
    mNext = roundButton(R.drawable.ic_moto_next, 40);
    mNext.setOnClickListener(v -> mMedia.next());
    controls.addView(mNext, margins(new LayoutParams(dp(40), dp(40)), 18));
    music.addView(controls, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    mPager.addPage(music);

    LinearLayout weather = new LinearLayout(activity);
    weather.setOrientation(VERTICAL);
    weather.setGravity(Gravity.CENTER);
    LinearLayout tempRow = new LinearLayout(activity);
    tempRow.setOrientation(HORIZONTAL);
    tempRow.setGravity(Gravity.CENTER);
    mWeatherIcon = text(34, false);
    mWeatherIcon.setPadding(0, 0, dp(8), 0);
    tempRow.addView(mWeatherIcon);
    mWeatherTemp = text(50, true);
    mWeatherTemp.setTypeface(condensed);
    mWeatherTemp.setIncludeFontPadding(false);
    tempRow.addView(mWeatherTemp);
    mWeatherUnit = text(20, true);
    mWeatherUnit.setTypeface(condensed);
    mWeatherUnit.setText("°C");
    mWeatherUnit.setPadding(dp(2), 0, 0, dp(26));
    tempRow.addView(mWeatherUnit);
    weather.addView(tempRow);
    mWeatherDesc = text(15, true);
    mWeatherDesc.setGravity(Gravity.CENTER);
    mWeatherDesc.setSingleLine(true);
    mWeatherDesc.setEllipsize(TextUtils.TruncateAt.END);
    weather.addView(mWeatherDesc, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    mWeatherSub = text(12, false);
    mWeatherSub.setGravity(Gravity.CENTER);
    mWeatherSub.setSingleLine(true);
    mWeatherSub.setEllipsize(TextUtils.TruncateAt.END);
    weather.addView(mWeatherSub, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    mPager.addPage(weather);

    android.widget.ScrollView notif = new android.widget.ScrollView(activity);
    notif.setVerticalScrollBarEnabled(false);
    notif.setOverScrollMode(View.OVER_SCROLL_NEVER);
    mNotifList = new LinearLayout(activity);
    mNotifList.setOrientation(VERTICAL);
    notif.addView(mNotifList, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                         ViewGroup.LayoutParams.WRAP_CONTENT));
    mNotifScroll = notif;
    mPager.addPage(notif);

    mMediaBox.addView(mPager, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(116)));
    mDots = new LinearLayout(activity);
    mDots.setOrientation(HORIZONTAL);
    mDots.setGravity(Gravity.CENTER);
    mDots.setPadding(0, dp(4), 0, 0);
    for (int i = 0; i < 3; i++)
    {
      View dot = new View(activity);
      int page = i;
      dot.setOnClickListener(v -> mPager.setPage(page, true));
      mDots.addView(dot, margins(new LayoutParams(dp(8), dp(8)), i == 0 ? 0 : 8));
    }
    mMediaBox.addView(mDots, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    mPager.setListener(new MotoPager.Listener() {
      @Override
      public void onPageChanged(int page, boolean byUser)
      {
        if (page == 2)
          mNotifScroll.scrollTo(0, 0);
        updateDots();
        touchPager();
      }

      @Override
      public void onInteraction()
      {
        touchPager();
      }
    });
    LinearLayout.LayoutParams mediaLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    mediaLp.topMargin = dp(12);
    mSide.addView(mMediaBox, mediaLp);
    renderWeather(MotoChronos.get(activity).getWeather());
    renderNotifs();

    mDock = new LinearLayout(activity);
    mDock.setOrientation(HORIZONTAL);
    mDock.setGravity(Gravity.CENTER_VERTICAL);
    mDock.setPadding(dp(6), dp(4), dp(6), dp(4));
    addView(mDock, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    View spacer = new View(activity);
    addView(spacer, new LayoutParams(0, 1, 1f));
    addView(clockCol, clockLp);
    addView(mMic, new LayoutParams(dp(46), dp(46)));

    mApps = roundButton(R.drawable.ic_moto_apps, 46);
    mApps.setContentDescription("Apps");
    mApps.setOnClickListener(v -> {
      if (mRiding)
        toast("Apps are locked while riding");
      else
        runUnlocked(this::openDrawer);
    });

    buildDock();

    mSearchBox = new LinearLayout(activity);
    mSearchBox.setOrientation(HORIZONTAL);
    mSearchBox.setGravity(Gravity.CENTER_VERTICAL);
    mSearchBox.setPadding(dp(16), 0, dp(16), 0);
    mSearchBox.setElevation(dp(6));
    mSearchIcon = icon(R.drawable.ic_search);
    mSearchBox.addView(mSearchIcon, new LayoutParams(dp(24), dp(24)));
    mSearchHint = text(16, false);
    mSearchHint.setText("Search places or addresses");
    mSearchHint.setSingleLine(true);
    mSearchHint.setEllipsize(TextUtils.TruncateAt.END);
    mSearchHint.setPadding(dp(12), 0, dp(8), 0);
    mSearchBox.addView(mSearchHint, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    mSearchBox.setOnClickListener(v -> {
      if (mActivity.getMotoPendingEnd() != null)
        mActivity.cancelMotoPending();
      else if (mRiding)
        runUnlocked(this::startVoice);
      else
        mActivity.showSearch("");
    });
    mActivity.setMotoPendingListener(this::updatePending);

    applyColors();
    updateClock();
    updateSpeed();
    updateBattery();
  }

  public static void onRoutePlanChanged()
  {
    if (sBar != null)
      sBar.mHandler.post(() -> {
        sBar.mPanels.refresh();
        sBar.updateFocus();
      });
  }

  public static boolean openSearch(@Nullable String query)
  {
    if (sBar == null)
      return false;
    sBar.mPanels.openSearch(query);
    return true;
  }

  @NonNull
  private MotoPanels.Host panelHost()
  {
    return new MotoPanels.Host() {
      @NonNull
      @Override
      public MotoColors colors()
      {
        return mColors;
      }

      @Override
      public void onPanelModeChanged()
      {
        updateFocus();
      }

      @Override
      public void navigateToSaved(@NonNull MotoPlaces.Place place)
      {
        navigateToPlace(place);
      }

      @Override
      public void saveCurrentLocation()
      {
        MotoBar.this.saveCurrentLocation();
      }

      @Override
      public void savePlace(@NonNull String title, double lat, double lon)
      {
        askNickname("Save place", title, lat, lon);
      }
    };
  }

  private float mStage;
  @Nullable
  private View mPills;
  @Nullable
  private android.animation.ValueAnimator mStageAnim;

  private int sideWidth()
  {
    View row = (View) mMapCard.getParent();
    return row == null ? 0 : row.getWidth() / 3;
  }

  private int normalMapRight()
  {
    View row = (View) mMapCard.getParent();
    if (row == null)
      return mMapCard.getWidth();
    ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) mMapCard.getLayoutParams();
    return row.getWidth() - sideWidth() - mlp.leftMargin;
  }

  private int panelWidth()
  {
    return (int) (mMapCard.getWidth() * 0.4f);
  }

  private void applyStage()
  {
    if (mMapCard == null || mMapCard.getWidth() == 0)
      return;
    int side = sideWidth();
    ViewGroup.LayoutParams slp = mSide.getLayoutParams();
    if (slp.width != side)
    {
      slp.width = side;
      mSide.setLayoutParams(slp);
    }
    int w = panelWidth();
    ViewGroup.LayoutParams plp = mPanels.mRoot.getLayoutParams();
    if (plp != null && plp.width != w)
    {
      plp.width = w;
      mPanels.mRoot.setLayoutParams(plp);
    }
    int hidden = mMapCard.getWidth() - normalMapRight();
    for (View v : new View[] {mListen, mActivity.findViewById(R.id.map_buttons)})
      setMargins(v, 0, hidden);
    // Centred over the part of the map that is actually visible, beside an open side panel.
    setMargins(mLoader, mFocus ? w + dp(12) : 0, mFocus ? 0 : hidden);
    setMargins(mActivity.findViewById(R.id.navigation_frame), mFocus ? w + dp(12) : 0, mFocus ? 0 : hidden);
    drawStage(mStage);
    applyVisibleRect();
  }

  private void setMargins(@Nullable View v, int left, int right)
  {
    if (v == null || !(v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams lp))
      return;
    if (lp.leftMargin != left || lp.rightMargin != right)
    {
      lp.leftMargin = left;
      lp.rightMargin = right;
      v.setLayoutParams(lp);
    }
  }

  private void drawStage(float f)
  {
    mStage = f;
    int full = mMapCard.getWidth();
    int normal = normalMapRight();
    int w = panelWidth();
    float mapRight = normal + (full - normal) * f;
    float panelRight = w * f;
    mMapMask.setHoles(panelRight, dp(12) * f, mapRight);
    mPanels.mRoot.setTranslationX(panelRight - w);
    mSide.setTranslationX(sideWidth() * f);
    mSide.setAlpha(1f - f);
    View buttons = mActivity.findViewById(R.id.map_buttons);
    if (buttons != null)
      buttons.setTranslationX((full - normal) * f);
    if (mPills != null)
    {
      mPills.setAlpha(1f - f);
      mPills.setVisibility(f > 0.02f ? INVISIBLE : VISIBLE);
    }
  }

  private void applyVisibleRect()
  {
    applyVisibleRect(mFocus);
  }

  private void applyVisibleRect(boolean focus)
  {
    int h = mMapCard.getHeight();
    if (h == 0)
      return;
    if (focus)
      app.organicmaps.sdk.Framework.nativeSetVisibleRect(panelWidth() + dp(12), 0, mMapCard.getWidth(), h);
    else
      app.organicmaps.sdk.Framework.nativeSetVisibleRect(0, 0, normalMapRight(), h);
  }

  private void updateFocus()
  {
    RoutingController rc = RoutingController.get();
    boolean want = mPanels.isOpen();
    if (want == mFocus || mMapCard == null)
      return;
    mFocus = want;
    applyStage();
    boolean bar = !want && !rc.isNavigating();
    mSearchBox.setVisibility(bar ? VISIBLE : GONE);
    mFuel.setVisibility(bar ? VISIBLE : GONE);
    mStar.setVisibility(bar ? VISIBLE : GONE);
    if (mStageAnim != null)
      mStageAnim.cancel();
    mSide.setVisibility(VISIBLE);
    applyVisibleRect(want);
    mStageAnim = android.animation.ValueAnimator.ofFloat(mStage, want ? 1f : 0f);
    mStageAnim.setDuration(260L);
    mStageAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
    mStageAnim.addUpdateListener(a -> drawStage((float) a.getAnimatedValue()));
    mStageAnim.addListener(new android.animation.AnimatorListenerAdapter() {
      @Override
      public void onAnimationEnd(android.animation.Animator animation)
      {
        if (mStage >= 1f)
          mSide.setVisibility(INVISIBLE);
      }
    });
    mStageAnim.start();
  }

  private void stylePanels()
  {
    for (int id : new int[] {R.id.search_container_fragment, R.id.routing_container, R.id.place_page_container_fragment})
    {
      View v = mActivity.findViewById(id);
      if (v != null && v.getVisibility() != GONE)
        v.setVisibility(GONE);
    }
  }

  void setInsets(int left, int right, int bottom)
  {
    int pad = Math.max(0, bottom - dp(10));
    setPadding(dp(16) + left, dp(12), dp(12) + right, pad + dp(8));
    setMinimumHeight(dp(84) + pad);
  }

  private void runUnlocked(@NonNull Runnable action)
  {
    KeyguardManager km = mActivity.getSystemService(KeyguardManager.class);
    if (km == null || !km.isKeyguardLocked() || Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
    {
      action.run();
      return;
    }
    km.requestDismissKeyguard(mActivity, new KeyguardManager.KeyguardDismissCallback() {
      @Override
      public void onDismissSucceeded()
      {
        action.run();
      }
    });
  }

  int dp(int value)
  {
    return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
  }

  @NonNull
  private LayoutParams margins(@NonNull LayoutParams lp, int left)
  {
    lp.leftMargin = dp(left);
    return lp;
  }

  @NonNull
  private TextView text(int sp, boolean bold)
  {
    TextView t = new TextView(getContext());
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    t.setTypeface(Typeface.create(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL));
    t.setFontFeatureSettings("tnum");
    t.setIncludeFontPadding(false);
    return t;
  }

  @NonNull
  private ImageView icon(int res)
  {
    ImageView v = new ImageView(getContext());
    v.setImageResource(res);
    return v;
  }

  @NonNull
  private ImageView roundButton(int res, int size)
  {
    ImageView v = icon(res);
    int pad = Math.round(dp(size) * 0.22f);
    v.setPadding(pad, pad, pad, pad);
    v.setScaleType(ImageView.ScaleType.FIT_CENTER);
    return v;
  }

  private void applyColors()
  {
    MotoColors c = mColors;
    setBackgroundColor(c.screen);
    mMapMask.setColor(c.screen);
    mLoader.setBackground(MotoColors.rounded(c.card, dp(28)));
    mLoaderSpinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(c.accent));
    mLoaderText.setTextColor(c.text);
    mLoaderCancel.setTextColor(c.text);
    mLoaderCancel.setBackground(MotoColors.rounded(c.surfaceHigh, dp(20)));
    mDock.setBackground(MotoColors.rounded(c.card, dp(27)));
    mWeatherTemp.setTextColor(c.text);
    mWeatherUnit.setTextColor(c.text);
    mWeatherDesc.setTextColor(c.text);
    mWeatherSub.setTextColor(c.textDim);
    renderNotifs();
    updateDots();
    mClock.setTextColor(c.text);
    mDate.setTextColor(c.textDim);
    mSpeedUnit.setTextColor(c.textDim);
    mTitle.setTextColor(c.text);
    mArtist.setTextColor(c.textDim);
    mMusicIcon.setColorFilter(c.accent);
    mSide.setBackgroundColor(c.screen);
    mInfoCard.setBackground(MotoColors.rounded(c.card, dp(24)));
    mMediaBox.setBackground(MotoColors.rounded(c.card, dp(24)));
    mTurnIcon.setColorFilter(c.accent);
    mDivider.setBackgroundColor(c.surfaceHigh);
    mVDivider.setBackgroundColor(c.surfaceHigh);
    mNavSpeedUnit.setTextColor(c.textDim);
    mEtaTime.setTextColor(c.text);
    mEndNav.setBackground(MotoColors.rounded(c.bad, dp(19)));
    if (mRoot != null)
      mRoot.setBackgroundColor(c.screen);
    mTurnDist.setTextColor(c.text);
    mNextStreet.setTextColor(c.text);
    mEta.setTextColor(c.textDim);
    mPrev.setBackground(MotoColors.oval(c.surfaceHigh));
    mPrev.setColorFilter(c.text);
    mPlay.setBackground(MotoColors.oval(c.accent));
    mPlay.setColorFilter(c.onAccent);
    mNext.setBackground(MotoColors.oval(c.surfaceHigh));
    mNext.setColorFilter(c.text);
    styleDock();
    mApps.setBackground(MotoColors.oval(c.surfaceHigh));
    mApps.setColorFilter(c.accent);
    mMic.setBackground(MotoColors.oval(c.accent));
    mMic.setColorFilter(c.onAccent);
    mSearchBox.setBackground(MotoColors.rounded(c.background, dp(27)));
    mFuel.setBackground(MotoColors.oval(c.background));
    styleBanner();
    mFuel.setColorFilter(c.accent);
    mStar.setBackground(MotoColors.oval(c.background));
    mStar.setColorFilter(0xFFFFB300);
    mSaveDest.setBackground(MotoColors.rounded(0xE6202124, dp(19)));
    updatePending();
    updateBattery();
    updateSpeed();
    updateMedia(null, null);
  }

  @Override
  protected void onConfigurationChanged(Configuration newConfig)
  {
    super.onConfigurationChanged(newConfig);
    mColors = MotoColors.of(getContext());
    applyColors();
  }

  private void updateClock()
  {
    Date now = new Date();
    mClock.setText(new SimpleDateFormat("HH:mm", INDONESIA).format(now));
    mDate.setText(new SimpleDateFormat("EEE d MMM", INDONESIA).format(now));
  }

  private void updateBattery()
  {
    boolean low = mBatteryPercent >= 0 && mBatteryPercent <= 20 && !mPlugged;
    mBatteryIcon.setImageResource(mPlugged ? R.drawable.ic_moto_bolt : R.drawable.ic_moto_battery);
    mBatteryIcon.setColorFilter(mPlugged ? mColors.ok : low ? mColors.bad : mColors.textDim);
    mBatteryText.setTextColor(low ? mColors.bad : mColors.textDim);
    mBatteryText.setText(mBatteryPercent >= 0 ? mBatteryPercent + "%" : "--");
    updatePhoneStatus();
  }

  private void updatePhoneStatus()
  {
    MotoChronos chronos = MotoChronos.get(mActivity);
    boolean connected = chronos.isConnected();
    int level = chronos.getPhoneBattery();
    boolean low = connected && level >= 0 && level <= 20 && !chronos.isPhoneCharging();
    int dim = (mColors.textDim & 0x00FFFFFF) | 0x55000000;
    mPhoneIcon.setColorFilter(!connected ? dim : chronos.isFinding() ? mColors.accent : low ? mColors.bad : mColors.ok);
    mPhoneText.setTextColor(low ? mColors.bad : mColors.textDim);
    mPhoneText.setText(connected && level >= 0 ? level + "%" : "");
    mPhoneText.setVisibility(connected && level >= 0 ? VISIBLE : GONE);
  }

  @Nullable
  private android.widget.FrameLayout mFindModal;

  private void showFindBanner()
  {
    if (mFindModal != null)
      return;
    ViewGroup content = mActivity.findViewById(android.R.id.content);
    if (content == null)
      return;
    android.widget.FrameLayout scrim = new android.widget.FrameLayout(mActivity);
    scrim.setBackgroundColor(0x99000000);
    scrim.setClickable(true);
    LinearLayout card = new LinearLayout(mActivity);
    card.setOrientation(VERTICAL);
    card.setGravity(Gravity.CENTER_HORIZONTAL);
    card.setPadding(dp(32), dp(28), dp(32), dp(24));
    card.setBackground(MotoColors.rounded(mColors.card, dp(28)));
    ImageView icon = new ImageView(mActivity);
    icon.setImageResource(R.drawable.ic_moto_phone);
    icon.setColorFilter(mColors.accent);
    card.addView(icon, new LayoutParams(dp(56), dp(56)));
    TextView title = text(30, true);
    title.setTypeface(mCondensed);
    title.setTextColor(mColors.text);
    title.setText("Ringing your phone");
    title.setPadding(0, dp(12), 0, dp(4));
    card.addView(title);
    TextView body = text(16, false);
    body.setTextColor(mColors.textDim);
    body.setText("It keeps ringing until you find it");
    card.addView(body);
    TextView found = text(20, true);
    found.setText("Found it");
    found.setTextColor(0xFFFFFFFF);
    found.setGravity(Gravity.CENTER);
    found.setBackground(MotoColors.rounded(mColors.accent, dp(30)));
    found.setOnClickListener(v -> stopFindPhone());
    LayoutParams foundLp = new LayoutParams(dp(260), dp(60));
    foundLp.topMargin = dp(22);
    card.addView(found, foundLp);
    android.widget.FrameLayout.LayoutParams cardLp = new android.widget.FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
    scrim.addView(card, cardLp);
    scrim.setAlpha(0f);
    content.addView(scrim, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                      ViewGroup.LayoutParams.MATCH_PARENT));
    scrim.animate().alpha(1f).setDuration(180L).start();
    mFindModal = scrim;
    updatePhoneStatus();
  }

  private void stopFindPhone()
  {
    MotoChronos.get(mActivity).setFindPhone(false);
    android.widget.FrameLayout modal = mFindModal;
    mFindModal = null;
    if (modal != null && modal.getParent() instanceof ViewGroup)
      ((ViewGroup) modal.getParent()).removeView(modal);
    updatePhoneStatus();
  }

  @Override
  public void onChronosPhoneBattery(int level, boolean charging)
  {
    updatePhoneStatus();
    if (level > 25 || charging)
      mPhoneLowWarned = false;
    else if (level <= 20 && !mPhoneLowWarned)
    {
      mPhoneLowWarned = true;
      showBanner("Phone battery", "Your phone is at " + level + "%", "Consider charging it", false, 5000L);
    }
  }

  private void updateNav()
  {
    RoutingController rc = RoutingController.get();
    boolean navigating = rc.isNavigating();
    if (mBarsNavMode == null || mBarsNavMode != navigating)
    {
      mBarsNavMode = navigating;
      hideStatusBar();
    }
    app.organicmaps.sdk.routing.RoutingInfo info = navigating ? rc.getCachedRoutingInfo() : null;
    mSearchBox.setVisibility(navigating || mFocus ? GONE : VISIBLE);
    mFuel.setVisibility(navigating || mFocus ? GONE : VISIBLE);
    mStar.setVisibility(navigating || mFocus ? GONE : VISIBLE);
    mSaveDest.setVisibility(navigating ? VISIBLE : GONE);
    mNavBox.setVisibility(navigating ? VISIBLE : GONE);
    mEndNav.setVisibility(navigating ? VISIBLE : GONE);
    View zoom = mActivity.findViewById(R.id.zoom_buttons_container);
    if (zoom != null && (navigating || mRiding))
      zoom.setVisibility(GONE);
    mIdleBox.setVisibility(navigating ? GONE : VISIBLE);
    if (info == null)
      return;
    if (info.carDirection != null)
      mTurnIcon.setImageResource(info.carDirection.getTurnRes(info.exitNum));
    mTurnDist.setText(info.distToTurn == null ? "" : info.distToTurn.toString(getContext()));
    mNextStreet.setText(info.nextStreet == null ? "" : info.nextStreet);
    int min = Math.max(0, Math.round(info.totalTimeInSeconds / 60f));
    mEtaTime.setText(min >= 60 ? (min / 60) + " h " + (min % 60) + " min" : min + " min");
    String arrive = new SimpleDateFormat("HH:mm", INDONESIA).format(new Date(System.currentTimeMillis() + info.totalTimeInSeconds * 1000L));
    String dist = info.distToTarget == null ? "" : info.distToTarget.toString(getContext()) + " · ";
    mEta.setText(dist + arrive);
  }

  private void updateLoader()
  {
    RoutingController rc = RoutingController.get();
    boolean waitingGps = mActivity.getMotoPendingEnd() != null;
    boolean building = rc.isMotoAutoStartPending() && !rc.isNavigating();
    // The route panel already says it is finding the route; a second indicator would just repeat it.
    boolean panelShowsIt = mPanels != null && mPanels.mode() == MotoPanels.Mode.ROUTE;
    if ((!waitingGps && !building) || panelShowsIt)
    {
      mLoader.setVisibility(GONE);
      return;
    }
    mLoaderText.setText(waitingGps ? "Waiting for GPS…" : "Calculating route…");
    mLoader.setVisibility(VISIBLE);
  }

  private void endNavigation()
  {
    RoutingController.get().cancel();
    mActivity.cancelMotoPending();
    updateNav();
  }

  private void updateSpeed()
  {
    boolean permission = hasLocationPermission();
    boolean fresh = mLocation != null && mFixTime > 0 && SystemClock.elapsedRealtime() - mFixTime < 4000L;
    Location loc = mLocation;
    updateRiding(fresh && loc != null && loc.hasSpeed(), loc != null && loc.hasSpeed() ? loc.getSpeed() * 3.6f : 0f);
    if (fresh && loc != null && loc.hasSpeed())
    {
      mSpeed.setText(String.valueOf(Math.round(loc.getSpeed() * 3.6f)));
      mSpeed.setTextColor(mColors.text);
      mNavSpeed.setText(mSpeed.getText());
      mNavSpeed.setTextColor(mColors.text);
    }
    else
    {
      mSpeed.setText("–");
      mSpeed.setTextColor(mColors.textDim);
      mNavSpeed.setText("–");
      mNavSpeed.setTextColor(mColors.textDim);
    }
    int tint = !permission || !mProviderEnabled ? mColors.bad : fresh ? mColors.ok : mColors.warn;
    mGpsIcon.setColorFilter(tint);
    mGpsText.setTextColor(tint);
    if (!permission)
      mGpsText.setText("GPS · no permission");
    else if (!mProviderEnabled)
      mGpsText.setText("GPS · location off");
    else if (fresh && loc != null)
      mGpsText.setText("GPS · " + mSatsUsed + " sats · ±" + Math.round(loc.getAccuracy()) + " m");
    else
      mGpsText.setText("GPS · searching " + mSatsUsed + "/" + mSatsSeen);
  }

  private void updateMedia(@Nullable String title, @Nullable String artist)
  {
    if (!mMediaAccess)
    {
      mTitle.setText("Media controls");
      mArtist.setText("Tap to allow notification access");
    }
    else if (title != null)
    {
      mTitle.setText(title);
      mArtist.setText(artist == null ? "" : artist);
    }
    else if (mTitle.getText().length() == 0 || "Media controls".contentEquals(mTitle.getText()))
    {
      mTitle.setText("Music");
      mArtist.setText("Not playing");
    }
    mPlay.setImageResource(mPlaying ? R.drawable.ic_moto_pause : R.drawable.ic_moto_play);
  }

  @Override
  public void onMediaChanged(boolean access, @Nullable String title, @Nullable String artist, boolean playing)
  {
    mMediaAccess = access;
    mPlaying = playing;
    if (access && title == null)
    {
      mTitle.setText("Music");
      mArtist.setText("Not playing");
    }
    updateMedia(title, artist);
  }

  @Nullable
  private static Boolean sForcedDark;

  private void syncForcedTheme()
  {
    String forced = mActivity.getSharedPreferences("moto", Context.MODE_PRIVATE).getString("forced_theme", "auto");
    Boolean want = "dark".equals(forced) ? Boolean.TRUE : "light".equals(forced) ? Boolean.FALSE : null;
    if (want == null ? sForcedDark == null : want.equals(sForcedDark))
      return;
    sForcedDark = want;
    if (want != null)
      applyLightTheme(want);
    else
    {
      mLightDark = null;
      mPendingDark = null;
    }
  }

  private void onLux(float lux)
  {
    if (sForcedDark != null)
    {
      if (!sForcedDark.equals(mLightDark))
        applyLightTheme(sForcedDark);
      return;
    }
    Boolean want = lux < DARK_LUX ? Boolean.TRUE : lux > LIGHT_LUX ? Boolean.FALSE : null;
    if (want == null)
    {
      mPendingDark = null;
      return;
    }
    long now = SystemClock.elapsedRealtime();
    if (mLightDark == null)
    {
      applyLightTheme(want);
      return;
    }
    if (want.equals(mLightDark))
    {
      mPendingDark = null;
      return;
    }
    if (!want.equals(mPendingDark))
    {
      mPendingDark = want;
      mPendingSince = now;
      return;
    }
    if (now - mPendingSince >= THEME_DELAY_MS)
      applyLightTheme(want);
  }

  private void applyLightTheme(boolean dark)
  {
    mLightDark = dark;
    mPendingDark = null;
    ThemeSwitcher.INSTANCE.setMotoDark(dark);
    mActivity.syncMotoTheme();
  }

  private void updatePending()
  {
    MapObject end = mActivity.getMotoPendingEnd();
    if (end == null && mRiding)
    {
      mSearchHint.setText("Riding · tap to say a destination");
      mSearchHint.setTextColor(mColors.text);
      mSearchIcon.setImageResource(R.drawable.ic_moto_mic);
      mSearchIcon.setColorFilter(mColors.accent);
    }
    else if (end == null)
    {
      mSearchHint.setText("Search places or addresses");
      mSearchHint.setTextColor(mColors.textDim);
      mSearchIcon.setImageResource(R.drawable.ic_search);
      mSearchIcon.setColorFilter(mColors.textDim);
    }
    else
    {
      mSearchHint.setText("Waiting for GPS → " + end.getName());
      mSearchHint.setTextColor(mColors.text);
      mSearchIcon.setImageResource(R.drawable.ic_moto_gps);
      mSearchIcon.setColorFilter(mColors.warn);
    }
  }

  private boolean hasLocationPermission()
  {
    return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION)
        == PackageManager.PERMISSION_GRANTED;
  }

  @SuppressLint("MissingPermission")
  private void startLocation()
  {
    if (mLocationRunning || !hasLocationPermission() || mLocationManager == null)
      return;
    mProviderEnabled = mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
    mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, mLocationListener,
                                            Looper.getMainLooper());
    mGnssRegistered = mLocationManager.registerGnssStatusCallback(mGnssCallback, mHandler);
    mLocationRunning = true;
  }

  private void stopLocation()
  {
    if (!mLocationRunning || mLocationManager == null)
      return;
    mLocationManager.removeUpdates(mLocationListener);
    if (mGnssRegistered)
      mLocationManager.unregisterGnssStatusCallback(mGnssCallback);
    mGnssRegistered = false;
    mLocationRunning = false;
  }

  @Override
  public void onStart(@NonNull LifecycleOwner owner)
  {
    ContextCompat.registerReceiver(getContext(), mBatteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                                   ContextCompat.RECEIVER_NOT_EXPORTED);
    startLocation();
    if (mSensorManager != null && mLightSensor != null)
      mSensorManager.registerListener(mLightListener, mLightSensor, SensorManager.SENSOR_DELAY_NORMAL);
    mMedia.setListener(this);
    mMedia.start();
    MotoAsr.warmUp(mActivity);
    MotoChronos chronos = MotoChronos.get(mActivity);
    chronos.start();
    chronos.setListener(this);
    mHandler.post(mTicker);
    mHandler.post(mLoaderPoll);
  }

  @Override
  public void onPause(@NonNull LifecycleOwner owner)
  {
    MotoCallRedirect.setTrigger(null);
  }

  @Override
  public void onResume(@NonNull LifecycleOwner owner)
  {
    hideStatusBar();
    syncForcedTheme();
    MotoCallRedirect.install(mActivity);
    if (MotoChronos.get(mActivity).isFinding())
      showFindBanner();
    MotoCallRedirect.setTrigger(() -> runUnlocked(this::startVoice));
    if (MotoCallRedirect.consumePendingVoice())
      mHandler.postDelayed(() -> runUnlocked(this::startVoice), 400L);
    if (!MotoApps.dock(mActivity).equals(mDockItems))
      buildDock();
    if (!sSetupReminded)
    {
      sSetupReminded = true;
      mHandler.postDelayed(this::remindSetup, 4000L);
    }
  }

  private void remindSetup()
  {
    if (mRiding || RoutingController.get().isNavigating() || mActivity.isFinishing())
      return;
    int left = MotoSetup.missingRequired(mActivity);
    if (left <= 0)
      return;
    showBanner("Moto Nav", "Finish setting up", left + (left == 1 ? " step" : " steps") + " left in Settings", false,
               10_000L, "Open", () -> mActivity.startActivity(new Intent(mActivity, MotoSettingsActivity.class)));
  }

  private void autoRecenter()
  {
    long now = SystemClock.elapsedRealtime();
    if (now - mLastRecenter < 10_000L || now - mLastMapTouch < 10_000L)
      return;
    mLastRecenter = now;
    RoutingController rc = RoutingController.get();
    if (rc.isNavigating() || rc.isPlanning() || !mActivity.hasWindowFocus())
      return;
    if (mSearchBox.getVisibility() != VISIBLE || mActivity.getMotoPendingEnd() != null)
      return;
    if (app.organicmaps.sdk.location.LocationState.getMode() == app.organicmaps.sdk.location.LocationState.NOT_FOLLOW)
      app.organicmaps.sdk.location.LocationState.nativeSwitchToNextMode();
  }

  private void enforceBars()
  {
    if (!mActivity.hasWindowFocus())
      return;
    WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(mActivity.getWindow().getDecorView());
    if (insets == null)
      return;
    if (insets.isVisible(WindowInsetsCompat.Type.statusBars())
        || insets.isVisible(WindowInsetsCompat.Type.navigationBars()))
      hideStatusBar();
  }

  private void hideStatusBar()
  {
    WindowInsetsControllerCompat c =
        WindowCompat.getInsetsController(mActivity.getWindow(), mActivity.getWindow().getDecorView());
    c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    c.hide(WindowInsetsCompat.Type.systemBars());
  }

  @Override
  public void onDestroy(@NonNull LifecycleOwner owner)
  {
    try
    {
      mActivity.unregisterReceiver(mScreenReceiver);
    }
    catch (IllegalArgumentException ignored)
    {
    }
    // onStop keeps GPS running while plugged in, so a recreated activity must release it here.
    stopLocation();
    mHandler.removeCallbacksAndMessages(null);
    if (sBar == this)
      sBar = null;
  }

  @Override
  public void onStop(@NonNull LifecycleOwner owner)
  {
    mHandler.removeCallbacks(mTicker);
    mHandler.removeCallbacks(mLoaderPoll);
    getContext().unregisterReceiver(mBatteryReceiver);
    if (!mPlugged)
    {
      stopLocation();
      MotoTrips.get(mActivity).tick();
    }
    if (mSensorManager != null)
      mSensorManager.unregisterListener(mLightListener);
    mMedia.stop();
    MotoChronos.get(mActivity).setListener(null);
  }

  private void buildDock()
  {
    mDock.removeAllViews();
    mShortcutViews.clear();
    mActionViews.clear();
    List<String> items = MotoApps.dock(mActivity);
    mDockItems = items;
    for (int i = 0; i < items.size(); i++)
    {
      String item = items.get(i);
      int slot = i;
      ImageView v = new ImageView(mActivity);
      if (MotoApps.isAction(item))
      {
        v.setImageResource(R.drawable.ic_moto_bluetooth);
        v.setPadding(dp(11), dp(11), dp(11), dp(11));
        v.setOnClickListener(x -> runUnlocked(() -> MotoApps.start(mActivity, new Intent(item))));
        mActionViews.add(v);
      }
      else
      {
        Drawable d = MotoApps.icon(mActivity, item);
        if (d != null)
          v.setImageDrawable(d);
        v.setPadding(dp(4), dp(4), dp(4), dp(4));
        v.setOnClickListener(x -> runUnlocked(() -> MotoApps.launchPackage(mActivity, item)));
        mShortcutViews.add(v);
      }
      v.setContentDescription(MotoApps.label(mActivity, item));
      v.setOnLongClickListener(x -> {
        showDockMenu(x, slot);
        return true;
      });
      mDock.addView(v, margins(new LayoutParams(dp(46), dp(46)), mDock.getChildCount() == 0 ? 0 : 12));
    }
    if (items.isEmpty())
    {
      ImageView add = new ImageView(mActivity);
      add.setImageResource(R.drawable.ic_moto_apps);
      add.setPadding(dp(12), dp(12), dp(12), dp(12));
      add.setContentDescription("Add shortcut");
      add.setOnClickListener(x -> openPicker(0));
      mActionViews.add(add);
      mDock.addView(add, new LayoutParams(dp(46), dp(46)));
    }
    if (mApps.getParent() instanceof ViewGroup)
      ((ViewGroup) mApps.getParent()).removeView(mApps);
    mDock.addView(mApps, 0, new LayoutParams(dp(46), dp(46)));
    if (mDock.getChildCount() > 1)
    {
      LayoutParams firstLp = (LayoutParams) mDock.getChildAt(1).getLayoutParams();
      firstLp.leftMargin = dp(12);
      mDock.getChildAt(1).setLayoutParams(firstLp);
    }
    mDock.setOnLongClickListener(x -> {
      showDockMenu(x, -1);
      return true;
    });
    if (mColors != null)
      styleDock();
  }

  private void styleDock()
  {
    for (ImageView v : mShortcutViews)
      v.setBackground(null);
    for (ImageView v : mActionViews)
    {
      v.setBackground(MotoColors.oval(0xFF1A73E8));
      v.setColorFilter(0xFFFFFFFF);
    }
  }

  private void showDockMenu(@NonNull View anchor, int slot)
  {
    android.widget.PopupMenu menu = new android.widget.PopupMenu(mActivity, anchor, Gravity.TOP);
    int size = mDockItems.size();
    if (slot >= 0)
    {
      menu.getMenu().add(0, 1, 0, "Replace");
      if (slot > 0)
        menu.getMenu().add(0, 2, 1, "Move left");
      if (slot < size - 1)
        menu.getMenu().add(0, 3, 2, "Move right");
      menu.getMenu().add(0, 4, 3, "Remove");
    }
    if (size < MotoApps.DOCK_MAX)
      menu.getMenu().add(0, 5, 4, "Add shortcut");
    menu.getMenu().add(0, 6, 5, "Reset to default");
    menu.setOnMenuItemClickListener(item -> {
      List<String> items = new ArrayList<>(mDockItems);
      switch (item.getItemId())
      {
      case 1 -> openPicker(slot);
      case 2 -> {
        java.util.Collections.swap(items, slot, slot - 1);
        MotoApps.saveDock(mActivity, items);
      }
      case 3 -> {
        java.util.Collections.swap(items, slot, slot + 1);
        MotoApps.saveDock(mActivity, items);
      }
      case 4 -> {
        items.remove(slot);
        MotoApps.saveDock(mActivity, items);
      }
      case 5 -> openPicker(size);
      case 6 -> MotoApps.resetDock(mActivity);
      default -> {}
      }
      buildDock();
      return true;
    });
    menu.show();
  }

  private void checkHeat()
  {
    long now = SystemClock.elapsedRealtime();
    if (now - mLastHeatCheck < 10_000L)
      return;
    mLastHeatCheck = now;
    float battery = MotoThermal.batteryTemp(mActivity);
    float cpu = MotoThermal.cpuTemp();
    boolean hot = battery >= MotoThermal.BATTERY_HOT || cpu >= MotoThermal.CPU_HOT;
    boolean cool = battery < MotoThermal.BATTERY_HOT - MotoThermal.COOL_MARGIN
                   && cpu < MotoThermal.CPU_HOT - MotoThermal.COOL_MARGIN;
    if (cool)
    {
      mHeatDismissed = false;
      mHeat.setVisibility(GONE);
      return;
    }
    if (!hot || mHeatDismissed)
      return;
    StringBuilder sb = new StringBuilder("Phone is hot \u00B7 Battery ");
    sb.append(String.format(Locale.ENGLISH, "%.0f\u00B0C", battery));
    if (cpu > 0)
      sb.append(String.format(Locale.ENGLISH, " \u00B7 CPU %.0f\u00B0C", cpu));
    mHeatText.setText(sb);
    if (mHeat.getVisibility() != VISIBLE)
      Log.i("MotoHeat", sb.toString());
    mHeat.setVisibility(VISIBLE);
  }

  private void navigateToPlace(@NonNull MotoPlaces.Place p)
  {
    toast("Navigating to " + p.nickname);
    mActivity.startLocationToPoint(app.organicmaps.sdk.bookmarks.data.MapObject.createMapObject(
        app.organicmaps.sdk.bookmarks.data.MapObject.SEARCH, p.title.isEmpty() ? p.nickname : p.title, p.nickname,
        p.lat, p.lon));
  }

  private void showPlaces()
  {
    mPanels.openSaved();
  }

  public static boolean handleBack()
  {
    return sBar != null && sBar.mPanels.back();
  }

  private void saveCurrentLocation()
  {
    android.location.Location loc = mLocation != null ? mLocation
        : app.organicmaps.MwmApplication.from(mActivity).getLocationHelper().getSavedLocation();
    if (loc == null)
    {
      toast("Waiting for GPS");
      return;
    }
    askNickname("Save this location", "", loc.getLatitude(), loc.getLongitude());
  }

  private void askNickname(@NonNull String dialogTitle, @NonNull String placeTitle, double lat, double lon)
  {
    LinearLayout box = new LinearLayout(mActivity);
    box.setOrientation(VERTICAL);
    box.setPadding(dp(24), dp(8), dp(24), 0);
    android.widget.EditText input = new android.widget.EditText(mActivity);
    input.setSingleLine(true);
    input.setHint("Name you will say, e.g. office");
    input.setText(placeTitle);
    input.setSelectAllOnFocus(true);
    box.addView(input);
    LinearLayout presets = new LinearLayout(mActivity);
    presets.setOrientation(HORIZONTAL);
    presets.setPadding(0, dp(8), 0, 0);
    for (String preset : new String[] {"home", "office", "gym", "school"})
    {
      TextView chip = text(14, true);
      chip.setText(preset);
      chip.setTextColor(mColors.text);
      chip.setPadding(dp(14), dp(8), dp(14), dp(8));
      chip.setBackground(MotoColors.rounded(mColors.surfaceHigh, dp(16)));
      chip.setOnClickListener(v -> input.setText(preset));
      LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      lp.rightMargin = dp(8);
      presets.addView(chip, lp);
    }
    box.addView(presets);
    new android.app.AlertDialog.Builder(mActivity)
        .setTitle(dialogTitle)
        .setMessage("Say \"navigate to <name>\" to go there later.")
        .setView(box)
        .setPositiveButton("Save", (d, w) -> {
          String nick = input.getText().toString().trim();
          if (nick.isEmpty())
            return;
          MotoPlaces.add(mActivity, new MotoPlaces.Place(nick, placeTitle, lat, lon));
          toast("Saved \"" + nick + "\"");
        })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void showWakeSplash()
  {
    ViewGroup content = mActivity.findViewById(android.R.id.content);
    if (content == null || mActivity.isFinishing())
      return;
    if (mWakeSplash != null)
      return;
    MotoSplashView splash = new MotoSplashView(mActivity);
    splash.setClickable(true);
    splash.setElevation(dp(40));
    content.addView(splash, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                       ViewGroup.LayoutParams.MATCH_PARENT));
    mWakeSplash = splash;
    splash.start(2000L);
    mHandler.postDelayed(() -> splash.animate().alpha(0f).setDuration(350L).withEndAction(() -> {
      content.removeView(splash);
      if (mWakeSplash == splash)
        mWakeSplash = null;
    }).start(), 2000L);
  }

  private void touchPager()
  {
    mHandler.removeCallbacks(mPagerReturn);
    if (mPager.getPage() != 0)
      mHandler.postDelayed(mPagerReturn, PAGER_RETURN_MS);
  }

  private void updateDots()
  {
    if (mColors == null)
      return;
    for (int i = 0; i < mDots.getChildCount(); i++)
    {
      boolean on = i == mPager.getPage();
      mDots.getChildAt(i).setBackground(MotoColors.oval(on ? mColors.text : (mColors.text & 0x00FFFFFF) | 0x40000000));
    }
  }

  private void addNotif(@NonNull String app, @NonNull String title, @NonNull String message)
  {
    String time = new SimpleDateFormat("HH:mm", INDONESIA).format(new Date());
    mNotifs.add(0, new String[] {app, title, message, time});
    while (mNotifs.size() > 30)
      mNotifs.remove(mNotifs.size() - 1);
    renderNotifs();
    mNotifScroll.scrollTo(0, 0);
  }

  private void renderNotifs()
  {
    if (mColors == null)
      return;
    mNotifList.removeAllViews();
    if (mNotifs.isEmpty())
    {
      boolean connected = MotoChronos.get(mActivity).isConnected();
      TextView title = text(24, true);
      title.setTypeface(mCondensed);
      title.setTextColor(mColors.text);
      title.setText(connected ? "All caught up" : "No phone");
      title.setPadding(0, dp(18), 0, 0);
      mNotifList.addView(title);
      TextView body = text(14, false);
      body.setTextColor(mColors.textDim);
      body.setText(connected ? "New notifications from your phone appear here" : "Connect your phone with Chronos");
      mNotifList.addView(body);
      return;
    }
    for (int i = 0; i < mNotifs.size(); i++)
    {
      String[] n = mNotifs.get(i);
      LinearLayout row = new LinearLayout(mActivity);
      row.setOrientation(VERTICAL);
      row.setPadding(0, dp(i == 0 ? 2 : 8), 0, dp(8));
      TextView app = text(11, true);
      app.setTextColor(mColors.accent);
      app.setText(n[0].toUpperCase(Locale.ENGLISH) + " · " + n[3]);
      row.addView(app);
      TextView title = text(19, true);
      title.setTypeface(mCondensed);
      title.setTextColor(mColors.text);
      title.setSingleLine(true);
      title.setEllipsize(TextUtils.TruncateAt.END);
      title.setText(n[1]);
      row.addView(title);
      if (!n[2].isEmpty())
      {
        TextView msg = text(14, false);
        msg.setTextColor(mColors.textDim);
        msg.setMaxLines(2);
        msg.setEllipsize(TextUtils.TruncateAt.END);
        msg.setText(n[2]);
        row.addView(msg);
      }
      mNotifList.addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
      if (i < mNotifs.size() - 1)
      {
        View div = new View(mActivity);
        div.setBackgroundColor(mColors.surfaceHigh);
        mNotifList.addView(div, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
      }
    }
  }

  private static final String[] WEATHER_EMOJI = {"\u26C5", "\u2600\uFE0F", "\u2744\uFE0F", "\uD83C\uDF27\uFE0F",
                                                 "\u2601\uFE0F", "\uD83C\uDF2A\uFE0F", "\uD83D\uDCA8", "\uD83C\uDF2B\uFE0F"};
  private static final String[] WEATHER_NAME = {"Partly cloudy", "Sunny", "Snow", "Rain", "Cloudy", "Storm", "Windy", "Haze"};

  private void renderWeather(@NonNull MotoChronos.Weather w)
  {
    if (!w.hasData())
    {
      mWeatherIcon.setText("");
      mWeatherTemp.setText("--");
      mWeatherUnit.setVisibility(GONE);
      mWeatherDesc.setText("No weather yet");
      mWeatherSub.setText("Connect your phone with Chronos");
      return;
    }
    int icon = Math.max(0, Math.min(7, w.icon));
    mWeatherIcon.setText(WEATHER_EMOJI[icon]);
    mWeatherTemp.setText(String.valueOf(w.temp));
    mWeatherUnit.setVisibility(VISIBLE);
    mWeatherDesc.setText(w.city.isEmpty() ? WEATHER_NAME[icon] : WEATHER_NAME[icon] + " · " + w.city);
    StringBuilder sub = new StringBuilder();
    if (w.high != Integer.MIN_VALUE && w.low != Integer.MIN_VALUE)
      sub.append("H ").append(w.high).append("° · L ").append(w.low).append("°");
    String rain = rainHint(w);
    if (!rain.isEmpty())
      sub.append(sub.length() > 0 ? " · " : "").append(rain);
    mWeatherSub.setText(sub.toString());
  }

  @NonNull
  private static String rainHint(@NonNull MotoChronos.Weather w)
  {
    int now = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
    boolean raining = w.icon == 3 || w.icon == 5;
    for (int h = now + 1; h < Math.min(24, now + 7); h++)
    {
      int ic = w.hourlyIcon[h];
      if (ic < 0)
        continue;
      boolean wet = ic == 3 || ic == 5;
      if (raining && !wet)
        return String.format(Locale.ENGLISH, "rain until %02d:00", h);
      if (!raining && wet)
        return String.format(Locale.ENGLISH, "rain around %02d:00", h);
    }
    return raining ? "rain for a while" : "";
  }

  @Override
  public void onChronosWeather(@NonNull MotoChronos.Weather weather)
  {
    renderWeather(weather);
    checkRainWarning(weather);
  }

  private void checkRainWarning(@NonNull MotoChronos.Weather w)
  {
    if (!w.hasData() || w.icon == 3 || w.icon == 5)
      return;
    java.util.Calendar cal = java.util.Calendar.getInstance();
    int now = cal.get(java.util.Calendar.HOUR_OF_DAY);
    for (int h = now + 1; h <= Math.min(23, now + 1); h++)
    {
      int ic = w.hourlyIcon[h];
      if (ic != 3 && ic != 5)
        continue;
      String key = cal.get(java.util.Calendar.DAY_OF_YEAR) + ":" + h;
      if (key.equals(mRainWarnedKey))
        return;
      mRainWarnedKey = key;
      showBanner("Weather", String.format(Locale.ENGLISH, "Rain expected around %02d:00", h),
                 w.city.isEmpty() ? "Get your rain gear ready" : w.city + " · get your rain gear ready", false, 5000L);
      return;
    }
  }

  private void runBannerAction()
  {
    Runnable run = mBannerActionRun;
    mBannerActionRun = null;
    mHandler.removeCallbacks(mHideBanner);
    mHideBanner.run();
    if (run != null)
      run.run();
  }

  private void showBanner(@NonNull String app, @NonNull String title, @NonNull String message, boolean call,
                          long durationMs)
  {
    showBanner(app, title, message, call, durationMs, null, null);
  }

  private void showBanner(@NonNull String app, @NonNull String title, @NonNull String message, boolean call,
                          long durationMs, @Nullable String action, @Nullable Runnable onAction)
  {
    mBannerActionRun = onAction;
    mBannerAction.setText(action == null ? "" : action);
    mBannerAction.setVisibility(action == null ? GONE : VISIBLE);
    mBannerCall = call;
    mBannerApp.setText(app.toUpperCase(Locale.ENGLISH));
    mBannerTitle.setText(title);
    mBannerText.setText(message);
    mBannerText.setVisibility(message.isEmpty() ? GONE : VISIBLE);
    styleBanner();
    mHandler.removeCallbacks(mHideBanner);
    mBanner.animate().cancel();
    if (mBanner.getVisibility() != VISIBLE)
    {
      mBanner.setAlpha(0f);
      mBanner.setTranslationY(-dp(40));
      mBanner.setVisibility(VISIBLE);
    }
    mBanner.animate().alpha(1f).translationY(0f).setDuration(220L).start();
    mHandler.postDelayed(mHideBanner, durationMs);
  }

  private void styleBanner()
  {
    if (mColors == null)
      return;
    int bg = mBannerCall ? 0xFF0F9D58 : mColors.card;
    int fg = mBannerCall ? 0xFFFFFFFF : mColors.text;
    mBanner.setBackground(MotoColors.rounded(bg, dp(20)));
    mBannerApp.setTextColor(mBannerCall ? 0xFFFFFFFF : mColors.accent);
    mBannerTitle.setTextColor(fg);
    mBannerText.setTextColor(mBannerCall ? 0xE6FFFFFF : mColors.textDim);
    mBannerAction.setBackground(MotoColors.rounded(mColors.accent, dp(24)));
  }

  @Override
  public void onChronosNotification(@NonNull String app, @NonNull String title, @NonNull String message)
  {
    boolean sameAsApp = title.equalsIgnoreCase(app) || title.isEmpty();
    addNotif(app, sameAsApp ? message : title, sameAsApp ? "" : message);
    if (mBannerCall)
      return;
    if (sameAsApp)
      showBanner(app, message, "", false, 5000L);
    else
      showBanner(app, title, message, false, 5000L);
  }

  @Override
  public void onChronosCall(@Nullable String caller)
  {
    if (caller == null)
    {
      if (mBannerCall)
      {
        mHandler.removeCallbacks(mHideBanner);
        mHideBanner.run();
      }
      return;
    }
    addNotif("Call", caller, "Incoming call");
    showBanner("Incoming call", caller, "", true, 30000L);
  }

  @Override
  public void onChronosConnection(boolean connected)
  {
    if (mWasChronosConnected != null && mWasChronosConnected != connected)
      toast(connected ? "Phone connected (Chronos)" : "Phone disconnected");
    mWasChronosConnected = connected;
    updatePhoneStatus();
    if (!connected)
      mNotifs.clear();
    renderNotifs();
  }

  @Nullable
  private Boolean mWasChronosConnected;

  private void openPicker(int slot)
  {
    runUnlocked(() -> mActivity.startActivity(
        new Intent(mActivity, MotoAppsActivity.class).putExtra(MotoAppsActivity.EXTRA_PICK_SLOT, slot)));
  }

  private void openDrawer()
  {
    mActivity.startActivity(new Intent(mActivity, MotoAppsActivity.class));
  }

  private void startVoice()
  {
    if (MotoAsr.selected(mActivity) == null)
    {
      if (!startFutoVoice())
        toast("Download a voice input model in Settings");
      return;
    }
    if (ContextCompat.checkSelfPermission(mActivity, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED)
    {
      androidx.core.app.ActivityCompat.requestPermissions(mActivity, new String[] {Manifest.permission.RECORD_AUDIO},
                                                          7310);
      toast("Allow the microphone, then tap the mic again");
      return;
    }
    if (MotoAsr.isListening())
    {
      MotoAsr.cancel();
      return;
    }
    MotoNameMatcher.cancelActive();
    showListen("Starting…", "");
    MotoAsr.listen(mActivity, new MotoAsr.Callback() {
      @Override
      public void onListening()
      {
        showListen("Listening", "Say a destination or a command in English");
      }

      @Override
      public void onLevel(float level)
      {
        float s = 1f + level * 0.85f;
        mListenRing.setScaleX(s);
        mListenRing.setScaleY(s);
      }

      @Override
      public void onProcessing()
      {
        showListen("Thinking…", "");
      }

      @Override
      public void onResult(@Nullable String text)
      {
        if (text == null)
        {
          hideListen();
          return;
        }
        mListenSub.setText("\u201C" + text + "\u201D");
        mHandler.postDelayed(MotoBar.this::hideListen, 900L);
        handleVoice(java.util.Collections.singletonList(text));
      }

      @Override
      public void onError(@NonNull String message)
      {
        hideListen();
        toast(message);
      }
    });
  }

  private void showListen(@NonNull String title, @NonNull String sub)
  {
    mListenTitle.setText(title);
    mListenSub.setText(sub);
    if (mListen.getVisibility() != VISIBLE)
    {
      mListen.setAlpha(0f);
      mListen.setVisibility(VISIBLE);
      mListen.animate().alpha(1f).setDuration(150L).start();
    }
  }

  private void hideListen()
  {
    mListen.animate().alpha(0f).setDuration(150L).withEndAction(() -> mListen.setVisibility(GONE)).start();
  }

  private boolean startFutoVoice()
  {
    Intent base = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                      .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                      .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                      .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                      .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a command");
    Intent futo = new Intent(base).setPackage(FUTO_PACKAGE);
    PackageManager pm = mActivity.getPackageManager();
    Intent intent = futo.resolveActivity(pm) != null ? futo : base.resolveActivity(pm) != null ? base : null;
    if (intent == null)
      return false;
    try
    {
      mVoiceLauncher.launch(intent);
      return true;
    }
    catch (RuntimeException e)
    {
      return false;
    }
  }

  private void handleVoice(@NonNull List<String> heard)
  {
    MotoVoice.Command cmd = MotoVoice.parse(heard);
    if (cmd == null)
    {
      toast("\"" + heard.get(0) + "\" not recognized");
      return;
    }
    switch (cmd.kind)
    {
    case SEARCH ->
    {
      MotoPlaces.Place saved = MotoPlaces.match(mActivity, cmd.argument);
      if (saved != null)
        navigateToPlace(saved);
      else if (MotoVoice.isFuel(cmd.argument))
      {
        mActivity.showSearch("Gas station ", "en", false);
        toast("Searching gas stations");
      }
      else
      {
        String q = MotoVoice.cleanQuery(cmd.argument);
        toast("Looking for \"" + q + "\"");
        MotoNameMatcher.match(mActivity, q, name -> {
          String target = name != null ? name : q;
          mActivity.showSearch(target);
          if (name != null && !MotoNameMatcher.clean(name).equals(MotoNameMatcher.clean(q)))
            toast("Found \"" + name + "\"");
        });
      }
    }
    case OPEN_APP ->
    {
      MotoApps.Entry e = MotoVoice.matchApp(MotoApps.load(mActivity), cmd.argument);
      if (e != null && MotoApps.launch(mActivity, e.component))
        toast("Opening " + e.label);
      else
        toast("App \"" + cmd.argument + "\" not found");
    }
    case PLAY ->
    {
      mMedia.play();
      toast("Play");
    }
    case PAUSE ->
    {
      mMedia.pause();
      toast("Pause");
    }
    case NEXT ->
    {
      mMedia.next();
      toast("Next track");
    }
    case PREVIOUS ->
    {
      mMedia.previous();
      toast("Previous track");
    }
    case DRAWER -> openDrawer();
    }
  }

  private void toast(@NonNull String message)
  {
    Toast.makeText(mActivity, message, Toast.LENGTH_SHORT).show();
  }
}
