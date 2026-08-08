# Brainfuck VPN (Brainfuck‑Psiphon × SocksDroid)

Ứng dụng Android tích hợp **[brainfuck-psiphon-pro-go]** (tạo tunnel Psiphon + kỹ
thuật inject SNI/Host “bug host”) vào **[SocksDroid]** (VPN dùng `VpnService` +
`tun2socks`). Kết quả là **một APK duy nhất**: bật lên là toàn bộ lưu lượng của
máy được đẩy qua tunnel Psiphon.

[brainfuck-psiphon-pro-go]: https://github.com/aztecrabbit/brainfuck-psiphon-pro-go
[SocksDroid]: https://github.com/bndeff/socksdroid

---

## Cách hoạt động (Architecture)

```
   Ứng dụng trên máy
          │  (mọi packet)
          ▼
   Android VpnService  ──►  TUN interface
          │
          ▼
   libtun2socks.so  ──SOCKS5──►  127.0.0.1:3080
                                      │  (proxy rotator)
                                      ▼
                          libbrainfuck.so  (engine)
                            ├─ injector (SNI/Host “bug host”)  :8989
                            └─ N × libpsiphon.so  (Psiphon core)
                                      │
                                      ▼
                                  Internet
```

- **`libtun2socks.so`, `libpdnsd.so`, `libsystem.so`** — lấy nguyên bản (prebuilt)
  từ SocksDroid, nằm sẵn trong `app/src/main/jniLibs/`.
- **`libbrainfuck.so`** — engine Go (thư mục `engine/`), phiên bản Android của
  brainfuck-psiphon: chạy proxy rotator SOCKS5 ở cổng `3080`, injector ở `8989`,
  và khởi động các Psiphon core. **Không** dùng redsocks (trên Android `tun2socks`
  đảm nhiệm việc định tuyến).
- **`libpsiphon.so`** — Psiphon core (`ConsoleClient` của psiphon-tunnel-core),
  build bằng CI.
- **`SocksVpnService`** khởi động engine trước, chờ cổng `3080` sẵn sàng, rồi trỏ
  `tun2socks` vào `127.0.0.1:3080`.

Vì engine chạy dưới cùng UID với app, và VpnService loại trừ chính app khỏi VPN
(`addDisallowedApplication`), lưu lượng ra ngoài của Psiphon **không bị loop** trở
lại tun.

---

## Lấy file APK (Get the APK)

Repo này **không** build APK trong máy dev (môi trường bị chặn tải Android SDK).
APK được build bằng **GitHub Actions** — nơi có sẵn Android SDK + NDK:

1. Push code lên GitHub (hoặc mở tab **Actions** và chạy workflow **Build APK**
   thủ công bằng *Run workflow*).
2. Workflow `.github/workflows/build-apk.yml` sẽ:
   - cross-compile `libbrainfuck.so` và `libpsiphon.so` cho 3 ABI
     (`arm64-v8a`, `armeabi-v7a`, `x86_64`) bằng NDK,
   - build **APK debug và release** (release ký bằng keystore tạo tự động),
   - đăng APK vào phần **Artifacts** của lần chạy.
3. Tải `BrainfuckVPN-apks` từ Artifacts, cài `app-debug.apk` (hoặc
   `app-release.apk`) lên điện thoại.

> Tạo một **tag** dạng `v1.0` (`git tag v1.0 && git push --tags`) thì APK còn được
> đính kèm vào một **GitHub Release**.

---

## Cấu hình “bug host” / SNI (trong app)

Mở app → mục **Brainfuck-Psiphon Tunnel**:

| Mục | Ý nghĩa |
|-----|---------|
| **Payload / Bug Host** | Payload HTTP injector, vd: `[raw][crlf]Host: bug.host.com[crlf][crlf]`. Để trống = tunnel Psiphon thường. |
| **SNI (Server Name)** | Host dùng cho SNI-based bug. |
| **Egress Region** | Mã vùng Psiphon 2 ký tự (`sg`, `jp`, `us`…). Trống = tự động. |
| **Injection Method** | `Direct` (không inject) hoặc `Payload / SNI rewrite`. |
| **Tunnel Count** | Số tunnel Psiphon song song (mặc định 2). |
| **Tunnel Protocols** | `LimitTunnelProtocols`, phẩy ngăn cách. Trống = `FRONTED-MEEK` mặc định. |

Các trường **Server IP / Server Port** của SocksDroid bị **bỏ qua** — app luôn dùng
engine nội bộ ở `127.0.0.1:3080`.

Các tham số này được truyền vào engine qua biến môi trường (`BF_PAYLOAD`, `BF_SNI`,
`BF_REGION`, `BF_CORES`, `BF_INJECT_TYPE`, `BF_PROTOCOLS`, …) — xem
`engine/main.go`.

---

## Build engine ở máy (tùy chọn)

Engine là một Go module tự chứa (đã `go mod vendor`):

```bash
cd engine
# ví dụ build thử cho Linux để kiểm tra biên dịch
GOFLAGS=-mod=vendor CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -o /tmp/libbrainfuck.so .
```

Để chạy trên Android cần build `GOOS=android` + NDK (xem workflow CI).

---

## Ghi chú thẳng thắn (Honest notes)

- **Kết nối được hay không phụ thuộc vào Psiphon và “bug host” của nhà mạng.** File
  `app/src/main/assets/psiphon.boltdb` (danh sách server nhúng, lấy từ brainfuck)
  có thể đã cũ; khi đó Psiphon sẽ cố tự làm mới server list. Kỹ thuật “free
  internet” qua bug host phụ thuộc hoàn toàn vào nhà mạng của bạn.
- Dự án chỉ **tích hợp** phần mềm mã nguồn mở sẵn có; công lao thuộc về các tác giả
  gốc (xem `NOTICE.md`).
- Toàn bộ dự án theo giấy phép **GPL-3.0** (kế thừa từ SocksDroid & Psiphon).
- Chỉ dùng cho mục đích hợp pháp: vượt kiểm duyệt, bảo vệ quyền riêng tư, nghiên
  cứu. Bạn tự chịu trách nhiệm tuân thủ pháp luật & điều khoản nhà mạng.

---

## Cấu trúc thư mục

```
app/                      Ứng dụng Android (nền SocksDroid, đã tích hợp)
  src/main/java/...        SocksVpnService (đã sửa), TunnelEngine.java, UI
  src/main/assets/         psiphon.boltdb (server list nhúng)
  src/main/jniLibs/<abi>/  libtun2socks/pdnsd/system.so (prebuilt)
                           + libbrainfuck.so, libpsiphon.so (CI thêm vào)
engine/                   Go tunnel engine (brainfuck-psiphon cho Android)
  main.go                 launcher Android, điều khiển bằng env var
  third_party/            các lib aztecrabbit (vendored, có patch nhỏ)
  vendor/                 dependency Go bên ngoài
.github/workflows/        build-apk.yml (CI build & ký APK)
```
