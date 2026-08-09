// Command bfpsiphon is the Android-flavoured tunnel engine for the integrated
// Brainfuck-Psiphon + SocksDroid app.
//
// It is adapted from aztecrabbit/brainfuck-psiphon-pro-go. Compared to the
// upstream desktop program it:
//
//   - never starts redsocks (on Android the routing job is done by tun2socks
//     inside the VpnService, not by redsocks);
//   - is fully driven by environment variables instead of interactive flags,
//     so the Android service can configure it;
//   - points the Psiphon core name at the file that ships inside the APK
//     (libpsiphon.so in the app's native library directory);
//   - keeps all writable state under $HOME (the app files directory).
//
// The engine exposes a SOCKS5 proxy on 127.0.0.1:<BF_ROTATOR_PORT> (default
// 3080). tun2socks forwards every packet from the VPN tun interface to that
// port; each connection is load-balanced across one or more Psiphon tunnels,
// whose upstream traffic is first passed through the local injector so that
// SNI / Host-header "bug host" payloads can be applied.
package main

import (
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/aztecrabbit/brainfuck-psiphon-pro-go/src/libpsiphon"
	"github.com/aztecrabbit/libinject"
	"github.com/aztecrabbit/liblog"
	"github.com/aztecrabbit/libproxyrotator"
	"github.com/aztecrabbit/libutils"
)

const (
	appName        = "Brainfuck Tunnel"
	appVersionName = "Android"
	appVersionCode = "2.0"
)

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func envInt(key string, def int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconvAtoi(v); err == nil {
			return n
		}
	}
	return def
}

// strconvAtoi is a tiny wrapper so we do not import strconv twice with libutils.
func strconvAtoi(s string) (int, error) {
	n := 0
	sign := 1
	for i, r := range s {
		if i == 0 && r == '-' {
			sign = -1
			continue
		}
		if r < '0' || r > '9' {
			return 0, fmt.Errorf("invalid int %q", s)
		}
		n = n*10 + int(r-'0')
	}
	return n * sign, nil
}

func main() {
	liblog.Header(
		[]string{
			fmt.Sprintf("%s [%s Version. %s]", appName, appVersionName, appVersionCode),
			"Integrated with SocksDroid tun2socks",
		},
		liblog.Colors["G1"],
	)

	rotatorPort := env("BF_ROTATOR_PORT", "3080")
	injectPort := env("BF_INJECT_PORT", "8989")
	coreName := env("BF_CORE_NAME", "libpsiphon.so")
	cores := envInt("BF_CORES", 3)

	// --- Proxy rotator (the SOCKS5 endpoint tun2socks connects to) ---------
	ProxyRotator := new(libproxyrotator.ProxyRotator)
	ProxyRotator.Config = libproxyrotator.DefaultConfig
	ProxyRotator.Config.Port = rotatorPort

	// --- Injector (SNI / Host-header "bug host" rewriting) -----------------
	Inject := new(libinject.Inject)
	Inject.Redsocks = nil // no redsocks on Android
	Inject.Config = libinject.DefaultConfig
	Inject.Config.Port = injectPort
	Inject.Config.Type = envInt("BF_INJECT_TYPE", 2)
	Inject.Config.MeekType = envInt("BF_MEEK_TYPE", 0)
	Inject.Config.Timeout = envInt("BF_TIMEOUT", 5)
	if p := os.Getenv("BF_PAYLOAD"); p != "" {
		Inject.Config.Payload = p
	}
	if sni := os.Getenv("BF_SNI"); sni != "" {
		Inject.Config.ServerNameIndication = sni
	}
	// Injection rules map a fronted CONNECT target to the carrier "bug" the
	// injector should dial instead. Defaults reproduce a working Viettel (VN)
	// setup: Psiphon fronts through akamai.net but the injector dials the
	// zero-rated IP 125.235.36.177.
	//
	// If BF_WHITELIST includes a port (e.g. "akamai.net:80") a single rule is
	// used. If it is host-only (e.g. "akamai.net") we bug BOTH meek ports —
	// 80 (FRONTED-MEEK-HTTP-OSSH) and 443 (FRONTED-MEEK-OSSH) — to the bug on
	// the matching port, so servers fronting on 443 are not dropped.
	// Bug host is user-supplied and blank by default. Only build injection rules
	// when BOTH the fronted host and the bug IP are given; otherwise inject
	// nothing (Psiphon connects normally, no carrier bug).
	whitelist := os.Getenv("BF_WHITELIST")
	front := os.Getenv("BF_FRONT")
	if whitelist != "" && front != "" {
		if strings.Contains(whitelist, ":") {
			Inject.Config.Rules = map[string][]string{
				whitelist: strings.Split(front, ","),
			}
		} else {
			bug := front
			if strings.Contains(bug, ":") {
				bug = strings.SplitN(bug, ":", 2)[0]
			}
			Inject.Config.Rules = map[string][]string{
				whitelist + ":80":  {bug + ":80"},
				whitelist + ":443": {bug + ":443"},
			}
		}
	} else {
		Inject.Config.Rules = map[string][]string{}
		// No bug configured: connect Psiphon straight to its servers instead of
		// routing it through the (empty-rule) injector, which would just fail.
		// Same effect as BF_NOBUG=1.
		os.Setenv("BF_NOBUG", "1")
	}

	go ProxyRotator.Start()
	go Inject.Start()

	time.Sleep(200 * time.Millisecond)

	liblog.LogInfo("Injector running on port "+Inject.Config.Port, "INFO", liblog.Colors["G1"])
	liblog.LogInfo("SOCKS5 rotator running on port "+ProxyRotator.Config.Port, "INFO", liblog.Colors["G1"])

	// --- Verify the Psiphon core binary is present -------------------------
	corePath := libutils.RealPath(coreName)
	if _, err := os.Stat(corePath); os.IsNotExist(err) {
		liblog.LogInfo("Psiphon core not found: "+corePath, "INFO", liblog.Colors["R1"])
		os.Exit(1)
	}

	// --- Psiphon configuration --------------------------------------------
	psiConfig := libpsiphon.DefaultConfig
	psiConfig.CoreName = coreName
	psiConfig.Region = strings.ToLower(env("BF_REGION", ""))
	psiConfig.Tunnel = envInt("BF_TUNNEL", 1)
	psiConfig.TunnelWorkers = envInt("BF_WORKERS", 6)
	psiConfig.KuotaDataLimit = envInt("BF_LIMIT", 0) // 0 = unlimited
	psiConfig.Authorizations = make([]string, 0)
	if protocols := os.Getenv("BF_PROTOCOLS"); protocols != "" {
		psiConfig.Protocols = strings.Split(protocols, ",")
	}

	verbose := os.Getenv("BF_VERBOSE") == "1"

	for i := 1; i <= cores; i++ {
		Psiphon := new(libpsiphon.Psiphon)
		Psiphon.ProxyRotator = ProxyRotator
		Psiphon.Config = psiConfig
		Psiphon.ProxyPort = Inject.Config.Port
		Psiphon.KuotaData = libpsiphon.DefaultKuotaData
		Psiphon.ListenPort = libutils.Atoi(ProxyRotator.Config.Port) + i
		Psiphon.Verbose = verbose

		go Psiphon.Start()
	}

	// Block forever; the Android service kills the process to stop.
	select {}
}
