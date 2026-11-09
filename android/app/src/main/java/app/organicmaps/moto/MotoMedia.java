package app.organicmaps.moto;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class MotoMedia
{
  public interface Listener
  {
    void onMediaChanged(boolean access, @Nullable String title, @Nullable String artist, boolean playing);
  }

  private final Context mContext;
  private final MediaSessionManager mSessions;
  private final AudioManager mAudio;
  private final ComponentName mComponent;
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  @Nullable
  private MediaController mController;
  @Nullable
  private Listener mListener;
  private boolean mListening;
  private boolean mAccess;

  private final MediaSessionManager.OnActiveSessionsChangedListener mSessionsListener = this::pick;

  private final MediaController.Callback mCallback = new MediaController.Callback() {
    @Override
    public void onMetadataChanged(@Nullable MediaMetadata metadata)
    {
      publish();
    }

    @Override
    public void onPlaybackStateChanged(@Nullable PlaybackState state)
    {
      publish();
    }

    @Override
    public void onSessionDestroyed()
    {
      refresh();
    }
  };

  public MotoMedia(@NonNull Context context)
  {
    mContext = context.getApplicationContext();
    mSessions = mContext.getSystemService(MediaSessionManager.class);
    mAudio = mContext.getSystemService(AudioManager.class);
    mComponent = new ComponentName(mContext, MotoMediaListener.class);
  }

  public void setListener(@Nullable Listener listener)
  {
    mListener = listener;
    publish();
  }

  public boolean hasAccess()
  {
    return NotificationManagerCompat.getEnabledListenerPackages(mContext).contains(mContext.getPackageName());
  }

  public void start()
  {
    mAccess = hasAccess();
    if (!mAccess)
    {
      publish();
      return;
    }
    if (!mListening)
    {
      try
      {
        mSessions.addOnActiveSessionsChangedListener(mSessionsListener, mComponent, mHandler);
        mListening = true;
      }
      catch (SecurityException e)
      {
        mAccess = false;
        publish();
        return;
      }
    }
    refresh();
  }

  public void stop()
  {
    if (mListening)
    {
      mSessions.removeOnActiveSessionsChangedListener(mSessionsListener);
      mListening = false;
    }
    if (mController != null)
      mController.unregisterCallback(mCallback);
    mController = null;
  }

  private void refresh()
  {
    List<MediaController> list;
    try
    {
      list = mSessions.getActiveSessions(mComponent);
    }
    catch (SecurityException e)
    {
      list = Collections.emptyList();
    }
    pick(list);
  }

  private void pick(@Nullable List<MediaController> list)
  {
    MediaController chosen = null;
    if (list != null)
    {
      for (MediaController c : list)
      {
        PlaybackState s = c.getPlaybackState();
        if (s != null && s.getState() == PlaybackState.STATE_PLAYING)
        {
          chosen = c;
          break;
        }
      }
      if (chosen == null && !list.isEmpty())
        chosen = list.get(0);
    }
    MediaController current = mController;
    boolean same = current != null && chosen != null
                   && Objects.equals(current.getSessionToken(), chosen.getSessionToken());
    if (!same)
    {
      if (current != null)
        current.unregisterCallback(mCallback);
      mController = chosen;
      if (chosen != null)
        chosen.registerCallback(mCallback, mHandler);
    }
    publish();
  }

  private void publish()
  {
    Listener listener = mListener;
    if (listener == null)
      return;
    MediaController c = mController;
    if (c == null)
    {
      listener.onMediaChanged(mAccess, null, null, false);
      return;
    }
    MediaMetadata md = c.getMetadata();
    String title = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE);
    if (title == null && md != null)
      title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
    String artist = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_ARTIST);
    if (artist == null && md != null)
      artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
    PlaybackState s = c.getPlaybackState();
    boolean playing = s != null
                      && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING);
    if (title == null || title.isEmpty())
    {
      listener.onMediaChanged(mAccess, null, null, playing);
      return;
    }
    if (artist == null || artist.isEmpty())
      artist = appLabel(c.getPackageName());
    listener.onMediaChanged(mAccess, title, artist, playing);
  }

  @NonNull
  private String appLabel(@NonNull String packageName)
  {
    PackageManager pm = mContext.getPackageManager();
    try
    {
      return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
    }
    catch (PackageManager.NameNotFoundException e)
    {
      return packageName;
    }
  }

  private void sendKey(int code)
  {
    long time = SystemClock.uptimeMillis();
    mAudio.dispatchMediaKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0));
    mAudio.dispatchMediaKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0));
  }

  public void playPause()
  {
    MediaController c = mController;
    if (c == null)
    {
      sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
      return;
    }
    PlaybackState s = c.getPlaybackState();
    if (s != null && s.getState() == PlaybackState.STATE_PLAYING)
      c.getTransportControls().pause();
    else
      c.getTransportControls().play();
  }

  public void play()
  {
    if (mController != null)
      mController.getTransportControls().play();
    else
      sendKey(KeyEvent.KEYCODE_MEDIA_PLAY);
  }

  public void pause()
  {
    if (mController != null)
      mController.getTransportControls().pause();
    else
      sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
  }

  public void next()
  {
    if (mController != null)
      mController.getTransportControls().skipToNext();
    else
      sendKey(KeyEvent.KEYCODE_MEDIA_NEXT);
  }

  public void previous()
  {
    if (mController != null)
      mController.getTransportControls().skipToPrevious();
    else
      sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
  }
}
