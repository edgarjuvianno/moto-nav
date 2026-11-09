package app.organicmaps.moto;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class MotoTtsService extends TextToSpeechService
{
  private static final String TAG = "MotoTts";
  private static final String ASSET_DIR = "moto-tts";
  private static final int DATA_VERSION = 1;

  @Nullable
  private static OfflineTts sTts;
  @Nullable
  private static String sVoiceId;
  @Nullable
  private static Context sAppContext;

  static void reset()
  {
    synchronized (sLock)
    {
      if (sTts != null)
        sTts.release();
      sTts = null;
      sVoiceId = null;
    }
    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
      if (sAppContext != null)
        new Thread(() -> engine(sAppContext), "MotoTtsInit").start();
      app.organicmaps.sdk.sound.TtsPlayer.State st = app.organicmaps.sdk.sound.TtsPlayer.getState();
      if (st != app.organicmaps.sdk.sound.TtsPlayer.State.INITIALIZING
          && st != app.organicmaps.sdk.sound.TtsPlayer.State.UNAVAILABLE)
        app.organicmaps.sdk.sound.TtsPlayer.INSTANCE.refreshLanguages();
    });
  }

  static boolean hasVoice(@NonNull Context context)
  {
    return MotoVoices.selected(context) != null;
  }
  private static final Object sLock = new Object();
  private volatile boolean mStopped;

  @Nullable
  static OfflineTts engine(@NonNull Context context)
  {
    synchronized (sLock)
    {
      sAppContext = context.getApplicationContext();
      MotoVoices.Voice voice = MotoVoices.selected(context);
      if (voice == null)
        return null;
      if (sTts != null && voice.id.equals(sVoiceId))
        return sTts;
      if (sTts != null)
      {
        sTts.release();
        sTts = null;
      }
      try
      {
        File data = new File(context.getFilesDir(), "moto-tts/espeak-ng-data");
        File stamp = new File(context.getFilesDir(), "moto-tts/version");
        if (!stamp.exists() || !String.valueOf(DATA_VERSION).equals(read(stamp)))
        {
          copyAssets(context.getAssets(), ASSET_DIR + "/espeak-ng-data", data);
          write(stamp, String.valueOf(DATA_VERSION));
        }
        File vdir = MotoVoices.dir(context, voice);
        OfflineTtsConfig config = new OfflineTtsConfig();
        config.model.vits.model = new File(vdir, voice.modelFile()).getAbsolutePath();
        config.model.vits.tokens = new File(vdir, "tokens.txt").getAbsolutePath();
        config.model.vits.dataDir = data.getAbsolutePath();
        config.model.numThreads = 2;
        sTts = new OfflineTts(null, config);
        sVoiceId = voice.id;
        Log.i(TAG, "Engine ready with " + voice.id + ", sample rate " + sTts.sampleRate());
      }
      catch (RuntimeException | IOException e)
      {
        Log.e(TAG, "Engine init failed", e);
        sTts = null;
      }
      return sTts;
    }
  }

  @Override
  public void onCreate()
  {
    super.onCreate();
    new Thread(() -> engine(getApplicationContext()), "MotoTtsInit").start();
  }

  @Override
  protected int onIsLanguageAvailable(String lang, String country, String variant)
  {
    if (lang == null || !(lang.equalsIgnoreCase("eng") || lang.equalsIgnoreCase("en")))
      return TextToSpeech.LANG_NOT_SUPPORTED;
    if (!hasVoice(getApplicationContext()))
      return TextToSpeech.LANG_MISSING_DATA;
    if (country == null || country.isEmpty())
      return TextToSpeech.LANG_AVAILABLE;
    return country.equalsIgnoreCase("USA") || country.equalsIgnoreCase("US") ? TextToSpeech.LANG_COUNTRY_AVAILABLE
                                                                          : TextToSpeech.LANG_AVAILABLE;
  }

  @Override
  protected String[] onGetLanguage()
  {
    return new String[] {"eng", "USA", ""};
  }

  @Override
  protected int onLoadLanguage(String lang, String country, String variant)
  {
    return onIsLanguageAvailable(lang, country, variant);
  }

  @Override
  protected void onStop()
  {
    mStopped = true;
  }

  @Override
  protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback)
  {
    mStopped = false;
    OfflineTts tts = engine(getApplicationContext());
    String text = request.getCharSequenceText() == null ? request.getText() : request.getCharSequenceText().toString();
    if (tts == null || text == null)
    {
      callback.error();
      return;
    }
    float speed = Math.max(0.5f,
                           Math.min(2.0f, request.getSpeechRate() / 100f * MotoVoices.speed(getApplicationContext())));
    GeneratedAudio audio;
    synchronized (sLock)
    {
      audio = tts.generate(text, 0, speed);
    }
    if (mStopped)
      return;
    callback.start(audio.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1);
    byte[] pcm = toPcm(audio.samples);
    int max = callback.getMaxBufferSize();
    for (int off = 0; off < pcm.length && !mStopped; off += max)
      callback.audioAvailable(pcm, off, Math.min(max, pcm.length - off));
    callback.done();
  }

  @NonNull
  private static byte[] toPcm(@NonNull float[] samples)
  {
    byte[] out = new byte[samples.length * 2];
    for (int i = 0; i < samples.length; i++)
    {
      int v = Math.round(Math.max(-1f, Math.min(1f, samples[i])) * 32767f);
      out[2 * i] = (byte) (v & 0xFF);
      out[2 * i + 1] = (byte) ((v >> 8) & 0xFF);
    }
    return out;
  }

  private static void copyAssets(@NonNull AssetManager assets, @NonNull String path, @NonNull File target)
      throws IOException
  {
    String[] children = assets.list(path);
    if (children == null || children.length == 0)
    {
      File parent = target.getParentFile();
      if (parent != null && !parent.exists() && !parent.mkdirs())
        throw new IOException("mkdirs " + parent);
      try (InputStream in = assets.open(path); OutputStream out = new FileOutputStream(target))
      {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0)
          out.write(buf, 0, n);
      }
      return;
    }
    if (!target.exists() && !target.mkdirs())
      throw new IOException("mkdirs " + target);
    for (String child : children)
      copyAssets(assets, path + "/" + child, new File(target, child));
  }

  @NonNull
  private static String read(@NonNull File f) throws IOException
  {
    try (InputStream in = new java.io.FileInputStream(f))
    {
      byte[] b = new byte[32];
      int n = in.read(b);
      return n > 0 ? new String(b, 0, n).trim() : "";
    }
  }

  private static void write(@NonNull File f, @NonNull String s) throws IOException
  {
    File parent = f.getParentFile();
    if (parent != null && !parent.exists() && !parent.mkdirs())
      throw new IOException("mkdirs " + parent);
    try (OutputStream out = new FileOutputStream(f))
    {
      out.write(s.getBytes());
    }
  }
}
