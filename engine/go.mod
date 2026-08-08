module bfpsiphon

go 1.20

require (
	github.com/aztecrabbit/brainfuck-psiphon-pro-go v0.0.0
	github.com/aztecrabbit/libinject v0.0.0
	github.com/aztecrabbit/liblog v0.0.0
	github.com/aztecrabbit/libproxyrotator v0.0.0
	github.com/aztecrabbit/libutils v0.0.0
)

require (
	github.com/armon/go-socks5 v0.0.0-20160902184237-e75332964ef5 // indirect
	github.com/aztecrabbit/libredsocks v0.0.0 // indirect
	github.com/buger/goterm v1.0.4 // indirect
	golang.org/x/net v0.21.0 // indirect
	golang.org/x/sys v0.17.0 // indirect
)

replace (
	github.com/aztecrabbit/brainfuck-psiphon-pro-go => ./third_party/brainfuck
	github.com/aztecrabbit/libinject => ./third_party/libinject
	github.com/aztecrabbit/liblog => ./third_party/liblog
	github.com/aztecrabbit/libproxyrotator => ./third_party/libproxyrotator
	github.com/aztecrabbit/libredsocks => ./third_party/libredsocks
	github.com/aztecrabbit/libutils => ./third_party/libutils
)
