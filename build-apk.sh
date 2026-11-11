#!/usr/bin/env bash
# 一键重新打包 APK
# 用法：bash build-apk.sh
# 改完根目录的 index.html 后跑一次，就会把最新页面打进 APK

set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
JDK="$ROOT/.toolchain/sdk-jdk"
SDK="$ROOT/.toolchain/sdk"
GRADLE="$ROOT/.toolchain/gradle-home/gradle-8.7/bin/gradle"

if [ ! -d "$JDK" ] || [ ! -d "$SDK" ]; then
  echo "缺少构建工具链（.toolchain 目录）。请先按 README 里的说明准备 JDK 17 + Android SDK。"
  exit 1
fi

export JAVA_HOME="$JDK"
export ANDROID_HOME="$SDK"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$ROOT/android"
"$GRADLE" assembleRelease --console=plain

cp app/build/outputs/apk/release/app-release.apk "$ROOT/便签.apk"
SIZE=$(du -h "$ROOT/便签.apk" | cut -f1)
echo ""
echo "构建完成：$ROOT/便签.apk ($SIZE)"
