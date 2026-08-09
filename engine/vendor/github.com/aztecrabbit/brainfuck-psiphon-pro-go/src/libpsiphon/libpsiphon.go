package libpsiphon

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"time"

	"github.com/aztecrabbit/liblog"
	"github.com/aztecrabbit/libproxyrotator"
	"github.com/aztecrabbit/libutils"
)

var (
	Loop          = true
	DefaultConfig = &Config{
		CoreName: "psiphon-tunnel-core",
		Tunnel:   1,
		Region:   "",
		Protocols: []string{
			"FRONTED-MEEK-HTTP-OSSH",
			"FRONTED-MEEK-OSSH",
		},
		TunnelWorkers:  6,
		KuotaDataLimit: 4,
		Authorizations: make([]string, 0),
	}
	DefaultKuotaData = &KuotaData{
		Port: make(map[int]map[string]float64),
		All:  0,
	}
	ConfigPathPsiphon = libutils.GetConfigPath("brainfuck-psiphon-pro-go", "storage/psiphon")
)

func Stop() {
	Loop = false
}

func RemoveData() {
	os.RemoveAll(ConfigPathPsiphon + "/data")
}

type Config struct {
	CoreName       string
	Tunnel         int
	Region         string
	Protocols      []string
	TunnelWorkers  int
	KuotaDataLimit int
	Authorizations []string
}

type KuotaData struct {
	Port map[int]map[string]float64
	All  float64
}

type Data struct {
	MigrateDataStoreDirectory string
	// omitempty: an empty UpstreamProxyURL must be OMITTED, not sent as "",
	// otherwise Psiphon treats it as a configured (empty) proxy and every dial
	// fails. Same for the other optional fields in no-bug mode.
	UpstreamProxyURL         string `json:",omitempty"`
	LocalSocksProxyPort      int
	SponsorId                string
	PropagationChannelId     string
	EmitBytesTransferred     bool
	EmitDiagnosticNotices    bool
	DisableLocalHTTPProxy    bool
	EgressRegion             string   `json:",omitempty"`
	TunnelPoolSize           int
	ConnectionWorkerPoolSize int
	LimitTunnelProtocols     []string `json:",omitempty"`
	Authorizations           []string `json:",omitempty"`
}

type Psiphon struct {
	ProxyRotator    *libproxyrotator.ProxyRotator
	Config          *Config
	ProxyPort       string
	KuotaData       *KuotaData
	ListenPort      int
	TunnelConnected int
	Verbose         bool
}

func (p *Psiphon) LogInfo(message string, color string) {
	if Loop {
		liblog.LogInfo(message, strconv.Itoa(p.ListenPort), color)
	}
}

func (p *Psiphon) LogVerbose(message string, color string) {
	if p.Verbose {
		p.LogInfo(fmt.Sprintf("%[1]sVERBOSE%[3]s %[2]s::%[3]s %[1]s", color, liblog.Colors["P1"], liblog.Colors["CC"])+message, color)
	}
}

func (p *Psiphon) GetAuthorizations() []string {
	data := make([]string, 0)

	if len(p.Config.Authorizations) != 0 {
		data = append(data, p.Config.Authorizations[0])
		p.Config.Authorizations = append(p.Config.Authorizations[1:], p.Config.Authorizations[0])
	}

	return data
}

func (p *Psiphon) CheckKuotaDataLimit(sent float64, received float64) bool {
	if p.Config.KuotaDataLimit != 0 && int(p.KuotaData.Port[p.ListenPort]["all"]) >= (p.Config.KuotaDataLimit*1000000) &&
		int(sent) == 0 && int(received) <= 64000 {
		return false
	}

	return true
}

