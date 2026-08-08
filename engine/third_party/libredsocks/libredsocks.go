package libredsocks

import (
	"os"
	"os/exec"
	"fmt"

	"github.com/aztecrabbit/libutils"
)

var (
	Loop = true
	DefaultConfig = &Config{
		LogInfo: "off",
		LogDebug: "off",
		LogOutput: libutils.RealPath("redsocks.log"),
		LocalHost: "0.0.0.0",
		LocalPort: "3070",
		Host: "127.0.0.1",
		Port: "3080",
		Type: "socks5",
		Username: "",
		Password: "",
		ConfigOutput: libutils.RealPath("redsocks.conf"),
	}
)

func Stop(r *Redsocks) {
	Loop = false
	r.Stop()
}

type Config struct {
	LogInfo string
	LogDebug string
	LogOutput string
	LocalHost string
	LocalPort string
	Host string
	Port string
	Type string
	Username string
	Password string
	ConfigOutput string
}

type Redsocks struct {
	Config *Config
	IsEnabled bool
}

func (r *Redsocks) CheckIsEnabled() bool {
	if os.Geteuid() == 0 && libutils.IsCommandExists("redsocks") {
		return true
	}

	return false
}

func (r *Redsocks) GenerateConfig() error {
	data := fmt.Sprintf(`// Generated from Brainfuck Tunnel Libraries
// (c) 2020 Aztec Rabbit.

base {
	log_info = %s;
	log_debug = %s;
	log = "file:%s";
	daemon = on;
	redirector = iptables;
}

redsocks {
	local_ip = %s;
	local_port = %s;

	ip = %s;
	port = %s;
	type = %s;
	login = "%s";
	password = "%s";
}
`,
		r.Config.LogInfo,
		r.Config.LogDebug,
		r.Config.LogOutput,
		r.Config.LocalHost,
		r.Config.LocalPort,
		r.Config.Host,
		r.Config.Port,
		r.Config.Type,
		r.Config.Username,
		r.Config.Password,
	)

	return libutils.CreateFile(r.Config.ConfigOutput, data)
}

func (r *Redsocks) ForceExecute(command string) error {
	if r.IsEnabled == false {
		return nil
	}

	return exec.Command("sh", "-c", command).Run()
}

func (r *Redsocks) Execute(command string) error {
	if Loop == false {
		return nil
	}

	return r.ForceExecute(command)
}

func (r *Redsocks) RuleDirectCheck(host string) bool {
	err := r.Execute("iptables -t nat -C REDSOCKS -d " + host + " -j RETURN")

	if fmt.Sprintf("%v", err) == "exit status 1" {
		return false
	}

	return true
}

func (r *Redsocks) RuleDirectAdd(host string) {
	if r.IsEnabled == false {
		return
	}

	libutils.Lock.Lock()

	if r.RuleDirectCheck(host) {
		libutils.Lock.Unlock()
		return
	}

	r.Execute("iptables -t nat -I REDSOCKS -d " + host + " -j RETURN")

	libutils.Lock.Unlock()
}

func (r *Redsocks) Stop() {
	commands := []string{
		"iptables -F",
		"iptables -X",
		"iptables -Z",
		"iptables -t nat -F",
		"iptables -t nat -X",
		"iptables -t nat -Z",
		"killall redsocks",
	}

	for _, command := range commands {
		r.ForceExecute(command)
	}

	os.RemoveAll(r.Config.LogOutput)
	os.RemoveAll(r.Config.ConfigOutput)
}

func (r *Redsocks) Start() {
	r.IsEnabled = r.CheckIsEnabled()

	r.Stop()

	if err := r.GenerateConfig(); err != nil {
		return
	}

	commands := []string{
		"iptables -t nat -N REDSOCKS",
		"iptables -t nat -A REDSOCKS -d 0.0.0.0/8 -j RETURN",
		"iptables -t nat -A REDSOCKS -d 10.0.0.0/8 -j RETURN",
		"iptables -t nat -A REDSOCKS -d 127.0.0.0/8 -j RETURN",
		"iptables -t nat -A REDSOCKS -d 169.254.0.0/16 -j RETURN",
		"iptables -t nat -A REDSOCKS -d 172.16.0.0/12 -j RETURN",
		"iptables -t nat -A REDSOCKS -d 192.168.0.0/16 -j RETURN",
		"iptables -t nat -A REDSOCKS -d 224.0.0.0/4 -j RETURN",
		"iptables -t nat -A REDSOCKS -d 240.0.0.0/4 -j RETURN",
		"iptables -t nat -A REDSOCKS -p tcp -j REDIRECT --to-ports 3070",
		"iptables -t nat -A REDSOCKS -p udp -j REDIRECT --to-ports 3070",
		"iptables -t nat -A PREROUTING -d 192.168.0.0/16 -j RETURN",
		"iptables -t nat -A PREROUTING -p tcp -j REDIRECT --to-ports 3070",
		"iptables -t nat -A OUTPUT -j REDSOCKS",
		"redsocks -c " + r.Config.ConfigOutput,
	}

	for _, command := range commands {
		r.Execute(command)
	}
}
