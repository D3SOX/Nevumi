// Android denies netlink to apps, so Go cannot list network interfaces. This
// reports one synthetic interface with the outbound addresses instead, like
// Tailscale's androidbin feature (BSD-3-Clause), which cgo builds don't register.
package main

import (
	"errors"
	"net"
	"net/netip"
	"time"

	"tailscale.com/net/netmon"
)

func init() {
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		var addresses []net.Addr
		if ip, ok := outboundIP("udp4", "8.8.8.8:53"); ok {
			addresses = append(addresses, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(32, 32)})
		}
		if ip, ok := outboundIP("udp6", "[2001:4860:4860::8888]:53"); ok {
			addresses = append(addresses, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(128, 128)})
		}
		if len(addresses) == 0 {
			return nil, errors.New("no outbound network route")
		}
		return []netmon.Interface{{
			Interface: &net.Interface{MTU: 1500, Name: "android", Flags: net.FlagUp | net.FlagRunning},
			AltAddrs:  addresses,
		}}, nil
	})
}

// outboundIP finds the local address of the default route; connecting a UDP socket sends no packets.
func outboundIP(network, address string) (netip.Addr, bool) {
	connection, err := (&net.Dialer{Timeout: 2 * time.Second}).Dial(network, address)
	if err != nil {
		return netip.Addr{}, false
	}
	defer connection.Close()
	local, ok := connection.LocalAddr().(*net.UDPAddr)
	if !ok {
		return netip.Addr{}, false
	}
	ip, ok := netip.AddrFromSlice(local.IP)
	if !ok {
		return netip.Addr{}, false
	}
	ip = ip.Unmap()
	return ip, !ip.IsLoopback() && !ip.IsUnspecified()
}
