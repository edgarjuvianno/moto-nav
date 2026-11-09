package app.organicmaps.moto;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

@SuppressLint("MissingPermission")
public final class MotoChronos
{
  public interface Listener
  {
    void onChronosNotification(@NonNull String app, @NonNull String title, @NonNull String message);

    void onChronosCall(@Nullable String caller);

    void onChronosConnection(boolean connected);

    void onChronosWeather(@NonNull Weather weather);

    void onChronosPhoneBattery(int level, boolean charging);
  }

  private int mPhoneBattery = -1;
  private boolean mPhoneCharging;
  private boolean mFinding;
  private final Runnable mStopFind = () -> setFindPhone(false);
  private final Runnable mHello = this::hello;

  private void hello()
  {
    sendInfo();
    sendBattery();
    send(new byte[] {(byte) 0xAB, 0x00, 0x04, (byte) 0xFE, (byte) 0x91, (byte) 0x80, 0x01});
    if (!mFinding)
      send(new byte[] {(byte) 0xAB, 0x00, 0x04, (byte) 0xFF, 0x7D, (byte) 0x80, 0x00});
  }

  public int getPhoneBattery()
  {
    return mDevice == null ? -1 : mPhoneBattery;
  }

  public boolean isPhoneCharging()
  {
    return mPhoneCharging;
  }

  public boolean isFinding()
  {
    return mFinding;
  }

  public void setFindPhone(boolean ring)
  {
    mHandler.removeCallbacks(mStopFind);
    mFinding = ring && mDevice != null;
    send(new byte[] {(byte) 0xAB, 0x00, 0x04, (byte) 0xFF, 0x7D, (byte) 0x80, (byte) (ring ? 1 : 0)});

  }

  public static final class Weather
  {
    public int icon = -1;
    public int temp;
    public int high = Integer.MIN_VALUE;
    public int low = Integer.MIN_VALUE;
    @NonNull
    public String city = "";
    public final int[] hourlyIcon = new int[24];
    public long updated;

    Weather()
    {
      Arrays.fill(hourlyIcon, -1);
    }

    public boolean hasData()
    {
      return icon >= 0;
    }
  }

  @NonNull
  private final Weather mWeather = new Weather();

  @NonNull
  public Weather getWeather()
  {
    return mWeather;
  }

  private static final String TAG = "MotoChronos";
  private static final UUID SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
  private static final UUID RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
  private static final UUID TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
  private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

  private static MotoChronos sInstance;

  private final Context mContext;
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  @Nullable
  private Listener mListener;
  @Nullable
  private BluetoothGattServer mServer;
  @Nullable
  private BluetoothGattCharacteristic mTx;
  @Nullable
  private BluetoothLeAdvertiser mAdvertiser;
  @Nullable
  private BluetoothDevice mDevice;
  private boolean mAdvertising;
  private boolean mStarted;
  private final ByteArrayOutputStream mBuffer = new ByteArrayOutputStream();
  private int mExpected;

  private final AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback() {
    @Override
    public void onStartSuccess(AdvertiseSettings settingsInEffect)
    {
      Log.i(TAG, "Advertising as Chronos watch");
    }

    @Override
    public void onStartFailure(int errorCode)
    {
      mAdvertising = false;
      Log.w(TAG, "Advertising failed: " + errorCode);
    }
  };

