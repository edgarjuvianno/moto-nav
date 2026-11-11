package app.organicmaps.moto;

import android.content.Context;
import android.graphics.Typeface;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.MwmActivity;
import app.organicmaps.MwmApplication;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.routing.RouteMarkData;
import app.organicmaps.sdk.routing.RouteMarkType;
import app.organicmaps.sdk.routing.RoutingController;
import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.search.SearchEngine;
import app.organicmaps.sdk.search.SearchListener;
import app.organicmaps.sdk.search.SearchResult;
import app.organicmaps.sdk.util.Language;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class MotoPanels implements SearchListener
{
  enum Mode
  {
    NONE,
    SEARCH,
    SAVED,
    PLACE,
    ROUTE
  }

  interface Host
  {
    @NonNull
    MotoColors colors();

    void onPanelModeChanged();

    void navigateToSaved(@NonNull MotoPlaces.Place place);

    void saveCurrentLocation();

    void savePlace(@NonNull String title, double lat, double lon);
  }

  private static final String[][] CATEGORIES = {{"Fuel", "Gas station "}, {"Food", "Restaurant "},
                                                {"Parking", "Parking "}, {"ATM", "ATM "},
                                                {"Mosque", "Mosque "},   {"Hospital", "Hospital "},
                                                {"Pharmacy", "Pharmacy "}};
  private static final int MAX_RESULTS = 40;
  private static final int TITLE_SP = 20;
  private static final int BODY_SP = 16;
  private static final int SMALL_SP = 13;
  private static final int ROW_DP = 44;
  private static final int BUTTON_DP = 52;

  private final MwmActivity mActivity;
  private final Host mHost;
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  final FrameLayout mRoot;

  private Mode mMode = Mode.NONE;
  private Mode mExplicit = Mode.NONE;
  @Nullable
  private MapObject mPlace;
  private boolean mPickStop;
  private boolean mFromSearch;
  private boolean mLocalPlace;
  private boolean mResumeNav;

  @Nullable
  private EditText mQuery;
  @Nullable
  private LinearLayout mResults;
  @Nullable
  private TextView mStatus;
  private boolean mCategory;
  private long mSearchStamp;
  private boolean mListening = true;
  @Nullable
  private SearchResult[] mLastResults;
  private final Runnable mRunSearch = this::runSearch;

  MotoPanels(@NonNull MwmActivity activity, @NonNull Host host)
  {
    mActivity = activity;
    mHost = host;
    mRoot = new FrameLayout(activity);
    mRoot.setClickable(true);
    mRoot.setVisibility(View.GONE);
  }

  @NonNull
  Mode mode()
  {
    return mMode;
  }

  boolean isOpen()
  {
    return mMode != Mode.NONE;
  }

  void openSearch(@Nullable String query)
  {
    mExplicit = Mode.SEARCH;
    mPickStop = false;
    String q = query == null ? "" : query;
    sync();
    setQuery(q, !q.trim().isEmpty() && q.endsWith(" "));
  }

  void openAddStop()
  {
    RoutingController rc = RoutingController.get();
    if (rc.getEndPoint() == null || Framework.nativeIsRoutePointsLimitReached())
      return;
    mPickStop = true;
    mExplicit = Mode.SEARCH;
    sync();
    setQuery("", false);
  }

  void openSaved()
  {
    mExplicit = Mode.SAVED;
    sync();
  }

  void setPlace(@Nullable MapObject place)
  {
    if (mLocalPlace && place == null)
      return;
    mLocalPlace = false;
    mPlace = place;
    if (place == null)
      mFromSearch = false;
    sync();
  }

  void refresh()
  {
    RoutingController rc = RoutingController.get();
    if (mResumeNav && !rc.isPlanning())
      mResumeNav = false;
    sync();
    if (mMode == Mode.ROUTE)
      render();
  }

  boolean back()
  {
    switch (mMode)
    {
    case PLACE ->
    {
      closePlace();
      return true;
    }
    case SEARCH, SAVED ->
    {
      closeExplicit();
      return true;
    }
    default ->
    {
      return false;
    }
    }
  }

  void close()
  {
    if (mPlace != null)
      closePlace();
    closeExplicit();
  }

  private void closePlace()
  {
    if (mLocalPlace)
    {
      mLocalPlace = false;
      mPlace = null;
      sync();
    }
    else
      mActivity.closePlacePage();
  }

  private void closeExplicit()
  {
    boolean wasSearch = mExplicit == Mode.SEARCH;
    mExplicit = Mode.NONE;
    mPickStop = false;
    mFromSearch = false;
    if (wasSearch)
      stopSearch();
    sync();
  }

  private void sync()
  {
    RoutingController rc = RoutingController.get();
    Mode next;
    if (mPlace != null)
      next = Mode.PLACE;
    else if (mExplicit != Mode.NONE)
      next = mExplicit;
    else if (rc.isPlanning() && !rc.isNavigating())
      next = Mode.ROUTE;
    else
      next = Mode.NONE;
    if (next == mMode)
      return;
    Mode prev = mMode;
    mMode = next;
    if (prev == Mode.SEARCH && next != Mode.SEARCH)
      hideKeyboard();
    render();
    mHost.onPanelModeChanged();
  }

  private void render()
  {
    mRoot.removeAllViews();
    MotoColors c = mHost.colors();
    mRoot.setBackgroundColor(c.card);
    if (mMode == Mode.NONE)
    {
      mRoot.setVisibility(View.GONE);
      return;
    }
    mRoot.setVisibility(View.VISIBLE);
    View content = switch (mMode)
    {
      case SEARCH -> buildSearch();
      case SAVED -> buildSaved();
      case PLACE -> buildPlace();
      case ROUTE -> buildRoute();
      default -> new View(mActivity);
    };
    mRoot.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                        ViewGroup.LayoutParams.MATCH_PARENT));
  }

  @NonNull
  private LinearLayout column()
  {
    LinearLayout col = new LinearLayout(mActivity);
    col.setOrientation(LinearLayout.VERTICAL);
    col.setPadding(dp(18), dp(14), dp(14), dp(14));
    return col;
  }

  @NonNull
  private LinearLayout header(@NonNull String title, @Nullable String sub, @NonNull Runnable onClose)
  {
    MotoColors c = mHost.colors();
    LinearLayout head = new LinearLayout(mActivity);
    head.setOrientation(LinearLayout.HORIZONTAL);
    head.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout texts = new LinearLayout(mActivity);
    texts.setOrientation(LinearLayout.VERTICAL);
    TextView t = text(TITLE_SP, true, c.text);
    t.setText(title);
    t.setMaxLines(2);
    t.setEllipsize(TextUtils.TruncateAt.END);
    texts.addView(t);
    if (sub != null && !sub.isEmpty())
    {
      TextView s = text(SMALL_SP + 1, false, c.textDim);
      s.setText(sub);
      s.setSingleLine(true);
      s.setEllipsize(TextUtils.TruncateAt.END);
      LinearLayout.LayoutParams lp =
          new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      lp.topMargin = dp(3);
      texts.addView(s, lp);
    }
    head.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(dp(40), dp(40));
    xlp.leftMargin = dp(8);
    head.addView(closeButton(onClose), xlp);
    return head;
  }

  @NonNull
  private TextView closeButton(@NonNull Runnable onClose)
  {
    TextView x = text(17, false, mHost.colors().text);
    x.setText("✕");
    x.setGravity(Gravity.CENTER);
    x.setBackground(MotoColors.rounded(mHost.colors().surfaceHigh, dp(20)));
    x.setOnClickListener(v -> onClose.run());
    return x;
  }

  @NonNull
  private View buildSearch()
  {
    MotoColors c = mHost.colors();
    LinearLayout col = column();
    LinearLayout head = new LinearLayout(mActivity);
    head.setOrientation(LinearLayout.HORIZONTAL);
    head.setGravity(Gravity.CENTER_VERTICAL);
    if (mQuery == null)
    {
      mQuery = new EditText(mActivity);
      mQuery.setSingleLine(true);
      mQuery.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
      mQuery.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                           | EditorInfo.IME_FLAG_NO_FULLSCREEN);
      mQuery.setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY_SP);
      mQuery.setPadding(dp(16), 0, dp(16), 0);
      mQuery.addTextChangedListener(new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after)
        {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count)
        {}

        @Override
        public void afterTextChanged(Editable s)
        {
          if (mListening)
          {
            mCategory = false;
            scheduleSearch();
          }
        }
      });
      mQuery.setOnEditorActionListener((v, actionId, event) -> {
        if (actionId == EditorInfo.IME_ACTION_SEARCH
            || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER))
        {
          hideKeyboard();
          return true;
        }
        return false;
      });
    }
    mQuery.setHint(mPickStop ? "Add a stop on the way" : "Search places or addresses");
    mQuery.setTextColor(c.text);
    mQuery.setHintTextColor(c.textDim);
    mQuery.setBackground(MotoColors.rounded(c.surfaceHigh, dp(22)));
    if (mQuery.getParent() instanceof ViewGroup parent)
      parent.removeView(mQuery);
    head.addView(mQuery, new LinearLayout.LayoutParams(0, dp(44), 1f));
    LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(dp(40), dp(40));
    xlp.leftMargin = dp(8);
    head.addView(closeButton(this::closeExplicit), xlp);
    col.addView(head);

    HorizontalScrollView chipScroll = new HorizontalScrollView(mActivity);
    chipScroll.setHorizontalScrollBarEnabled(false);
    LinearLayout chips = new LinearLayout(mActivity);
    chips.setOrientation(LinearLayout.HORIZONTAL);
    for (String[] cat : CATEGORIES)
    {
      TextView chip = text(SMALL_SP + 1, false, c.text);
      chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      chip.setText(cat[0]);
      chip.setGravity(Gravity.CENTER);
      chip.setPadding(dp(10), 0, dp(14), 0);
      android.graphics.drawable.GradientDrawable chipBg = new android.graphics.drawable.GradientDrawable();
      chipBg.setCornerRadius(dp(16));
      chipBg.setColor(c.card);
      chipBg.setStroke(Math.max(1, dp(1)), (c.textDim & 0x00FFFFFF) | 0x55000000);
      chip.setBackground(chipBg);
      int[] style = categoryStyle(cat[0]);
      android.graphics.drawable.Drawable icon = tinted(style[0], style[1]);
      if (icon != null)
      {
        icon.setBounds(0, 0, dp(22), dp(22));
        chip.setCompoundDrawables(icon, null, null, null);
        chip.setCompoundDrawablePadding(dp(6));
      }
      chip.setOnClickListener(v -> setQuery(cat[1], true));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
      lp.rightMargin = dp(6);
      chips.addView(chip, lp);
    }
    chipScroll.addView(chips);
    LinearLayout.LayoutParams clp =
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    clp.topMargin = dp(10);
    col.addView(chipScroll, clp);

    mStatus = text(SMALL_SP + 1, false, c.textDim);
    mStatus.setPadding(dp(4), 0, 0, 0);
    mStatus.setVisibility(View.GONE);
    LinearLayout.LayoutParams slp =
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    slp.topMargin = dp(10);
    col.addView(mStatus, slp);

    ScrollView scroll = new ScrollView(mActivity);
    mResults = new LinearLayout(mActivity);
    mResults.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(mResults, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                        ViewGroup.LayoutParams.WRAP_CONTENT));
    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
    rlp.topMargin = dp(10);
    col.addView(scroll, rlp);
    renderResults();
    return col;
  }

  private void setQuery(@NonNull String query, boolean category)
  {
    if (mQuery == null)
      render();
    if (mQuery == null)
      return;
    mListening = false;
    mQuery.setText(category ? query.trim() : query);
    mQuery.setSelection(mQuery.getText().length());
    mListening = true;
    mCategory = category;
    if (query.trim().isEmpty())
    {
      mLastResults = null;
      SearchEngine.INSTANCE.cancel();
      renderResults();
      mQuery.requestFocus();
      mQuery.post(this::showKeyboard);
    }
    else
    {
      hideKeyboard();
      mHandler.removeCallbacks(mRunSearch);
      runSearch();
    }
  }

  private void scheduleSearch()
  {
    mHandler.removeCallbacks(mRunSearch);
    mHandler.postDelayed(mRunSearch, 350L);
  }

  private void runSearch()
  {
    if (mQuery == null || mMode != Mode.SEARCH)
      return;
    String text = mQuery.getText().toString();
    if (text.trim().isEmpty())
    {
      mLastResults = null;
      SearchEngine.INSTANCE.cancel();
      renderResults();
      return;
    }
    String query = mCategory ? text.trim() + " " : text;
    SearchEngine.INSTANCE.cancel();
    SearchEngine.INSTANCE.removeListener(this);
    SearchEngine.INSTANCE.addListener(this);
    Location loc = MwmApplication.from(mActivity).getLocationHelper().getSavedLocation();
    mSearchStamp = System.nanoTime();
    SearchEngine.INSTANCE.setQuery(query);
    boolean started = SearchEngine.INSTANCE.searchInteractive(
        query, mCategory, mCategory ? "en" : Language.getKeyboardLocale(mActivity), mSearchStamp, true, loc != null,
        loc != null ? loc.getLatitude() : 0, loc != null ? loc.getLongitude() : 0);
    setStatus(started ? "Searching…" : "Search unavailable");
  }

  private void setStatus(@NonNull String status)
  {
    if (mStatus == null)
      return;
    mStatus.setText(status);
    mStatus.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
  }

  private void stopSearch()
  {
    mHandler.removeCallbacks(mRunSearch);
    SearchEngine.INSTANCE.removeListener(this);
    SearchEngine.INSTANCE.cancel();
    mLastResults = null;
    mListening = false;
    if (mQuery != null)
      mQuery.setText("");
    mListening = true;
  }

  @Override
  public void onResultsUpdate(@NonNull SearchResult[] results, long timestamp)
  {
    if (timestamp != mSearchStamp)
      return;
    mLastResults = results;
    renderResults();
  }

  @Override
  public void onResultsEnd(long timestamp)
  {
    if (timestamp != mSearchStamp)
      return;
    setStatus(mLastResults == null || mLastResults.length == 0 ? "No places found" : "");
  }

  private void renderResults()
  {
    if (mResults == null)
      return;
    mResults.removeAllViews();
    String text = mQuery == null ? "" : mQuery.getText().toString().trim();
    if (text.isEmpty())
    {
      setStatus(mPickStop ? "Search a place to stop at on the way" : "");
      return;
    }
    if (mLastResults == null)
      return;
    setStatus("");
    int n = 0;
    for (int i = 0; i < mLastResults.length && n < MAX_RESULTS; i++)
    {
      SearchResult r = mLastResults[i];
      if (r.type != SearchResult.TYPE_RESULT)
        continue;
      n++;
      final int index = i;
      String type = r.description == null ? "" : r.description.localizedFeatureType;
      String region = r.description == null ? "" : r.description.region;
      String dist = r.description == null || r.description.distance == null || !r.description.distance.isValid()
                      ? ""
                      : r.description.distance.toString(mActivity);
      String title = r.name == null || r.name.isEmpty() ? type : r.name;
      mResults.addView(resultRow(title, type, region, dist, n == 1, mCategory && n == 1, () -> onResult(r, index)));
    }
  }

  private void onResult(@NonNull SearchResult r, int index)
  {
    hideKeyboard();
    String type = r.description == null ? "" : r.description.localizedFeatureType;
    MapObject obj = MapObject.createMapObject(MapObject.SEARCH, r.name == null ? "" : r.name, type, r.lat, r.lon);
    if (mPickStop)
    {
      boolean navigating = RoutingController.get().isNavigating();
      RoutingController.get().addStop(obj);
      closeExplicit();
      mResumeNav = navigating;
      sync();
      return;
    }
    mFromSearch = true;
    SearchEngine.INSTANCE.selectResult(index);
    mHandler.postDelayed(() -> {
      if (mPlace == null && mMode == Mode.SEARCH && mFromSearch)
      {
        Framework.nativeSetViewportCenter(r.lat, r.lon, 16);
        setPlace(obj);
      }
    }, 700L);
  }

  @NonNull
  private View buildSaved()
  {
    LinearLayout col = column();
    List<MotoPlaces.Place> places = MotoPlaces.load(mActivity);
    col.addView(header(places.isEmpty() ? "No saved places yet" : "Saved places", null, this::closeExplicit));
    ScrollView scroll = new ScrollView(mActivity);
    LinearLayout list = new LinearLayout(mActivity);
    list.setOrientation(LinearLayout.VERTICAL);
    Location loc = MwmApplication.from(mActivity).getLocationHelper().getSavedLocation();
    for (MotoPlaces.Place p : places)
    {
      String sub = p.title.isEmpty() || p.title.equalsIgnoreCase(p.nickname) ? "" : p.title;
      list.addView(row(p.nickname, sub, distanceTo(loc, p.lat, p.lon), () -> {
        closeExplicit();
        mHost.navigateToSaved(p);
      }), rowParams());
    }
    list.addView(row("+  Save my current location", "", "", () -> {
      closeExplicit();
      mHost.saveCurrentLocation();
    }), rowParams());
    scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                    ViewGroup.LayoutParams.WRAP_CONTENT));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
    lp.topMargin = dp(12);
    col.addView(scroll, lp);
    return col;
  }

  @NonNull
  private View buildPlace()
  {
    MotoColors c = mHost.colors();
    MapObject p = mPlace;
    LinearLayout col = column();
    if (p == null)
      return col;
    String title = p.getTitle();
    String type = p.getSubtitle();
    if (title == null || title.isEmpty())
      title = type == null || type.isEmpty() ? "Selected place" : type;
    col.addView(header(title, type != null && !type.equals(title) ? type : null, this::closePlace));

    ScrollView scroll = new ScrollView(mActivity);
    LinearLayout body = new LinearLayout(mActivity);
    body.setOrientation(LinearLayout.VERTICAL);
    Location loc = MwmApplication.from(mActivity).getLocationHelper().getSavedLocation();
    String dist = distanceTo(loc, p.getLat(), p.getLon());
    if (!dist.isEmpty())
    {
      TextView d = text(TITLE_SP + 2, true, c.accent);
      d.setText(dist);
      body.addView(d);
    }
    String address = p.getAddress();
    if (address != null && !address.isEmpty())
    {
      TextView a = text(BODY_SP - 1, false, c.text);
      a.setText(address);
      LinearLayout.LayoutParams lp =
          new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      lp.topMargin = dp(6);
      body.addView(a, lp);
    }
    scroll.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                    ViewGroup.LayoutParams.WRAP_CONTENT));
    LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
    slp.topMargin = dp(12);
    col.addView(scroll, slp);

    RoutingController rc = RoutingController.get();
    boolean navigating = rc.isNavigating();
    boolean planning = rc.isPlanning() || navigating;
    final String placeTitle = title;
    LinearLayout buttons = new LinearLayout(mActivity);
    buttons.setOrientation(LinearLayout.HORIZONTAL);
    if (!navigating)
    {
      TextView save = button("☆ Save", false);
      save.setOnClickListener(v -> mHost.savePlace(placeTitle, p.getLat(), p.getLon()));
      buttons.addView(save, new LinearLayout.LayoutParams(0, dp(BUTTON_DP), 1f));
    }
    TextView go = button(planning ? "Add stop" : "Start", true);
    go.setOnClickListener(v -> {
      mFromSearch = false;
      mExplicit = Mode.NONE;
      mPickStop = false;
      stopSearch();
      if (planning)
      {
        mResumeNav = navigating;
        rc.addStop(p);
        closePlace();
      }
      else
        mActivity.startLocationToPoint(p);
    });
    LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(0, dp(BUTTON_DP), 2f);
    if (!navigating)
      glp.leftMargin = dp(8);
    buttons.addView(go, glp);
    LinearLayout.LayoutParams blp =
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    blp.topMargin = dp(10);
    col.addView(buttons, blp);
    return col;
  }

  @NonNull
  private View buildRoute()
  {
    MotoColors c = mHost.colors();
    RoutingController rc = RoutingController.get();
    LinearLayout col = column();

    String big;
    String small;
    RoutingInfo info = rc.isBuilt() ? rc.getCachedRoutingInfo() : null;
    if (info != null)
    {
      big = formatDuration(info.totalTimeInSeconds) + "  ·  " + info.distToTarget.toString(mActivity);
      small = "Arrive " + new SimpleDateFormat("HH:mm", Locale.getDefault())
                              .format(new Date(System.currentTimeMillis() + info.totalTimeInSeconds * 1000L));
    }
    else if (rc.isBuilding())
    {
      big = "Finding route…";
      small = null;
    }
    else
    {
      big = "No route";
      small = "Try another destination";
    }
    col.addView(header(big, small, rc::cancel));

    LinearLayout card = new LinearLayout(mActivity);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dp(14), dp(2), dp(4), dp(2));
    card.setBackground(MotoColors.rounded(c.surfaceHigh, dp(16)));
    RouteMarkData[] points = Framework.nativeGetRoutePoints();
    if (points != null)
    {
      for (RouteMarkData m : points)
      {
        String label;
        if (m.mPointType == RouteMarkType.Start)
          label = "From";
        else if (m.mPointType == RouteMarkType.Finish)
          label = "To";
        else
          label = "Stop";
        String name = m.mIsMyPosition ? "My position" : m.mTitle == null || m.mTitle.isEmpty() ? "Point" : m.mTitle;
        Runnable remove = null;
        if (m.mPointType == RouteMarkType.Intermediate)
        {
          final int idx = m.mIntermediateIndex;
          remove = () -> {
            Framework.nativeRemoveRoutePoint(RouteMarkType.Intermediate, idx);
            RoutingController.get().checkAndBuildRoute();
            render();
          };
        }
        card.addView(stopRow(label, name, remove, null),
                     new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP)));
      }
    }
    if (rc.getStartPoint() != null && rc.getEndPoint() != null && !Framework.nativeIsRoutePointsLimitReached())
      card.addView(stopRow("+", "Add stop", null, () -> {
        mPickStop = true;
        mExplicit = Mode.SEARCH;
        sync();
        setQuery("", false);
      }), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP)));
    ScrollView scroll = new ScrollView(mActivity);
    scroll.addView(card, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                    ViewGroup.LayoutParams.WRAP_CONTENT));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
    lp.topMargin = dp(12);
    col.addView(scroll, lp);

    TextView start = button(mResumeNav ? "Continue" : "Start", true);
    start.setEnabled(rc.isBuilt());
    start.setAlpha(rc.isBuilt() ? 1f : 0.4f);
    start.setOnClickListener(v -> {
      if (RoutingController.get().isBuilt())
      {
        mResumeNav = false;
        RoutingController.get().start();
      }
    });
    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(BUTTON_DP));
    blp.topMargin = dp(10);
    col.addView(start, blp);
    return col;
  }

  @NonNull
  private View stopRow(@NonNull String label, @NonNull String name, @Nullable Runnable remove,
                       @Nullable Runnable onClick)
  {
    MotoColors c = mHost.colors();
    LinearLayout row = new LinearLayout(mActivity);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    TextView l = text(SMALL_SP - 1, true, onClick != null ? c.accent : c.textDim);
    l.setText(label.toUpperCase(Locale.ROOT));
    row.addView(l, new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));
    TextView n = text(BODY_SP, true, onClick != null ? c.accent : c.text);
    n.setText(name);
    n.setSingleLine(true);
    n.setEllipsize(TextUtils.TruncateAt.END);
    row.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    if (remove != null)
    {
      TextView x = text(BODY_SP, false, c.textDim);
      x.setText("✕");
      x.setGravity(Gravity.CENTER);
      x.setOnClickListener(v -> remove.run());
      row.addView(x, new LinearLayout.LayoutParams(dp(40), dp(40)));
    }
    if (onClick != null)
      row.setOnClickListener(v -> onClick.run());
    return row;
  }

  @NonNull
  private View row(@NonNull String title, @NonNull String sub, @NonNull String right, @NonNull Runnable action)
  {
    MotoColors c = mHost.colors();
    LinearLayout row = new LinearLayout(mActivity);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(14), dp(9), dp(14), dp(9));
    row.setBackground(MotoColors.rounded(c.surfaceHigh, dp(14)));
    LinearLayout texts = new LinearLayout(mActivity);
    texts.setOrientation(LinearLayout.VERTICAL);
    TextView t = text(BODY_SP, true, c.text);
    t.setText(title);
    t.setSingleLine(true);
    t.setEllipsize(TextUtils.TruncateAt.END);
    texts.addView(t);
    if (!sub.isEmpty())
    {
      TextView s = text(SMALL_SP, false, c.textDim);
      s.setText(sub);
      s.setSingleLine(true);
      s.setEllipsize(TextUtils.TruncateAt.END);
      LinearLayout.LayoutParams lp =
          new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      lp.topMargin = dp(2);
      texts.addView(s, lp);
    }
    row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    if (!right.isEmpty())
    {
      TextView r = text(SMALL_SP + 1, true, c.accent);
      r.setText(right);
      r.setPadding(dp(10), 0, 0, 0);
      row.addView(r);
    }
    row.setOnClickListener(v -> action.run());
    return row;
  }

  @NonNull
  private View resultRow(@NonNull String title, @Nullable String type, @Nullable String region, @NonNull String dist,
                         boolean first, boolean nearest, @NonNull Runnable action)
  {
    MotoColors c = mHost.colors();
    LinearLayout wrap = new LinearLayout(mActivity);
    wrap.setOrientation(LinearLayout.VERTICAL);
    if (!first && !nearest)
    {
      View line = new View(mActivity);
      line.setBackgroundColor((c.textDim & 0x00FFFFFF) | 0x26000000);
      LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
      llp.leftMargin = dp(48);
      wrap.addView(line, llp);
    }
    LinearLayout row = new LinearLayout(mActivity);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(2), dp(12), dp(4), dp(12));
    android.util.TypedValue tv = new android.util.TypedValue();
    if (nearest)
    {
      row.setPadding(dp(10), dp(12), dp(12), dp(12));
      row.setBackground(MotoColors.rounded((c.accent & 0x00FFFFFF) | 0x1A000000, dp(16)));
    }
    else if (mActivity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true))
      row.setBackgroundResource(tv.resourceId);

    int[] style = categoryStyle(type == null ? "" : type);
    android.widget.ImageView icon = new android.widget.ImageView(mActivity);
    android.graphics.drawable.Drawable d = tinted(style[0], style[1]);
    if (d != null)
      icon.setImageDrawable(d);
    row.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));

    LinearLayout texts = new LinearLayout(mActivity);
    texts.setOrientation(LinearLayout.VERTICAL);
    TextView t = text(BODY_SP, false, c.text);
    t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    t.setText(title);
    t.setSingleLine(true);
    t.setEllipsize(TextUtils.TruncateAt.END);
    texts.addView(t);
    String sub = join(type, region);
    if (!sub.isEmpty())
    {
      TextView s = text(SMALL_SP, false, c.textDim);
      s.setText(sub);
      s.setSingleLine(true);
      s.setEllipsize(TextUtils.TruncateAt.END);
      LinearLayout.LayoutParams lp =
          new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      lp.topMargin = dp(3);
      texts.addView(s, lp);
    }
    LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    tlp.leftMargin = dp(14);
    row.addView(texts, tlp);

    if (!dist.isEmpty() || nearest)
    {
      LinearLayout right = new LinearLayout(mActivity);
      right.setOrientation(LinearLayout.VERTICAL);
      right.setGravity(Gravity.END);
      if (nearest)
      {
        TextView tag = text(SMALL_SP - 2, true, c.accent);
        tag.setText("Nearest");
        right.addView(tag);
      }
      if (!dist.isEmpty())
      {
        TextView r = text(SMALL_SP, nearest, nearest ? c.accent : c.textDim);
        r.setText(dist);
        r.setSingleLine(true);
        LinearLayout.LayoutParams dlp =
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (nearest)
          dlp.topMargin = dp(3);
        right.addView(r, dlp);
      }
      LinearLayout.LayoutParams rlp =
          new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      rlp.leftMargin = dp(10);
      row.addView(right, rlp);
    }
    row.setOnClickListener(v -> action.run());
    wrap.addView(row);
    if (nearest)
    {
      TextView others = text(SMALL_SP - 1, true, c.textDim);
      others.setText("MORE NEARBY");
      LinearLayout.LayoutParams olp =
          new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      olp.topMargin = dp(14);
      olp.bottomMargin = dp(2);
      olp.leftMargin = dp(4);
      wrap.addView(others, olp);
    }
    return wrap;
  }

  @NonNull
  private static int[] categoryStyle(@NonNull String type)
  {
    String t = type.toLowerCase(Locale.ROOT);
    if (t.contains("fuel") || t.contains("petrol") || t.contains("gas"))
      return new int[] {app.organicmaps.R.drawable.ic_category_fuel, 0xFFF57C00};
    if (t.contains("restaurant") || t.contains("food") || t.contains("cafe") || t.contains("bakery")
        || t.contains("fast"))
      return new int[] {app.organicmaps.R.drawable.ic_category_eat, 0xFFE53935};
    if (t.contains("parking"))
      return new int[] {app.organicmaps.R.drawable.ic_category_parking, 0xFF1E88E5};
    if (t.contains("atm") || t.contains("bank"))
      return new int[] {app.organicmaps.R.drawable.ic_category_atm, 0xFF43A047};
    if (t.contains("hospital") || t.contains("clinic") || t.contains("doctor"))
      return new int[] {app.organicmaps.R.drawable.ic_category_hospital, 0xFFD81B60};
    if (t.contains("pharmacy") || t.contains("chemist"))
      return new int[] {app.organicmaps.R.drawable.ic_category_pharmacy, 0xFF00897B};
    if (t.contains("mosque") || t.contains("worship") || t.contains("church") || t.contains("temple"))
      return new int[] {app.organicmaps.R.drawable.ic_category_tourism, 0xFF8E24AA};
    if (t.contains("hotel") || t.contains("guest") || t.contains("hostel"))
      return new int[] {app.organicmaps.R.drawable.ic_category_hotel, 0xFF5E35B1};
    if (t.contains("shop") || t.contains("mall") || t.contains("market") || t.contains("store"))
      return new int[] {app.organicmaps.R.drawable.ic_category_shopping, 0xFF00ACC1};
    if (t.contains("police"))
      return new int[] {app.organicmaps.R.drawable.ic_category_police, 0xFF3949AB};
    if (t.contains("bus") || t.contains("station") || t.contains("transport"))
      return new int[] {app.organicmaps.R.drawable.ic_category_transport, 0xFF546E7A};
    if (t.contains("toilet"))
      return new int[] {app.organicmaps.R.drawable.ic_category_toilet, 0xFF6D4C41};
    return new int[] {app.organicmaps.R.drawable.ic_category_tourism, 0xFF757575};
  }

  @Nullable
  private android.graphics.drawable.Drawable tinted(int res, int color)
  {
    android.graphics.drawable.Drawable d = androidx.core.content.ContextCompat.getDrawable(mActivity, res);
    if (d == null)
      return null;
    d = d.mutate();
    if (d instanceof android.graphics.drawable.LayerDrawable layers && layers.getNumberOfLayers() > 1)
      layers.getDrawable(0).setTint(color);
    else
      d.setTint(color);
    return d;
  }

  @NonNull
  private LinearLayout.LayoutParams rowParams()
  {
    LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.bottomMargin = dp(6);
    return lp;
  }

  @NonNull
  private TextView button(@NonNull String label, boolean primary)
  {
    MotoColors c = mHost.colors();
    TextView b = text(primary ? 17 : 15, true, primary ? c.onAccent : c.text);
    b.setText(label);
    b.setGravity(Gravity.CENTER);
    b.setBackground(MotoColors.rounded(primary ? c.accent : c.surfaceHigh, dp(BUTTON_DP / 2)));
    return b;
  }

  @NonNull
  private TextView text(int sp, boolean bold, int color)
  {
    TextView t = new TextView(mActivity);
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
    t.setTextColor(color);
    t.setIncludeFontPadding(false);
    return t;
  }

  @NonNull
  private static String join(@Nullable String a, @Nullable String b)
  {
    boolean ha = a != null && !a.isEmpty();
    boolean hb = b != null && !b.isEmpty();
    if (ha && hb)
      return a + "  ·  " + b;
    return ha ? a : hb ? b : "";
  }

  @NonNull
  private static String distanceTo(@Nullable Location loc, double lat, double lon)
  {
    if (loc == null)
      return "";
    float[] out = new float[1];
    Location.distanceBetween(loc.getLatitude(), loc.getLongitude(), lat, lon, out);
    float m = out[0];
    if (m < 1000)
      return Math.round(m / 10f) * 10 + " m";
    if (m < 10000)
      return String.format(Locale.US, "%.1f km", m / 1000f);
    return Math.round(m / 1000f) + " km";
  }

  @NonNull
  private static String formatDuration(int seconds)
  {
    int minutes = Math.max(1, Math.round(seconds / 60f));
    if (minutes < 60)
      return minutes + " min";
    return minutes / 60 + " h " + minutes % 60 + " min";
  }

  private void showKeyboard()
  {
    if (mQuery == null)
      return;
    InputMethodManager imm = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
    if (imm != null)
      imm.showSoftInput(mQuery, InputMethodManager.SHOW_IMPLICIT);
  }

  private void hideKeyboard()
  {
    if (mQuery == null)
      return;
    InputMethodManager imm = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
    if (imm != null)
      imm.hideSoftInputFromWindow(mQuery.getWindowToken(), 0);
    mQuery.clearFocus();
  }

  private int dp(int v)
  {
    return Math.round(v * mActivity.getResources().getDisplayMetrics().density);
  }
}
