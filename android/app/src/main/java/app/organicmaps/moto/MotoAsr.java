package app.organicmaps.moto;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MotoAsr
{
  public static final class Model
  {
    public final String id;
    public final String name;
    public final String description;
    public final int sizeMb;

    Model(String id, String name, String description, int sizeMb)
    {
      this.id = id;
      this.name = name;
      this.description = description;
      this.sizeMb = sizeMb;
    }

    @NonNull
    String repo()
    {
      return "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-" + id + "/resolve/main/";
    }

    @NonNull
    String[] files()
    {
      return new String[] {id + "-tokens.txt", id + "-encoder.int8.onnx", id + "-decoder.int8.onnx"};
    }
  }

  public interface ModelsListener
  {
    void onModelsChanged();
  }

  public interface Callback
  {
    void onListening();

    void onLevel(float level);

    void onProcessing();

    void onResult(@Nullable String text);

    void onError(@NonNull String message);
  }

  public static final List<Model> MODELS = Arrays.asList(
      new Model("tiny.en", "Whisper Tiny English", "Fastest, good for short commands", 110),
      new Model("base.en", "Whisper Base English", "Most accurate for English commands", 157),
      new Model("tiny", "Whisper Tiny", "Multilingual, less accurate on English", 104),
      new Model("base", "Whisper Base", "Multilingual, less accurate on English", 161));

  public static final String LANG_EN = "en";

  private static final String TAG = "MotoAsr";
  private static final String PREFS = "moto";
  private static final String KEY_MODEL = "asr_model";
  private static final int SAMPLE_RATE = 16000;
  private static final int WINDOW = 512;
  private static final long MAX_LISTEN_MS = 9000L;
  private static final long NO_SPEECH_MS = 5000L;
  private static final float TARGET_PEAK = 0.6f;
  private static final float MAX_GAIN_HEADSET = 16f;
  private static final float MAX_GAIN_PHONE = 6f;

  private static final ExecutorService DOWNLOADS = Executors.newSingleThreadExecutor();
  private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
  private static final Map<String, Integer> PROGRESS = new ConcurrentHashMap<>();
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final Object LOCK = new Object();
  @Nullable
  private static ModelsListener sModelsListener;
  @Nullable
  private static OfflineRecognizer sRecognizer;
  @Nullable
  private static String sRecognizerKey;
  private static volatile boolean sCancel;
  private static volatile boolean sRunning;
  @Nullable
  private static volatile BluetoothHeadset sHeadset;
  private static boolean sHeadsetBound;

  private static final class HeadsetRoute
  {
    @Nullable
    BluetoothDevice voiceRecognition;
    int restoreMode = -1;
  }

  private MotoAsr() {}

  public static void setModelsListener(@Nullable ModelsListener l)
  {
    sModelsListener = l;
  }

  @NonNull
  static File dir(@NonNull Context c, @NonNull Model m)
  {
    return new File(c.getFilesDir(), "asr/" + m.id);
  }

  public static boolean isInstalled(@NonNull Context c, @NonNull Model m)
  {
    for (String f : m.files())
      if (!new File(dir(c, m), f).isFile())
        return false;
    return true;
  }

  public static int progress(@NonNull Model m)
  {
    Integer p = PROGRESS.get(m.id);
    return p == null ? -1 : p;
  }

  @Nullable
  public static Model selected(@NonNull Context c)
  {
    String id = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODEL, null);
    for (Model m : MODELS)
      if (m.id.equals(id) && isInstalled(c, m))
        return m;
    return null;
  }

  public static void select(@NonNull Context c, @NonNull Model m)
  {
    c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODEL, m.id).apply();
    releaseRecognizer();
    notifyModels();
  }

  @NonNull
  public static String language(@NonNull Context c)
  {
    return LANG_EN;
  }

  public static void delete(@NonNull Context c, @NonNull Model m)
  {
    boolean wasSelected = selected(c) == m;
    File d = dir(c, m);
    File[] files = d.listFiles();
    if (files != null)
      for (File f : files)
        if (!f.delete())
          Log.w(TAG, "Unable to delete " + f);
    if (!d.delete())
      Log.w(TAG, "Unable to delete " + d);
    if (wasSelected)
      releaseRecognizer();
    notifyModels();
  }

  public static void download(@NonNull Context context, @NonNull Model m)
  {
    if (PROGRESS.containsKey(m.id))
      return;
    Context app = context.getApplicationContext();
    PROGRESS.put(m.id, 0);
    notifyModels();
    DOWNLOADS.execute(() -> {
      File d = dir(app, m);
      try
      {
        if (!d.exists() && !d.mkdirs())
          throw new IOException("mkdirs " + d);
        String[] files = m.files();
        long[] sizes = new long[files.length];
        long total = 0;
        for (int i = 0; i < files.length; i++)
        {
          sizes[i] = contentLength(m.repo() + files[i]);
          total += Math.max(0, sizes[i]);
        }
        long before = 0;
        for (int i = 0; i < files.length; i++)
        {
          File target = new File(d, files[i]);
          if (!target.isFile() || (sizes[i] > 0 && target.length() != sizes[i]))
          {
            final long base = before;
            final long all = total;
            fetch(m.repo() + files[i], target, done -> {
              if (all > 0)
              {
                int p = (int) ((base + done) * 100 / all);
                Integer old = PROGRESS.get(m.id);
                if (old == null || old != p)
                {
                  PROGRESS.put(m.id, p);
                  notifyModels();
                }
              }
            });
          }
          before += Math.max(0, sizes[i]);
        }
        PROGRESS.remove(m.id);
        if (selected(app) == null)
          select(app, m);
        else
          notifyModels();
      }
      catch (IOException e)
      {
        Log.w(TAG, "Download failed for " + m.id, e);
        PROGRESS.remove(m.id);
        MAIN.post(() -> android.widget.Toast.makeText(app, "Download failed. Check your connection.",
                                                      android.widget.Toast.LENGTH_SHORT).show());
        notifyModels();
      }
    });
  }

  private interface Sink
  {
    void onBytes(long done);
  }

  @NonNull
  private static HttpURLConnection open(@NonNull String url, @NonNull String method) throws IOException
  {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setRequestMethod(method);
    c.setInstanceFollowRedirects(true);
    c.setConnectTimeout(15000);
    c.setReadTimeout(30000);
    int code = c.getResponseCode();
    if (code / 100 == 3)
    {
      String next = c.getHeaderField("Location");
      c.disconnect();
      if (next == null)
        throw new IOException("Redirect without location");
      c = (HttpURLConnection) new URL(new URL(url), next).openConnection();
      c.setRequestMethod(method);
      c.setConnectTimeout(15000);
      c.setReadTimeout(30000);
    }
    return c;
  }

  private static long contentLength(@NonNull String url)
  {
    try
    {
      HttpURLConnection c = open(url, "HEAD");
      long len = c.getContentLengthLong();
      c.disconnect();
      return len;
    }
    catch (IOException e)
    {
      return -1;
    }
  }

  private static void fetch(@NonNull String url, @NonNull File target, @NonNull Sink sink) throws IOException
  {
    File tmp = new File(target.getPath() + ".part");
    HttpURLConnection c = open(url, "GET");
    if (c.getResponseCode() != 200)
      throw new IOException("HTTP " + c.getResponseCode() + " for " + url);
    long done = 0;
    try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp))
    {
      byte[] buf = new byte[65536];
      int n;
      long lastReport = 0;
      while ((n = in.read(buf)) > 0)
      {
        out.write(buf, 0, n);
        done += n;
        if (done - lastReport > 512 * 1024)
        {
          lastReport = done;
          sink.onBytes(done);
        }
      }
    }
    finally
    {
      c.disconnect();
    }
    if (!tmp.renameTo(target))
      throw new IOException("rename " + tmp);
  }

  private static void notifyModels()
  {
    MAIN.post(() -> {
      ModelsListener l = sModelsListener;
      if (l != null)
        l.onModelsChanged();
    });
  }

  private static void releaseRecognizer()
  {
    synchronized (LOCK)
    {
      if (sRecognizer != null)
        sRecognizer.release();
      sRecognizer = null;
      sRecognizerKey = null;
    }
  }

  @Nullable
  private static OfflineRecognizer recognizer(@NonNull Context c)
  {
    Model m = selected(c);
    if (m == null)
      return null;
    String lang = language(c);
    String key = m.id + ":" + lang;
    synchronized (LOCK)
    {
      if (sRecognizer != null && key.equals(sRecognizerKey))
        return sRecognizer;
      if (sRecognizer != null)
        sRecognizer.release();
      File d = dir(c, m);
      OfflineRecognizerConfig config = new OfflineRecognizerConfig();
      config.modelConfig.whisper.encoder = new File(d, m.id + "-encoder.int8.onnx").getAbsolutePath();
      config.modelConfig.whisper.decoder = new File(d, m.id + "-decoder.int8.onnx").getAbsolutePath();
      config.modelConfig.whisper.language = lang;
      config.modelConfig.whisper.task = "transcribe";
      config.modelConfig.tokens = new File(d, m.id + "-tokens.txt").getAbsolutePath();
      config.modelConfig.modelType = "whisper";
      config.modelConfig.numThreads = 4;
      long t = SystemClock.elapsedRealtime();
      sRecognizer = new OfflineRecognizer(null, config);
      sRecognizerKey = key;
      Log.i(TAG, "Recognizer " + key + " ready in " + (SystemClock.elapsedRealtime() - t) + " ms");
      return sRecognizer;
    }
  }

  public static void warmUp(@NonNull Context context)
  {
    Context app = context.getApplicationContext();
    bindHeadset(app);
    WORKER.execute(() -> {
      try
      {
        recognizer(app);
      }
      catch (RuntimeException e)
      {
        Log.w(TAG, "Warm up failed", e);
      }
    });
  }

  public static boolean isListening()
  {
    return sRunning;
  }

  public static void cancel()
  {
    sCancel = true;
  }

  @SuppressLint("MissingPermission")
  public static void listen(@NonNull Context context, @NonNull Callback cb)
  {
    if (sRunning)
      return;
    Context app = context.getApplicationContext();
    if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
    {
      cb.onError("Microphone permission needed");
      return;
    }
    if (selected(app) == null)
    {
      cb.onError("Download a voice input model in Settings");
      return;
    }
    sRunning = true;
    sCancel = false;
    WORKER.execute(() -> {
      AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
      HeadsetRoute route = null;
      AudioRecord rec = null;
      Vad vad = null;
      Object focus = requestFocus(am);
      try
      {
        route = routeToHeadset(app, am);
        boolean sco = route != null;
        int source = sco ? MediaRecorder.AudioSource.VOICE_COMMUNICATION : MediaRecorder.AudioSource.VOICE_RECOGNITION;
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        rec = new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                              Math.max(min, WINDOW * 8));
        VadModelConfig vc = new VadModelConfig();
        vc.sileroVadModelConfig.model = "moto-asr/silero_vad.onnx";
        vc.sileroVadModelConfig.minSilenceDuration = 0.6f;
        vc.sileroVadModelConfig.minSpeechDuration = 0.2f;
        vc.sileroVadModelConfig.maxSpeechDuration = 8.0f;
        vc.sileroVadModelConfig.threshold = sco ? 0.4f : 0.5f;
        vad = new Vad(app.getAssets(), vc);
        OfflineRecognizer recognizer = recognizer(app);
        if (recognizer == null)
          throw new IllegalStateException("No model");
        rec.startRecording();
        MAIN.post(cb::onListening);
        short[] pcm = new short[WINDOW];
        float[] buf = new float[WINDOW];
        float[] all = new float[(int) (SAMPLE_RATE * (MAX_LISTEN_MS / 1000.0 + 1))];
        short[] raw = new short[all.length];
        int total = 0;
        long start = SystemClock.elapsedRealtime();
        boolean heard = false;
        float maxGain = sco ? MAX_GAIN_HEADSET : MAX_GAIN_PHONE;
        float gain = 1f;
        float rawPeak = 0f;
        SpeechSegment segment = null;
        while (!sCancel)
        {
          int n = rec.read(pcm, 0, WINDOW);
          // A blocking read only returns <= 0 on errors that repeat forever (mic taken, dead audio server).
          if (n <= 0)
            throw new IllegalStateException("Mic read failed: " + n);
          float peak = 0f;
          for (int i = 0; i < n; i++)
            peak = Math.max(peak, Math.abs(pcm[i] / 32768f));
          rawPeak = Math.max(rawPeak, peak);
          float want = peak > 0.0005f ? Math.min(maxGain, TARGET_PEAK / peak) : maxGain;
          gain = want < gain ? want : gain + (want - gain) * 0.05f;
          double sum = 0;
          for (int i = 0; i < n; i++)
          {
            buf[i] = Math.max(-1f, Math.min(1f, pcm[i] / 32768f * gain));
            sum += buf[i] * buf[i];
            if (total < all.length)
            {
              raw[total] = pcm[i];
              all[total++] = buf[i];
            }
          }
          float level = (float) Math.min(1.0, Math.sqrt(sum / n) * 8);
          MAIN.post(() -> cb.onLevel(level));
          vad.acceptWaveform(n == WINDOW ? buf : Arrays.copyOf(buf, n));
          if (vad.isSpeechDetected())
            heard = true;
          if (!vad.empty())
          {
            segment = vad.front();
            vad.pop();
            break;
          }
          long elapsed = SystemClock.elapsedRealtime() - start;
          if ((!heard && elapsed > NO_SPEECH_MS) || elapsed > MAX_LISTEN_MS)
          {
            vad.flush();
            if (!vad.empty())
            {
              segment = vad.front();
              vad.pop();
            }
            break;
          }
        }
        rec.stop();
        if (sCancel)
        {
          MAIN.post(() -> cb.onResult(null));
          return;
        }
        saveCapture(app, raw, total);
        Log.i(TAG, "Listened " + (sco ? "headset" : "phone") + " mic, raw peak " + rawPeak + ", gain " + gain
                       + (segment == null ? ", no speech" : ""));
        if (segment == null || segment.samples.length < SAMPLE_RATE / 4)
        {
          MAIN.post(() -> cb.onResult(null));
          return;
        }
        MAIN.post(cb::onProcessing);
        long t = SystemClock.elapsedRealtime();
        int from = Math.max(0, segment.start - SAMPLE_RATE * 4 / 10);
        int to = Math.min(total, segment.start + segment.samples.length + SAMPLE_RATE / 4);
        float[] audio = to > from ? Arrays.copyOfRange(all, from, to) : segment.samples;
        float peak = 0;
        for (float v : audio)
          peak = Math.max(peak, Math.abs(v));
        if (peak > 0.01f && peak < 0.7f)
        {
          float boost = Math.min(8f, 0.85f / peak);
          for (int i = 0; i < audio.length; i++)
            audio[i] *= boost;
        }
        Log.i(TAG, "Segment " + segment.samples.length + " samples, padded " + audio.length + ", peak " + peak);
        OfflineStream stream = recognizer.createStream();
        stream.acceptWaveform(audio, SAMPLE_RATE);
        String text;
        synchronized (LOCK)
        {
          recognizer.decode(stream);
          OfflineRecognizerResult r = recognizer.getResult(stream);
          text = r.text == null ? "" : r.text.trim();
        }
        stream.release();
        Log.i(TAG, "Heard \"" + text + "\" in " + (SystemClock.elapsedRealtime() - t) + " ms");
        String result = text;
        MAIN.post(() -> cb.onResult(result.isEmpty() ? null : result));
      }
      catch (RuntimeException e)
      {
        Log.e(TAG, "Listening failed", e);
        MAIN.post(() -> cb.onError("Voice input failed"));
      }
      finally
      {
        if (rec != null)
          rec.release();
        if (vad != null)
          vad.release();
        if (route != null)
          clearRoute(am, route);
        abandonFocus(am, focus);
        sRunning = false;
      }
    });
  }

  @Nullable
  private static Object requestFocus(@Nullable AudioManager am)
  {
    if (am == null)
      return null;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
    {
      AudioFocusRequest r =
          new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
              .setAudioAttributes(new AudioAttributes.Builder()
                                      .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                      .build())
              .setOnAudioFocusChangeListener(change -> {})
              .build();
      am.requestAudioFocus(r);
      SystemClock.sleep(150);
      return r;
    }
    AudioManager.OnAudioFocusChangeListener l = change -> {};
    am.requestAudioFocus(l, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE);
    SystemClock.sleep(150);
    return l;
  }

  private static void abandonFocus(@Nullable AudioManager am, @Nullable Object focus)
  {
    if (am == null || focus == null)
      return;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focus instanceof AudioFocusRequest r)
      am.abandonAudioFocusRequest(r);
    else if (focus instanceof AudioManager.OnAudioFocusChangeListener l)
      am.abandonAudioFocus(l);
  }

  private static boolean canUseBluetooth(@NonNull Context c)
  {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        && ContextCompat.checkSelfPermission(c, Manifest.permission.BLUETOOTH_CONNECT)
               == PackageManager.PERMISSION_GRANTED;
  }

  private static synchronized void bindHeadset(@NonNull Context app)
  {
    if (sHeadsetBound || !canUseBluetooth(app))
      return;
    BluetoothManager bm = app.getSystemService(BluetoothManager.class);
    BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
    if (adapter == null)
      return;
    // The proxy reconnects by itself when Bluetooth is turned off and on again.
    sHeadsetBound = adapter.getProfileProxy(app, new BluetoothProfile.ServiceListener() {
      @Override
      public void onServiceConnected(int profile, BluetoothProfile proxy)
      {
        sHeadset = (BluetoothHeadset) proxy;
      }

      @Override
      public void onServiceDisconnected(int profile)
      {
        sHeadset = null;
      }
    }, BluetoothProfile.HEADSET);
  }

  // Intercoms keep their mic off on a bare SCO link and only open it during a call or a voice assistant
  // session, so ask for the latter (+BVRA) the way phone assistants do.
  @SuppressLint("MissingPermission")
  @Nullable
  private static BluetoothDevice startVoiceRecognition(@NonNull Context c, @NonNull String address)
  {
    bindHeadset(c);
    for (int i = 0; i < 10 && sHeadset == null; i++)
      SystemClock.sleep(50);
    BluetoothHeadset headset = sHeadset;
    if (headset == null)
      return null;
    try
    {
      BluetoothDevice target = null;
      for (BluetoothDevice d : headset.getConnectedDevices())
        if (target == null || d.getAddress().equalsIgnoreCase(address))
          target = d;
      if (target == null || !headset.startVoiceRecognition(target))
        return null;
      long until = SystemClock.elapsedRealtime() + 2500;
      while (!headset.isAudioConnected(target) && SystemClock.elapsedRealtime() < until)
        SystemClock.sleep(50);
      if (headset.isAudioConnected(target))
        return target;
      Log.w(TAG, "Headset did not open audio for voice recognition");
      headset.stopVoiceRecognition(target);
    }
    catch (RuntimeException e)
    {
      Log.w(TAG, "Voice recognition on headset failed", e);
    }
    return null;
  }

  @Nullable
  private static HeadsetRoute routeToHeadset(@NonNull Context c, @Nullable AudioManager am)
  {
    if (am == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
      return null;
    AudioDeviceInfo sco = null;
    for (AudioDeviceInfo d : am.getAvailableCommunicationDevices())
    {
      if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
      {
        sco = d;
        break;
      }
    }
    if (sco == null)
      return null;
    HeadsetRoute route = new HeadsetRoute();
    if (canUseBluetooth(c))
      route.voiceRecognition = startVoiceRecognition(c, sco.getAddress());
    if (route.voiceRecognition == null)
    {
      // Headsets without voice recognition support: a communication session is the next best call-like state.
      route.restoreMode = am.getMode();
      am.setMode(AudioManager.MODE_IN_COMMUNICATION);
    }
    if (!am.setCommunicationDevice(sco))
    {
      clearRoute(am, route);
      return null;
    }
    SystemClock.sleep(600);
    Log.i(TAG, "Recording from Bluetooth headset " + sco.getProductName()
                   + (route.voiceRecognition != null ? " in voice recognition" : " in communication mode"));
    return route;
  }

  @SuppressLint("MissingPermission")
  private static void clearRoute(@NonNull AudioManager am, @NonNull HeadsetRoute route)
  {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
      am.clearCommunicationDevice();
    BluetoothHeadset headset = sHeadset;
    if (route.voiceRecognition != null && headset != null)
    {
      try
      {
        headset.stopVoiceRecognition(route.voiceRecognition);
      }
      catch (RuntimeException e)
      {
        Log.w(TAG, "Unable to stop voice recognition", e);
      }
    }
    if (route.restoreMode != -1)
      am.setMode(route.restoreMode);
  }

  // Keeps the last raw capture to check what the mic delivered:
  // adb pull /sdcard/Android/data/id.moto.nav/files/asr-last.wav
  private static void saveCapture(@NonNull Context c, @NonNull short[] pcm, int n)
  {
    File dir = c.getExternalFilesDir(null);
    if (dir == null)
      return;
    ByteBuffer b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN);
    b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + n * 2);
    b.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
    b.putShort((short) 1).putShort((short) 1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2);
    b.putShort((short) 2).putShort((short) 16);
    b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(n * 2);
    for (int i = 0; i < n; i++)
      b.putShort(pcm[i]);
    try (OutputStream out = new FileOutputStream(new File(dir, "asr-last.wav")))
    {
      out.write(b.array());
    }
    catch (IOException e)
    {
      Log.w(TAG, "Unable to save capture", e);
    }
  }
}
