# Pulls in hev-socks5-tunnel/Android.mk, which builds libhev-socks5-tunnel.so
# (the tun2socks core plus its JNI bridge) and its static deps (yaml, lwip,
# hev-task-system).
include $(call all-subdir-makefiles)
