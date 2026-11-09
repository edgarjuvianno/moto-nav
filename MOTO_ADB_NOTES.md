# Moto Nav – ADB cheat sheet

HP: ASUS Zenfone Max Pro M1 (X00TD), serial `J7AXGF0197942T3`, LineageOS + Magisk.
Fork: `~/Documents/projects/organicmaps`, branch `moto`, paket `id.moto.nav`.

## Koneksi

```bash
adb devices -l
export ANDROID_SERIAL=J7AXGF0197942T3
```

## Build & install Moto Nav

```bash
cd ~/Documents/projects/organicmaps/android
export PATH=$HOME/Library/Android/sdk/cmake/3.22.1/bin:$PATH
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew -Parm64 -Punity -Pnjobs=8 assembleFdroidRelease
adb install -r app/build/outputs/apk/fdroid/release/OrganicMaps-26092901-fdroid-release.apk
```

Build debug (lebih lambat di HP, untuk debugging): ganti `assembleFdroidRelease` dengan `assembleFdroidDebug`, APK di `app/build/outputs/apk/fdroid/debug/`.

## Launcher default

```bash
adb shell cmd package set-home-activity id.moto.nav/app.organicmaps.DownloadResourcesActivity
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

Kembali ke Trebuchet:

```bash
adb shell cmd package set-home-activity com.android.launcher3/.uioverlay.UiOverlayLauncher
```

## Izin Moto Nav (setelah install bersih)

```bash
adb shell pm grant id.moto.nav android.permission.ACCESS_FINE_LOCATION
adb shell pm grant id.moto.nav android.permission.ACCESS_COARSE_LOCATION
adb shell cmd notification allow_listener id.moto.nav/app.organicmaps.moto.MotoMediaListener
```

## File peta Indonesia (.mwm)

Lokasi: `/sdcard/Android/data/id.moto.nav/files/<versi>/` (versi sekarang `260928`).

```bash
adb shell su -c "ls -la /sdcard/Android/data/id.moto.nav/files/260928/"
adb push "Indonesia_Jawa Barat.mwm" /sdcard/Android/data/id.moto.nav/files/260928/
adb shell am force-stop id.moto.nav
```

Versi file peta harus sama dengan versi fork. Update peta dari aplikasi: Apps › Update maps.

## Restart / cek aplikasi

```bash
adb shell am force-stop id.moto.nav
adb shell input keyevent HOME
adb shell dumpsys activity activities | grep topResumedActivity
adb logcat -b crash -d | tail -40
```

## Screenshot

```bash
adb exec-out screencap -p > ~/Desktop/motonav.png
```

## GPS

```bash
adb shell settings get secure location_mode
adb shell dumpsys location | grep -m1 "last location="
```

## ACC (pembatas pengisian) – selalu pakai `su -mm`

```bash
adb shell su -mm -c "acc -i"
adb shell su -mm -c "acc -s pc=85 rc=75"
adb shell su -mm -c "grep -E '^(capacity|chargingSwitch|idleThreshold)' /data/adb/vr25/acc-data/config.txt"
adb shell su -mm -c "acc -D restart"
adb shell dumpsys battery | grep -E "powered|status|level"
```

`pc` = berhenti isi, `rc` = mulai isi lagi. Switch dikunci ke
`battery/constant_charge_current_max 3300000 10000 main/constant_charge_current_max 2000000 0 --`
supaya charger tetap terdeteksi (aman untuk macro Kontak ON/OFF).

## Keyboard & suara

```bash
adb shell ime list -s
adb shell ime set helium314.keyboard/.latin.LatinIME
adb shell pm grant org.futo.voiceinput android.permission.RECORD_AUDIO
```

## Layar kunci

```bash
adb shell locksettings set-disabled true
adb shell locksettings set-disabled false
```

## Freeform (sisa eksperimen, boleh dimatikan)

```bash
adb shell settings put global enable_freeform_support 0
adb shell settings put global force_resizable_activities 0
```

## Tema sistem (untuk tes splash terang/gelap)

```bash
adb shell cmd uimode night no
adb shell cmd uimode night yes
```
