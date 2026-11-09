package app.organicmaps.moto;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public final class MotoAppsActivity extends AppCompatActivity
{
  public static final String EXTRA_PICK_SLOT = "moto_pick_slot";
  private int mPickSlot = -1;
  private final List<MotoApps.Entry> mApps = new ArrayList<>();
  private MotoColors mColors;
  private RecyclerView.Adapter<Holder> mAdapter;

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
    mPickSlot = getIntent().getIntExtra(EXTRA_PICK_SLOT, -1);

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(mColors.background);

    LinearLayout header = new LinearLayout(this);
    header.setOrientation(LinearLayout.HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(12), dp(10), dp(12), dp(10));
    header.addView(button("Back", v -> finish()));
    if (mPickSlot >= 0)
    {
      header.addView(title("Choose a shortcut"));
      header.addView(button("Bluetooth", v -> pick(MotoApps.BLUETOOTH)));
    }
    else
    {
      header.addView(button("Trips", v -> startActivity(new Intent(this, MotoTripsActivity.class))));
      header.addView(button("Find phone", v -> {
        MotoChronos chronos = MotoChronos.get(this);
        if (!chronos.isConnected())
          android.widget.Toast.makeText(this, "Connect your phone with Chronos first", android.widget.Toast.LENGTH_SHORT).show();
        else
        {
          chronos.setFindPhone(true);
          finish();
        }
      }));
      header.addView(button("Settings", v -> startActivity(new Intent(this, MotoSettingsActivity.class))));
    }
    android.widget.HorizontalScrollView headerScroll = new android.widget.HorizontalScrollView(this);
    headerScroll.setHorizontalScrollBarEnabled(false);
    headerScroll.addView(header);
    root.addView(headerScroll);

    RecyclerView list = new RecyclerView(this);
    int span = Math.max(3, Math.round(getResources().getDisplayMetrics().widthPixels / (float) dp(116)));
    list.setLayoutManager(new GridLayoutManager(this, span));
    list.setPadding(dp(8), 0, dp(8), dp(8));
    list.setClipToPadding(false);
    mAdapter = new RecyclerView.Adapter<>() {
      @NonNull
      @Override
      public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
      {
        return new Holder(cell());
      }

      @Override
      public void onBindViewHolder(@NonNull Holder holder, int position)
      {
        MotoApps.Entry e = mApps.get(position);
        holder.icon.setImageDrawable(e.icon);
        holder.label.setText(e.label);
        holder.itemView.setOnClickListener(v -> {
          if (mPickSlot >= 0)
          {
            pick(e.packageName);
            return;
          }
          MotoApps.launch(MotoAppsActivity.this, e.component);
          finish();
        });
        holder.itemView.setOnLongClickListener(v -> {
          if (mPickSlot >= 0)
            return false;
          showAppMenu(v, e);
          return true;
        });
      }

      @Override
      public int getItemCount()
      {
        return mApps.size();
      }
    };
    list.setAdapter(mAdapter);
    root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

    ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
      Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
      v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
      return WindowInsetsCompat.CONSUMED;
    });
    setContentView(root);

    Executors.newSingleThreadExecutor().execute(() -> {
      List<MotoApps.Entry> loaded = MotoApps.load(getApplicationContext());
      runOnUiThread(() -> {
        mApps.clear();
        mApps.addAll(loaded);
        mAdapter.notifyDataSetChanged();
      });
    });
  }

  private void pick(@NonNull String item)
  {
    MotoApps.setDockSlot(this, mPickSlot, item);
    finish();
  }

  private void showAppMenu(@NonNull View anchor, @NonNull MotoApps.Entry e)
  {
    android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
    boolean inDock = MotoApps.dock(this).contains(e.packageName);
    menu.getMenu().add(0, 1, 0, inDock ? "Already in dock" : "Add to dock").setEnabled(!inDock);
    menu.getMenu().add(0, 2, 1, "App info");
    menu.setOnMenuItemClickListener(item -> {
      if (item.getItemId() == 1)
      {
        boolean ok = MotoApps.addToDock(this, e.packageName);
        android.widget.Toast.makeText(this, ok ? "Added to dock" : "Dock is full (" + MotoApps.DOCK_MAX + ")",
                                      android.widget.Toast.LENGTH_SHORT).show();
      }
      else
        MotoApps.openAppInfo(this, e.packageName);
      return true;
    });
    menu.show();
  }

  @NonNull
  private TextView title(@NonNull String text)
  {
    TextView t = new TextView(this);
    t.setText(text);
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
    t.setTextColor(mColors.text);
    t.setPadding(dp(8), 0, dp(16), 0);
    return t;
  }

  @Override
  protected void onResume()
  {
    super.onResume();
    androidx.core.view.WindowInsetsControllerCompat bars =
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
    bars.setSystemBarsBehavior(androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    bars.hide(WindowInsetsCompat.Type.systemBars());
  }

  @NonNull
  private TextView button(@NonNull String label, @NonNull View.OnClickListener onClick)
  {
    TextView t = new TextView(this);
    t.setText(label);
    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
    t.setTextColor(mColors.text);
    t.setGravity(Gravity.CENTER);
    t.setPadding(dp(16), 0, dp(16), 0);
    t.setBackground(MotoColors.rounded(mColors.surfaceHigh, dp(16)));
    t.setOnClickListener(onClick);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52));
    lp.rightMargin = dp(10);
    t.setLayoutParams(lp);
    return t;
  }

  @NonNull
  private View cell()
  {
    LinearLayout cell = new LinearLayout(this);
    cell.setOrientation(LinearLayout.VERTICAL);
    cell.setGravity(Gravity.CENTER_HORIZONTAL);
    cell.setPadding(dp(6), dp(10), dp(6), dp(10));
    cell.setBackground(MotoColors.rounded(mColors.surfaceHigh, dp(16)));
    RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                 ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.setMargins(dp(4), dp(4), dp(4), dp(4));
    cell.setLayoutParams(lp);
    ImageView icon = new ImageView(this);
    icon.setId(android.R.id.icon);
    cell.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));
    TextView label = new TextView(this);
    label.setId(android.R.id.text1);
    label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
    label.setTextColor(mColors.text);
    label.setGravity(Gravity.CENTER);
    label.setMaxLines(2);
    label.setEllipsize(TextUtils.TruncateAt.END);
    label.setPadding(0, dp(6), 0, 0);
    cell.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                      ViewGroup.LayoutParams.WRAP_CONTENT));
    return cell;
  }

  static final class Holder extends RecyclerView.ViewHolder
  {
    final ImageView icon;
    final TextView label;

    Holder(@NonNull View itemView)
    {
      super(itemView);
      icon = itemView.findViewById(android.R.id.icon);
      label = itemView.findViewById(android.R.id.text1);
    }
  }
}
