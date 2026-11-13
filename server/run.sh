#!/usr/bin/env sh
# Forge requires a configured set of both JVM and program arguments.
# Add custom JVM arguments to the user_jvm_args.txt
# Add custom program arguments {such as nogui} to this file in the next line before the "$@" or
#  pass them to this script directly
# 需要系统安装 Java 21；Windows 版 .jdk 不能用于 Unix。
cd "$(dirname "$0")" || exit 1
java @user_jvm_args.txt @libraries/net/neoforged/neoforge/21.1.265/unix_args.txt "$@"
