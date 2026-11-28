# 공통 환경 (source 해서 사용)
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ADB="$ANDROID_HOME/platform-tools/adb"
export APP_ID=com.eudero.gano
export AVD_NAME=test34
export PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
