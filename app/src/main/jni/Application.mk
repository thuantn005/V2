# ndk-build settings for the hev-socks5-tunnel native library.
#
# PKGNAME/CLSNAME tell hev-socks5-tunnel's bundled JNI (src/hev-jni.c) which
# Java class to register its native methods on. Our class is
# net.typeblog.socks.HevTunnel, so the JNI looks up "net/typeblog/socks/HevTunnel".
APP_OPTIM := release
APP_PLATFORM := android-21
APP_ABI := armeabi-v7a arm64-v8a x86_64
APP_CFLAGS := -O3 -DPKGNAME=net/typeblog/socks -DCLSNAME=HevTunnel
APP_CPPFLAGS := -O3 -std=c++11
NDK_TOOLCHAIN_VERSION := clang
