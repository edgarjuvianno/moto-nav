package app.organicmaps.moto;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;

/// Shrinks its single child uniformly (text, icons and spacing together) when the child does not fit, so a card
/// keeps all of its content visible on a reduced screen size. Uses a view scale rather than text sizes, so fitting
/// never triggers another layout pass. Needs an exact size from its parent (MATCH_PARENT or a fixed size).
@SuppressLint("ViewConstructor")
final class MotoFit extends FrameLayout
{
  private static final float kMinScale = 0.4f;

  MotoFit(@NonNull Context context, @NonNull View child)
  {
    super(context);
    addView(child, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec)
  {
    int width = MeasureSpec.getSize(widthMeasureSpec);
    int height = MeasureSpec.getSize(heightMeasureSpec);
    setMeasuredDimension(width, height);
    View child = getChildAt(0);
    if (child.getVisibility() == GONE || width == 0 || height == 0)
      return;

    float scale = 1f;
    int contentWidth = width;
    int contentHeight = natural(child, contentWidth);
    while (scale > kMinScale)
    {
      // A row wider than the card (e.g. a long single-line value) is cut at the edge rather than wrapped.
      boolean tooWide = (child.getMeasuredWidthAndState() & MEASURED_STATE_TOO_SMALL) != 0;
      float fit = (float) height / contentHeight;
      if (!tooWide && fit >= scale)
        break;
      scale = Math.max(kMinScale, tooWide ? Math.min(scale * 0.9f, fit) : fit);
      // Laid out wider by 1/scale, wrapped text may need fewer lines, so the height is rechecked.
      contentWidth = (int) (width / scale);
      contentHeight = natural(child, contentWidth);
    }
    child.measure(MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
                  MeasureSpec.makeMeasureSpec(Math.max((int) (height / scale), contentHeight), MeasureSpec.EXACTLY));
    child.setPivotX(0);
    child.setPivotY(0);
    child.setScaleX(scale);
    child.setScaleY(scale);
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom)
  {
    View child = getChildAt(0);
    if (child.getVisibility() == GONE)
      return;
    // Center the scaled content, in case it was capped by the minimum scale or its width.
    int dx = (int) ((right - left - child.getMeasuredWidth() * child.getScaleX()) / 2);
    int dy = (int) ((bottom - top - child.getMeasuredHeight() * child.getScaleY()) / 2);
    child.layout(dx, dy, dx + child.getMeasuredWidth(), dy + child.getMeasuredHeight());
  }

  private static int natural(@NonNull View child, int width)
  {
    child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                  MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    return Math.max(1, child.getMeasuredHeight());
  }
}
