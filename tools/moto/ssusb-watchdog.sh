#!/system/bin/sh
# Magisk service.d watchdog for the Zenfone Max Pro M1 (X00TD).
#
# A spurious type-c-change interrupt while parked can leave the dwc3 USB
# controller runtime-active with no cable attached. Its wakeup source
# (a800000.ssusb) then blocks suspend and the battery drains ~2x faster.
# When that wakeup source stays active while nothing is plugged in, cycle the
# controller through peripheral -> none so it drops back to low-power mode.
#
# Install: /data/adb/service.d/ssusb-watchdog.sh (chmod 755). Log: /data/adb/ssusb-watchdog.log

DWC=/sys/devices/platform/soc/a800000.ssusb
PS=/sys/class/power_supply
LOG=/data/adb/ssusb-watchdog.log
CHECK_S=60
STUCK_MS=300000  # active this long with nothing plugged in counts as stuck

log() {
  [ -f "$LOG" ] && [ "$(wc -c < "$LOG")" -gt 65536 ] && mv -f "$LOG" "$LOG.old"
  echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"
}

find_ws() {
  for w in /sys/class/wakeup/wakeup*; do
    [ "$(cat "$w/name" 2>/dev/null)" = "a800000.ssusb" ] && echo "$w" && return
  done
}

plugged() {
  [ "$(cat $PS/usb/online 2>/dev/null)" = "1" ] || [ "$(cat $PS/dc/online 2>/dev/null)" = "1" ]
}

until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 5; done
sleep 30

WS=$(find_ws)
if [ -z "$WS" ] || [ ! -w "$DWC/mode" ]; then
  log "ssusb wakeup source or $DWC/mode not found, exiting"
  exit 0
fi
log "started ($WS)"

while true; do
  sleep $CHECK_S
  plugged && continue
  active=$(cat "$WS/active_time_ms" 2>/dev/null)
  [ "${active:-0}" -lt $STUCK_MS ] && continue

  log "stuck: active ${active} ms, unplugged, mode=$(cat $DWC/mode) runtime=$(cat $DWC/power/runtime_status); kicking"
  echo peripheral > "$DWC/mode"
  sleep 3
  echo none > "$DWC/mode"
  sleep 10
  log "after kick: active=$(cat "$WS/active_time_ms") ms runtime=$(cat $DWC/power/runtime_status)"
done
