#!/usr/bin/env bash
# 一键重新打包 APK
# 用法：bash build-apk.sh
# 改完根目录的 index.html 后跑一次，就会把最新页面打进 APK
#
# 需要的环境（脚本会自动探测，也可以用环境变量指定）：
#   JAVA_HOME     JDK 17 及以上，且必须带 jlink.exe
#                 （AGP 要用 jlink 把 android.jar 处理成 jdk image，精简版 JRE 会失败）
#   ANDROID_HOME  Android SDK，含 platforms/android-34 与 build-tools/34.0.0
#   Gradle 8.7+   命令行 gradle，或 ~/.gradle/wrapper/dists 里已下载好的发行版

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
REQUIRED_PLATFORM="android-34"
REQUIRED_BUILD_TOOLS="34.0.0"

die() {
  echo "" >&2
  echo "构建中止：$*" >&2
  exit 1
}

# ---------- 版本号对账 ----------
# 页面里显示的版本、APK 的 versionName、git tag 必须是同一个数，否则装了新版还显示旧号
APP_VER=$(grep -o "const APP_VERSION = '[^']*'" "$ROOT/index.html" | head -1 | sed "s/.*'\(.*\)'/\1/")
GRADLE_VER=$(grep -o 'versionName "[^"]*"' "$ROOT/android/app/build.gradle" | head -1 | sed 's/.*"\(.*\)"/\1/')

[ -n "$APP_VER" ] || die "没能从 index.html 里读到 APP_VERSION"
if [ "$APP_VER" != "$GRADLE_VER" ]; then
  die "版本号不一致：index.html=$APP_VER，build.gradle=$GRADLE_VER"
fi

# 当前提交上如果已经有 tag，tag 也得对上（构建时还没打 tag 的话就跳过）
HEAD_TAG=$(cd "$ROOT" && git describe --tags --exact-match 2>/dev/null || true)
if [ -n "$HEAD_TAG" ] && [ "v$APP_VER" != "$HEAD_TAG" ]; then
  echo "警告：当前提交的 tag 是 $HEAD_TAG，但版本号是 $APP_VER" >&2
fi

# ---------- 找 JDK ----------
# 只认带 jlink 的完整 JDK，JBR / JRE 这类精简运行时会卡在 JdkImageTransform
has_jlink() { [ -x "$1/bin/jlink" ] || [ -x "$1/bin/jlink.exe" ]; }

pick_jdk() {
  local candidate
  for candidate in \
    "${JAVA_HOME:-}" \
    "/c/Program Files/Java/jdk-22" \
    "/c/Program Files/Java/jdk-21" \
    "/c/Program Files/Java/jdk-17" \
    "$HOME/AndroidBuild/jdk" \
    "${JAVA_HOME:-}"; do
    [ -n "$candidate" ] || continue
    [ -d "$candidate" ] || continue
    if has_jlink "$candidate"; then
      echo "$candidate"
      return 0
    fi
  done
  # 最后兜底：扫一遍常见的 JDK 安装目录
  for candidate in $(ls -d "/c/Program Files/Java"/jdk-* 2>/dev/null | sort -V -r); do
    if has_jlink "$candidate"; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

JDK="$(pick_jdk || true)"
if [ -z "$JDK" ]; then
  die "没找到带 jlink 的 JDK 17+。装一个 JDK（不是 JRE），把 JAVA_HOME 指过去再跑。"
fi

# ---------- 找 Android SDK ----------
pick_sdk() {
  local candidate
  for candidate in \
    "${ANDROID_HOME:-}" \
    "${ANDROID_SDK_ROOT:-}" \
    "$HOME/AndroidBuild/sdk" \
    "$HOME/AppData/Local/Android/Sdk" \
    "/d/Android/Sdk"; do
    [ -n "$candidate" ] || continue
    if [ -d "$candidate/platforms/$REQUIRED_PLATFORM" ]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

SDK="$(pick_sdk || true)"
if [ -z "$SDK" ]; then
  echo "提示：$ANDROID_HOME 里的 SDK 缺少 platforms/$REQUIRED_PLATFORM。" >&2
  die "没找到含 platforms/$REQUIRED_PLATFORM 的 Android SDK，设置 ANDROID_HOME 再跑。"
fi

if [ ! -d "$SDK/build-tools/$REQUIRED_BUILD_TOOLS" ]; then
  die "SDK 缺少 build-tools/$REQUIRED_BUILD_TOOLS，用 sdkmanager 装一下。"
fi

# ---------- 找 Gradle ----------
pick_gradle() {
  if command -v gradle >/dev/null 2>&1; then
    command -v gradle
    return 0
  fi
  # ~/.gradle/wrapper/dists/gradle-x.y.z-bin/<hash>/gradle-x.y.z/bin/gradle
  local found
  found="$(ls -d "$HOME/.gradle/wrapper/dists"/gradle-*/*/*/bin/gradle 2>/dev/null | sort -V | tail -1)"
  if [ -n "$found" ]; then
    echo "$found"
    return 0
  fi
  return 1
}

GRADLE="$(pick_gradle || true)"
if [ -z "$GRADLE" ]; then
  die "没找到 Gradle（需要 8.7+）。装一个 gradle 放进 PATH，或先跑一次 gradlew 让它下载发行版。"
fi

# ---------- 开始构建 ----------
export JAVA_HOME="$JDK"
export ANDROID_HOME="$SDK"
export PATH="$JAVA_HOME/bin:$PATH"

echo "版本    : $APP_VER"
echo "JDK     : $JAVA_HOME"
echo "SDK     : $ANDROID_HOME"
echo "Gradle  : $GRADLE"
echo ""

# 把最新页面同步进 assets，否则打出来的还是上一次的页面
cp "$ROOT/index.html" "$ROOT/android/app/src/main/assets/index.html"

cd "$ROOT/android"
"$GRADLE" assembleRelease --console=plain

cp app/build/outputs/apk/release/app-release.apk "$ROOT/便签.apk"

# ---------- 自检：内嵌页面必须等于根目录那份 ----------
SIZE=$(du -h "$ROOT/便签.apk" | cut -f1)
echo ""
echo "构建完成：$ROOT/便签.apk ($SIZE)"

if command -v unzip >/dev/null 2>&1 && command -v md5sum >/dev/null 2>&1; then
  IN_APK=$(unzip -p "$ROOT/便签.apk" assets/index.html | md5sum | cut -d' ' -f1)
  ON_DISK=$(md5sum "$ROOT/index.html" | cut -d' ' -f1)
  if [ "$IN_APK" = "$ON_DISK" ]; then
    echo "自检通过：APK 内页面与 index.html 一致（md5 ${IN_APK:0:12}）"
  else
    echo "警告：APK 内页面与 index.html 不一致（$IN_APK vs $ON_DISK）" >&2
    exit 1
  fi
fi
