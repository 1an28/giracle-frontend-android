#!/usr/bin/env bash
# CI 用: Android エミュレータを KVM 付きで起動し、起動完了まで待つ (#12、#8 でも使う)。
#   API     : 使う Android の API レベル (既定 35)
#   TARGET  : システムイメージの種類 (既定 google_apis)
#   ARCH    : x86_64 (Linux ランナーは x86_64)
# 終わると adb で 1 台につながった状態になる。エミュレータの出力は ~/emulator.log。
set -euo pipefail
API=${API:-35}
TARGET=${TARGET:-google_apis}
ARCH=${ARCH:-x86_64}
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
SDKM=$(ls -d "$SDK"/cmdline-tools/*/bin/sdkmanager | sort -V | tail -1)
AVDM=$(dirname "$SDKM")/avdmanager
IMAGE="system-images;android-$API;$TARGET;$ARCH"

# GitHub のランナーは /dev/kvm があるが、一般ユーザーに権限が無いので開ける
if [ -e /dev/kvm ]; then
  echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules > /dev/null
  sudo udevadm control --reload-rules
  sudo udevadm trigger --name-match=kvm
  ls -l /dev/kvm
else
  echo "::warning::/dev/kvm がありません (ソフトウェアエミュレーションは遅すぎるので使わない)"
  exit 1
fi

t0=$(date +%s)
yes 2> /dev/null | "$SDKM" --licenses > /dev/null || true
# 入れるときにもライセンスの同意を尋ねることがあるので、yes を渡す (渡さないと入力待ちで止まる)
# (yes は書き込み先が閉じると SIGPIPE で終わるので、pipefail を外して sdkmanager の結果だけを見る)
set +o pipefail
yes 2> /dev/null | timeout 600 "$SDKM" "emulator" "platform-tools" "$IMAGE" > "$HOME/sdkmanager.log" 2>&1
rc=${PIPESTATUS[1]}
set -o pipefail
if [ "$rc" != 0 ]; then tail -n 30 "$HOME/sdkmanager.log" >&2; exit 1; fi
echo "sdk packages: $(( $(date +%s) - t0 ))s"
"$SDK/emulator/emulator" -accel-check || true

# avdmanager と emulator で AVD の置き場所の解釈がずれることがあるので、明示してそろえる
export ANDROID_AVD_HOME="$HOME/.android/avd"
mkdir -p "$ANDROID_AVD_HOME"
echo no | "$AVDM" create avd -n ci -k "$IMAGE" -d pixel_6 --force
"$SDK/emulator/emulator" -list-avds
# 画面は 1080x2400 (pixel_6)。描画は CPU (swiftshader)、音声・カメラなし、毎回まっさらな状態から起動する
t1=$(date +%s)
nohup "$SDK/emulator/emulator" -avd ci -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader_indirect -camera-back none -camera-front none -memory 4096 -cores 4 \
  > "$HOME/emulator.log" 2>&1 &
ADB="$SDK/platform-tools/adb"
timeout 180 "$ADB" wait-for-device || { echo "adb does not see the emulator" >&2; tail -n 50 "$HOME/emulator.log" >&2; exit 1; }
for _ in $(seq 300); do
  if [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then break; fi
  sleep 1
done
if [ "$("$ADB" shell getprop sys.boot_completed | tr -d '\r')" != "1" ]; then
  echo "emulator did not boot" >&2; tail -n 50 "$HOME/emulator.log" >&2; exit 1
fi
echo "boot: $(( $(date +%s) - t1 ))s"
# アニメーションを止め、画面を点けたままにする (UI 操作の待ちを安定させる)
"$ADB" shell settings put global window_animation_scale 0
"$ADB" shell settings put global transition_animation_scale 0
"$ADB" shell settings put global animator_duration_scale 0
"$ADB" shell svc power stayon true
"$ADB" shell input keyevent 82 || true
"$ADB" devices
# 後の手順から adb を使えるようにする
if [ -n "${GITHUB_PATH:-}" ]; then echo "$SDK/platform-tools" >> "$GITHUB_PATH"; fi
