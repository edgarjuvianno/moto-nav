package app.organicmaps.moto;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

@SuppressLint("ViewConstructor")
public final class MotoPager extends FrameLayout
{
  public interface Listener
  {
    void onPageChanged(int page, boolean byUser);

    void onInteraction();
  }

  private final int mTouchSlop;
  private int mPage;
  private float mDownX;
  private float mDownY;
  private boolean mDragging;
  @Nullable
  private Listener mListener;

  public MotoPager(@NonNull Context context)
  {
    super(context);
    mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
  }

  public void setListener(@Nullable Listener listener)
  {
    mListener = listener;
  }

  public void addPage(@NonNull View page)
  {
    page.setVisibility(getChildCount() == mPage ? VISIBLE : GONE);
    addView(page, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
  }

  public int getPage()
  {
    return mPage;
  }

  public int getPageCount()
  {
    return getChildCount();
  }

  public void setPage(int page, boolean animate)
  {
    setPage(page, animate, 0, false);
  }

  private void setPage(int page, boolean animate, int direction, boolean byUser)
  {
    int count = getChildCount();
    if (count == 0)
      return;
    int target = ((page % count) + count) % count;
    if (target == mPage)
      return;
    View from = getChildAt(mPage);
    View to = getChildAt(target);
    int dir = direction != 0 ? direction : (target > mPage ? 1 : -1);
    mPage = target;
    if (animate && getWidth() > 0)
    {
      float shift = getWidth() * 0.35f;
      from.animate().cancel();
      to.animate().cancel();
      from.animate().translationX(-dir * shift).alpha(0f).setDuration(180L).withEndAction(() -> {
        from.setVisibility(GONE);
        from.setTranslationX(0f);
        from.setAlpha(1f);
      }).start();
      to.setTranslationX(dir * shift);
      to.setAlpha(0f);
      to.setVisibility(VISIBLE);
      to.animate().translationX(0f).alpha(1f).setDuration(220L).start();
    }
    else
    {
      from.setVisibility(GONE);
      to.setVisibility(VISIBLE);
      to.setTranslationX(0f);
      to.setAlpha(1f);
    }
    if (mListener != null)
      mListener.onPageChanged(mPage, byUser);
  }

  @Override
  public boolean onInterceptTouchEvent(MotionEvent ev)
  {
    switch (ev.getActionMasked())
    {
    case MotionEvent.ACTION_DOWN:
      mDownX = ev.getX();
      mDownY = ev.getY();
      mDragging = false;
      if (mListener != null)
        mListener.onInteraction();
      return false;
    case MotionEvent.ACTION_MOVE:
      float dx = ev.getX() - mDownX;
      float dy = ev.getY() - mDownY;
      if (Math.abs(dx) > mTouchSlop * 2 && Math.abs(dx) > Math.abs(dy) * 1.5f)
      {
        mDragging = true;
        getParent().requestDisallowInterceptTouchEvent(true);
        return true;
      }
      return false;
    default:
      return false;
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  @Override
  public boolean onTouchEvent(MotionEvent ev)
  {
    switch (ev.getActionMasked())
    {
    case MotionEvent.ACTION_DOWN:
      mDownX = ev.getX();
      mDownY = ev.getY();
      mDragging = false;
      if (mListener != null)
        mListener.onInteraction();
      return true;
    case MotionEvent.ACTION_MOVE:
      if (!mDragging && Math.abs(ev.getX() - mDownX) > mTouchSlop * 2)
        mDragging = true;
      return true;
    case MotionEvent.ACTION_UP:
      float dx = ev.getX() - mDownX;
      if (mDragging && Math.abs(dx) > mTouchSlop * 3)
        setPage(mPage + (dx < 0 ? 1 : -1), true, dx < 0 ? 1 : -1, true);
      mDragging = false;
      return true;
    case MotionEvent.ACTION_CANCEL:
      mDragging = false;
      return true;
    default:
      return true;
    }
  }
}