  private final BroadcastReceiver mStateReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent)
    {
      int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
      if (state == BluetoothAdapter.STATE_ON)
        mHandler.postDelayed(MotoChronos.this::open, 1500L);
      else if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF)
        close();
    }
  };

  private final BluetoothGattServerCallback mCallback = new BluetoothGattServerCallback() {
    @Override
    public void onConnectionStateChange(BluetoothDevice device, int status, int newState)
    {
      mHandler.post(() -> {
        if (newState == BluetoothProfile.STATE_CONNECTED)
        {
          mDevice = device;
          stopAdvertising();
          notifyConnection(true);
          mHandler.removeCallbacks(mHello);
          mHandler.postDelayed(mHello, 2500L);

        }
        else if (newState == BluetoothProfile.STATE_DISCONNECTED && device.equals(mDevice))
        {
          mDevice = null;
          mFinding = false;
          mHandler.removeCallbacks(mStopFind);
          notifyConnection(false);
          startAdvertising();
        }
      });
    }

    @Override
    public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattCharacteristic characteristic, boolean preparedWrite,
                                             boolean responseNeeded, int offset, byte[] value)
    {
      BluetoothGattServer server = mServer;
      if (responseNeeded && server != null)
        server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
      Log.i(TAG, "write " + characteristic.getUuid() + " " + hex(value));
      if (value == null || !RX.equals(characteristic.getUuid()))
        return;
      byte[] copy = Arrays.copyOf(value, value.length);
      mHandler.post(() -> onWrite(copy));
    }

    @Override
    public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor,
                                         boolean preparedWrite, boolean responseNeeded, int offset, byte[] value)
    {
      BluetoothGattServer server = mServer;
      if (responseNeeded && server != null)
        server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
      Log.i(TAG, "descriptor write " + descriptor.getUuid() + " " + hex(value));
      boolean subscribed = value != null && value.length > 0 && value[0] != 0;
      if (subscribed && CCCD.equals(descriptor.getUuid()))
        mHandler.removeCallbacks(mHello);
        mHandler.postDelayed(mHello, 1000L);
    }

    @Override
    public void onNotificationSent(BluetoothDevice device, int status)
    {
      mHandler.post(() -> onSent(status));
    }

    @Override
    public void onMtuChanged(BluetoothDevice device, int mtu)
    {
      Log.i(TAG, "mtu " + mtu);
    }

    @Override
    public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                            BluetoothGattCharacteristic characteristic)
    {
      Log.i(TAG, "read " + characteristic.getUuid());
      BluetoothGattServer server = mServer;
      if (server != null)
        server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, new byte[0]);
    }

    @Override
    public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
                                        BluetoothGattDescriptor descriptor)
    {
      BluetoothGattServer server = mServer;
      if (server != null)
        server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0,
                            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
    }
  };

  private MotoChronos(@NonNull Context context)
  {
    mContext = context.getApplicationContext();
  }

  @NonNull
  public static synchronized MotoChronos get(@NonNull Context context)
  {
    if (sInstance == null)
      sInstance = new MotoChronos(context);
    return sInstance;
  }

  public void setListener(@Nullable Listener listener)
  {
    mListener = listener;
    if (listener != null)
      listener.onChronosConnection(mDevice != null);
  }

  public boolean isConnected()
  {
    return mDevice != null;
  }

  public void start()
  {
    if (mStarted)
      return;
    if (!hasPermissions())
    {
      Log.w(TAG, "Missing Bluetooth permissions");
      return;
    }
    mStarted = true;
    ContextCompat.registerReceiver(mContext, mStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                                   ContextCompat.RECEIVER_NOT_EXPORTED);
    open();
  }

  private boolean hasPermissions()
  {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
      return true;
    return ContextCompat.checkSelfPermission(mContext, Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED
        && ContextCompat.checkSelfPermission(mContext, Manifest.permission.BLUETOOTH_ADVERTISE)
               == PackageManager.PERMISSION_GRANTED;
  }

  private void open()
  {
    if (mServer != null)
      return;
    BluetoothManager manager = (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
    BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
    if (adapter == null || !adapter.isEnabled())
      return;
    try
    {
      mServer = manager.openGattServer(mContext, mCallback);
      if (mServer == null)
        return;
      BluetoothGattService service = new BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
      mTx = new BluetoothGattCharacteristic(TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                                            BluetoothGattCharacteristic.PERMISSION_READ);
      mTx.addDescriptor(new BluetoothGattDescriptor(
          CCCD, BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
      BluetoothGattCharacteristic rx = new BluetoothGattCharacteristic(
          RX, BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
          BluetoothGattCharacteristic.PERMISSION_WRITE);
      service.addCharacteristic(mTx);
      service.addCharacteristic(rx);
      mServer.addService(service);
      mAdvertiser = adapter.getBluetoothLeAdvertiser();
      mHandler.postDelayed(this::startAdvertising, 500L);
    }
    catch (RuntimeException e)
    {
      Log.w(TAG, "Unable to open GATT server", e);
      close();
    }
  }

  private void close()
  {
    stopAdvertising();
    if (mServer != null)
    {
      try
      {
        mServer.close();
      }
      catch (RuntimeException ignored)
      {
      }
    }
    mServer = null;
    mTx = null;
    if (mDevice != null)
    {
      mDevice = null;
      notifyConnection(false);
    }
  }

  private void startAdvertising()
  {
    if (mAdvertising || mAdvertiser == null || mServer == null || mDevice != null)
      return;
    AdvertiseSettings settings = new AdvertiseSettings.Builder()
                                     .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                                     .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                                     .setConnectable(true)
                                     .build();
    AdvertiseData data = new AdvertiseData.Builder().setIncludeDeviceName(true).build();
    AdvertiseData scan = new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(SERVICE)).build();
    try
    {
      mAdvertiser.startAdvertising(settings, data, scan, mAdvertiseCallback);
      mAdvertising = true;
    }
    catch (RuntimeException e)
    {
      Log.w(TAG, "startAdvertising failed", e);
    }
  }

  private void stopAdvertising()
  {
    if (!mAdvertising || mAdvertiser == null)
      return;
    try
    {
      mAdvertiser.stopAdvertising(mAdvertiseCallback);
    }
    catch (RuntimeException ignored)
    {
    }
    mAdvertising = false;
  }

  private void onWrite(@NonNull byte[] p)
  {
    if (p.length == 0)
      return;
    int b0 = p[0] & 0xFF;
    if (p.length >= 4 && (b0 == 0xAB || b0 == 0xEA) && ((p[3] & 0xFF) == 0xFE || (p[3] & 0xFF) == 0xFF))
    {
      mBuffer.reset();
      mExpected = ((p[1] & 0xFF) << 8) + (p[2] & 0xFF) + 3;
      mBuffer.write(p, 0, p.length);
    }
    else if (mExpected > 0)
    {
      mBuffer.write(p, 1, p.length - 1);
    }
    else
      return;
    if (mBuffer.size() >= mExpected)
    {
      byte[] full = Arrays.copyOf(mBuffer.toByteArray(), mExpected);
      mExpected = 0;
      mBuffer.reset();
      handle(full);
    }
  }

  private void handle(@NonNull byte[] d)
  {
    if (d.length < 5)
      return;
    int head = d[0] & 0xFF;
    int cmd = d[4] & 0xFF;
    if (head == 0xEA)
    {
      handleExtended(d);
      return;
    }
    if (head != 0xAB)
      return;
    if (cmd == 0x7E && d.length >= 8)
    {
      int b = d[6] & 0xFF;
      mWeather.icon = b >> 4;
      mWeather.temp = (d[7] & 0xFF) * ((b & 1) != 0 ? -1 : 1);
      mWeather.updated = System.currentTimeMillis();
      Log.i(TAG, "Weather icon=" + mWeather.icon + " temp=" + mWeather.temp);
      publishWeather();
      return;
    }
    if (cmd == 0x88 && d.length >= 8)
    {
      int h = d[6] & 0xFF;
      int l = d[7] & 0xFF;
      mWeather.high = (h & 0x7F) * ((h & 0x80) != 0 ? -1 : 1);
      mWeather.low = (l & 0x7F) * ((l & 0x80) != 0 ? -1 : 1);
      publishWeather();
      return;
    }
    if (cmd == 0x72 && d.length >= 8)
    {
      int icon = d[6] & 0xFF;
      int state = d[7] & 0xFF;
      String text = new String(d, 8, d.length - 8, StandardCharsets.UTF_8).trim();
      Listener l = mListener;
      if (icon == 0x01)
      {
        Log.i(TAG, "Incoming call");
        if (l != null)
          l.onChronosCall(text.isEmpty() ? "Unknown caller" : text);
        return;
      }
      if (icon == 0x02)
      {
        if (l != null)
          l.onChronosCall(null);
        return;
      }
      if (state != 0x02)
        return;
      if (text.startsWith("Phone battery"))
        return;
      String app = appName(icon);
      String title = app;
      String message = text;
      int colon = text.indexOf(':');
      int newline = text.indexOf('\n');
      if (colon != -1 && colon < 30 && (newline == -1 || newline > colon))
      {
        title = text.substring(0, colon).trim();
        message = text.substring(colon + 1).trim();
      }
      Log.i(TAG, "Notification from " + app);
      if (l != null)
        l.onChronosNotification(app, title, message);
    }
    else if (cmd == 0x92)
      sendInfo();
    else if (cmd == 0x91 && (d[3] & 0xFF) == 0xFF)
      sendBattery();
    else if (cmd == 0x91 && (d[3] & 0xFF) == 0xFE && d.length >= 8)
    {
      mPhoneCharging = d[6] == 1;
      mPhoneBattery = d[7] & 0xFF;
      Log.i(TAG, "Phone battery " + mPhoneBattery + (mPhoneCharging ? " charging" : ""));
      Listener l = mListener;
      if (l != null)
        l.onChronosPhoneBattery(mPhoneBattery, mPhoneCharging);
    }
    else if (cmd == 0x71)
    {
      Listener l = mListener;
      if (l != null)
        l.onChronosNotification("Chronos", "Find device", "Your phone is looking for Moto Nav");
    }
  }

  private void handleExtended(@NonNull byte[] d)
  {
    if ((d[4] & 0xFF) != 0x7E || d.length < 7)
      return;
    int sub = d[5] & 0xFF;
    if (sub == 0x01 && d.length > 7)
    {
      mWeather.city = new String(d, 7, d.length - 7, StandardCharsets.UTF_8).trim();
      publishWeather();
    }
    else if (sub == 0x02 && d.length > 8)
    {
      int size = d[6] & 0xFF;
      int hour = d[7] & 0xFF;
      for (int z = 0; z < size; z++)
      {
        int idx = 8 + 6 * z;
        if (idx >= d.length || hour + z >= 24)
          break;
        mWeather.hourlyIcon[hour + z] = (d[idx] & 0xFF) >> 4;
      }
      publishWeather();
    }
  }

  private void publishWeather()
  {
    Listener l = mListener;
    if (l != null)
      l.onChronosWeather(mWeather);
  }

  private void sendInfo()
  {
    send(new byte[] {(byte) 0xab, 0x00, 0x11, (byte) 0xff, (byte) 0x92, (byte) 0xc0, 0x01, (byte) 91, 0x00,
                     (byte) 0xfb, 0x1e, 0x40, (byte) 0xc0, 0x0e, 0x32, 0x28, 0x00, (byte) 0xe2, 0x00,
                     (byte) 0x80});
  }

  private void sendBattery()
  {
    Intent battery = mContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    int level = battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
    int plugged = battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
    send(new byte[] {(byte) 0xAB, 0x00, 0x05, (byte) 0xFF, (byte) 0x91, (byte) 0x80, (byte) (plugged != 0 ? 1 : 0),
                     (byte) level});
  }

  private final java.util.ArrayDeque<byte[]> mQueue = new java.util.ArrayDeque<>();
  private boolean mSending;

  private void send(@NonNull byte[] value)
  {
    mQueue.add(value);
    if (!mSending)
      sendNext();
  }

  private void onSent(int status)
  {
    Log.i(TAG, "notification sent status=" + status);
    mSending = false;
    mHandler.postDelayed(this::sendNext, 30L);
  }

  private void sendNext()
  {
    byte[] value = mQueue.poll();
    if (value == null)
      return;
    Log.i(TAG, "notify " + hex(value));
    BluetoothGattServer server = mServer;
    BluetoothGattCharacteristic tx = mTx;
    BluetoothDevice device = mDevice;
    if (server == null || tx == null || device == null)
      return;
    try
    {
      int result;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        result = server.notifyCharacteristicChanged(device, tx, false, value);
      else
      {
        tx.setValue(value);
        result = server.notifyCharacteristicChanged(device, tx, false) ? 0 : -1;
      }
      Log.i(TAG, "notify result=" + result);
      mSending = result == 0;
      if (!mSending)
        mHandler.postDelayed(() -> {
          mQueue.addFirst(value);
          sendNext();
        }, 100L);
    }
    catch (RuntimeException e)
    {
      Log.w(TAG, "notify failed", e);
    }
  }

  @NonNull
  private static String hex(@Nullable byte[] v)
  {
    if (v == null)
      return "null";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < Math.min(v.length, 40); i++)
      sb.append(String.format(java.util.Locale.ROOT, "%02X", v[i] & 0xFF));
    return sb.toString();
  }

  private void notifyConnection(boolean connected)
  {
    Log.i(TAG, connected ? "Phone connected" : "Phone disconnected");
    Listener l = mListener;
    if (l != null)
      l.onChronosConnection(connected);
  }

  @NonNull
  private static String appName(int id)
  {
    switch (id)
    {
    case 0x03: return "Message";
    case 0x04: return "Mail";
    case 0x08: return "Skype";
    case 0x09: return "WeChat";
    case 0x0A: return "WhatsApp";
    case 0x0B: return "Gmail";
    case 0x0E: return "Line";
    case 0x0F: return "X";
    case 0x10: return "Facebook";
    case 0x11: return "Messenger";
    case 0x12: return "Instagram";
    case 0x14: return "KakaoTalk";
    case 0x16: return "Viber";
    case 0x18: return "Telegram";
    case 0x20: return "WhatsApp Business";
    case 0xC0: return "Chronos";
    default: return "Notification";
    }
  }
}
