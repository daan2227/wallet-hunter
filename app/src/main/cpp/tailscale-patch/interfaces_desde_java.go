// Las interfaces de red se las damos NOSOTROS, desde Java.
//
// # POR QUE HACE FALTA
//
// Al levantar el nodo, tsnet contestaba:
//
//	tsnet.Up: tsnet: route ip+net: netlinkrib: permission denied
//
// Go pregunta al sistema por las interfaces de red con netlink, y Android 11
// en adelante se lo prohibe a las apps normales. No es cosa de permisos que se
// puedan pedir: el sistema lo bloquea y punto.
//
// Lo dice el propio codigo de Tailscale, en net/netmon/state.go:
//
//	// It exists because Android SDK 30 no longer permits Go's net.Interfaces
//	// to work (Issue 2293); this wrapper lets us the Android app register
//	// an alternate implementation.
//
// O sea que dejan el gancho a proposito, y es lo que usa su propia app de
// Android. Esto es lo mismo: registrar una implementacion alternativa que, en
// vez de preguntar al kernel, devuelve lo que Java ya ha averiguado.
//
// # POR QUE JAVA SI PUEDE
//
// java.net.NetworkInterface.getNetworkInterfaces() funciona en Android — la app
// ya lo usa para enseñar sus direcciones en la pantalla de red. El bloqueo es
// al netlink en crudo desde codigo nativo, no a la API de Java.
//
// # POR QUE SE EMPUJA Y NO SE PREGUNTA
//
// Lo natural seria que Go llamara a Java cuando necesitara la lista. Pero esa
// llamada llegaria desde cualquier hilo de Go, y para usar JNI desde un hilo
// que no conoce la maquina virtual hay que engancharlo primero y soltarlo
// despues; hacerlo mal deja hilos colgados o revienta.
//
// # EL NOMBRE DEL FICHERO IMPORTA
//
// Se llamaba interfaces_android.go y eso era un error silencioso: Go trata el
// sufijo _android como condicion de compilacion, asi que el fichero solo se
// compilaba para GOOS=android. Al probarlo en el anfitrion se excluia SOLO, la
// compilacion salia bien y el simbolo exportado no aparecia por ningun lado —
// sin un solo mensaje que lo dijera.
//
// Sin sufijo se compila siempre, y asi se puede verificar antes de subirlo. No
// estorba en otras plataformas: el gancho existe en todas.
//
// Asi que Java EMPUJA la lista y aqui se guarda. El getter devuelve lo
// guardado, sin tocar JNI ni bloquear a nadie. A cambio hay que acordarse de
// refrescarla cuando la red cambie, que es justo lo que hace TsNet.kt antes de
// levantar el nodo.
package main

import "C"

import (
	"net"
	"strconv"
	"strings"
	"sync"

	"tailscale.com/net/netmon"
)

var (
	ifMu    sync.Mutex
	ifCache []netmon.Interface
)

func init() {
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		ifMu.Lock()
		defer ifMu.Unlock()
		// Copia: quien la reciba puede quedarsela todo lo que quiera, y
		// mientras tanto Java puede estar empujando otra.
		out := make([]netmon.Interface, len(ifCache))
		copy(out, ifCache)
		return out, nil
	})
}

// Formato, una interfaz por linea:
//
//	nombre|indice|mtu|banderas|ip/prefijo,ip/prefijo
//
// banderas son un subconjunto de: up,broadcast,loopback,ptp,multicast
//
// Se eligio texto plano y no algo mas serio porque cruza la frontera C->Go una
// vez por arranque con cuatro lineas dentro: cualquier cosa mas elaborada seria
// mas codigo que depurar a ciegas sin ganar nada.
//
//export tsnet_set_interfaces
func tsnet_set_interfaces(spec *C.char) C.int {
	lista := parseInterfaces(C.GoString(spec))
	ifMu.Lock()
	ifCache = lista
	ifMu.Unlock()
	return C.int(len(lista))
}

func parseInterfaces(txt string) []netmon.Interface {
	var out []netmon.Interface
	for _, linea := range strings.Split(txt, "\n") {
		linea = strings.TrimSpace(linea)
		if linea == "" {
			continue
		}
		campos := strings.Split(linea, "|")
		if len(campos) < 4 {
			continue
		}
		idx, _ := strconv.Atoi(campos[1])
		mtu, _ := strconv.Atoi(campos[2])

		var flags net.Flags
		for _, f := range strings.Split(campos[3], ",") {
			switch strings.TrimSpace(f) {
			case "up":
				// FlagRunning ademas de FlagUp: hay sitios de tailscale que
				// miran si esta "corriendo" y no solo si esta "levantada", y
				// desde Java no se distinguen.
				flags |= net.FlagUp | net.FlagRunning
			case "broadcast":
				flags |= net.FlagBroadcast
			case "loopback":
				flags |= net.FlagLoopback
			case "ptp":
				flags |= net.FlagPointToPoint
			case "multicast":
				flags |= net.FlagMulticast
			}
		}

		var addrs []net.Addr
		if len(campos) >= 5 {
			for _, a := range strings.Split(campos[4], ",") {
				a = strings.TrimSpace(a)
				if a == "" {
					continue
				}
				// ParseCIDR y no ParseIP: netmon necesita la mascara para
				// saber que hay en la misma red. Sin ella no puede decidir
				// por donde salir.
				ip, red, err := net.ParseCIDR(a)
				if err != nil || red == nil {
					continue
				}
				addrs = append(addrs, &net.IPNet{IP: ip, Mask: red.Mask})
			}
		}

		out = append(out, netmon.Interface{
			Interface: &net.Interface{
				Index:        idx,
				MTU:          mtu,
				Name:         campos[0],
				HardwareAddr: nil, // Android no lo da desde Android 6, y a
				// tailscale no le hace falta para esto.
				Flags: flags,
			},
			AltAddrs: addrs,
		})
	}
	return out
}

// NO se declara main() aqui: tailscale.go, del propio libtailscale, ya la
// tiene. Declararla otra vez seria "main redeclared in this block".