func (p *Psiphon) Start() {
	sponsorId := os.Getenv("BF_SPONSOR")
	if sponsorId == "" {
		sponsorId = "0000000000000000"
	}
	propagationChannelId := os.Getenv("BF_PROPAGATION")
	if propagationChannelId == "" {
		propagationChannelId = "0000000000000000"
	}

	// No-bug mode (BF_NOBUG=1): connect Psiphon straight to its servers without
	// routing through the local injector, and let it pick any tunnel protocol.
	// Used to prove the core + server list work when a carrier "bug host" has
	// died — if this connects but the bugged mode does not, the bug is the fault.
	noBug := os.Getenv("BF_NOBUG") == "1"
	upstreamProxyURL := "http://127.0.0.1:" + p.ProxyPort
	limitProtocols := p.Config.Protocols
	if noBug {
		upstreamProxyURL = ""
		limitProtocols = nil
	}

	PsiphonData := &Data{
		MigrateDataStoreDirectory: ConfigPathPsiphon + "/data/" + strconv.Itoa(p.ListenPort),
		UpstreamProxyURL:          upstreamProxyURL,
		LocalSocksProxyPort:       p.ListenPort,
		SponsorId:                 sponsorId,
		PropagationChannelId:      propagationChannelId,
		EmitBytesTransferred:      true,
		EmitDiagnosticNotices:     true,
		DisableLocalHTTPProxy:     true,
		EgressRegion:              strings.ToUpper(p.Config.Region),
		TunnelPoolSize:            p.Config.Tunnel,
		ConnectionWorkerPoolSize:  p.Config.TunnelWorkers,
		LimitTunnelProtocols:      limitProtocols,
		Authorizations:            p.GetAuthorizations(),
	}

	libutils.JsonWrite(PsiphonData, PsiphonData.MigrateDataStoreDirectory+"/config.json")

	// Optional legacy boltdb seed (kept for backward compatibility).
	PsiphonFileBoltdb := PsiphonData.MigrateDataStoreDirectory + "/ca.psiphon.PsiphonTunnel.tunnel-core/datastore/psiphon.boltdb"
	if _, err := os.Stat(PsiphonFileBoltdb); os.IsNotExist(err) {
		seed := os.Getenv("BF_BOLTDB")
		if seed != "" {
			if _, serr := os.Stat(seed); serr == nil {
				libutils.CopyFile(seed, PsiphonFileBoltdb)
			}
		}
	}

	// Fresh server list imported via psiphon-tunnel-core's own -serverList flag
	// (the official ImportEmbeddedServerEntries path). This avoids depending on
	// a stale bundled datastore.
	serverList := os.Getenv("BF_SERVERLIST")

	p.LogInfo("Connecting", liblog.Colors["G1"])

	for Loop {
		p.KuotaData.Port[p.ListenPort] = make(map[string]float64)
		p.KuotaData.Port[p.ListenPort]["all"] = 0
		p.TunnelConnected = 0

		args := []string{"-config", PsiphonData.MigrateDataStoreDirectory + "/config.json"}
		if serverList != "" {
			args = append(args, "-serverList", serverList)
		}
		command := exec.Command(libutils.RealPath(p.Config.CoreName), args...)
		command.Dir = PsiphonData.MigrateDataStoreDirectory

		stderr, err := command.StderrPipe()
		if err != nil {
			panic(err)
		}

		scanner := bufio.NewScanner(stderr)
		go func() {
			var text string
			var line map[string]interface{}
			for Loop && scanner.Scan() {
				text = scanner.Text()
				json.Unmarshal([]byte(text), &line)

				noticeType := line["noticeType"]

				if noticeType == "BytesTransferred" {
					data := line["data"].(map[string]interface{})
					diagnosticID := data["diagnosticID"].(string)
					sent := data["sent"].(float64)
					received := data["received"].(float64)

					p.KuotaData.Port[p.ListenPort][diagnosticID] += sent + received
					p.KuotaData.Port[p.ListenPort]["all"] += sent + received
					p.KuotaData.All += sent + received

					if p.CheckKuotaDataLimit(sent, received) == false {
						break
					}

					liblog.LogReplace(
						fmt.Sprintf(
							"%v (%v) (%v) (%v)",
							p.ListenPort,
							diagnosticID,
							libutils.BytesToSize(p.KuotaData.Port[p.ListenPort][diagnosticID]),
							libutils.BytesToSize(p.KuotaData.All),
						),
						liblog.Colors["G1"],
					)

				} else if noticeType == "ActiveTunnel" {
					p.ProxyRotator.AddProxy("0.0.0.0:" + strconv.Itoa(p.ListenPort))
					p.TunnelConnected++
					if p.Config.Tunnel > 1 {
						diagnosticID := line["data"].(map[string]interface{})["diagnosticID"].(string)
						p.LogInfo(fmt.Sprintf("Connected (%s)", diagnosticID), liblog.Colors["Y1"])
					}
					if p.TunnelConnected == p.Config.Tunnel {
						p.LogInfo("Connected", liblog.Colors["Y1"])
					}

				} else if noticeType == "Alert" || noticeType == "Warning" {
					message := line["data"].(map[string]interface{})["message"].(string)

					if strings.HasPrefix(message, "Config migration:") {
						continue
					} else if strings.Contains(message, "meek round trip failed") {
						if p.Config.Tunnel == 1 && p.Config.Tunnel == p.TunnelConnected && (message == "meek round trip failed: remote error: tls: bad record MAC" ||
							message == "meek round trip failed: context deadline exceeded" ||
							message == "meek round trip failed: EOF" ||
							strings.Contains(message, "psiphon.CustomTLSDial")) {
							p.LogVerbose(text, liblog.Colors["R1"])
							break
						}
					} else if strings.Contains(message, "controller shutdown due to component failure") ||
						strings.Contains(message, "psiphon.(*ServerContext).DoConnectedRequest") ||
						strings.Contains(message, "psiphon.(*Tunnel).sendSshKeepAlive") ||
						strings.Contains(message, "psiphon.(*Tunnel).Activate") ||
						strings.Contains(message, "underlying conn is closed") ||
						strings.Contains(message, "duplicate tunnel:") ||
						strings.Contains(message, "tunnel failed:") {
						p.LogVerbose(text, liblog.Colors["R1"])
						break
					} else if strings.Contains(message, "A connection attempt failed because the connected party did not properly respond after a period of time") ||
						strings.Contains(message, "No connection could be made because the target machine actively refused it") ||
						strings.Contains(message, "HandleServerRequest for psiphon-alert failed") ||
						strings.Contains(message, "SOCKS proxy accept error: socks5ReadCommand:") ||
						strings.Contains(message, "tunnel.dialTunnel: dialConn is not a Closer") ||
						strings.Contains(message, "making proxy request: unexpected EOF") ||
						strings.Contains(message, "psiphon.(*MeekConn).readPayload") ||
						strings.Contains(message, "response status: 403 Forbidden") ||
						strings.Contains(message, "meek connection has closed") ||
						strings.Contains(message, "meek connection is closed") ||
						strings.Contains(message, "psiphon.(*MeekConn).relay") ||
						strings.Contains(message, "unexpected status code:") ||
						strings.Contains(message, "RemoteAddr returns nil") ||
						strings.Contains(message, "network is unreachable") ||
						strings.Contains(message, "close tunnel ssh error") ||
						strings.Contains(message, "tactics request failed") ||
						strings.Contains(message, "API request rejected") ||
						strings.Contains(message, "context canceled") ||
						strings.Contains(message, "no such host") {
						p.LogVerbose(message, liblog.Colors["G2"])
						continue
					} else if strings.Contains(message, "bind: address already in use") {
						p.LogInfo("Port already in use", liblog.Colors["R1"])
						break
					} else {
						p.LogInfo(text, liblog.Colors["R1"])
					}

				} else if noticeType == "LocalProxyError" {
					continue

				} else if noticeType == "UpstreamProxyError" {
					continue

				} else {
					p.LogVerbose(text, liblog.Colors["CC"])

				}
			}

			libutils.KillProcess(command.Process)
		}()

		command.Start()
		command.Wait()

		p.ProxyRotator.DeleteProxy("0.0.0.0:" + strconv.Itoa(p.ListenPort))

		time.Sleep(200 * time.Millisecond)

		p.LogInfo(fmt.Sprintf("Reconnecting (%s)", libutils.BytesToSize(p.KuotaData.Port[p.ListenPort]["all"])), liblog.Colors["G1"])
	}
}
