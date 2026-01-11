package netutil

import (
	"fmt"
	"net"
	"net/url"
	"strings"
)

// CGNATIPv4s returns a list of IPv4 addresses in 100.64.0.0/10.
// This range is commonly used by Tailscale on consumer networks.
//
// It intentionally ignores loopback and link-local addresses.
func CGNATIPv4s() []string {
	var out []string
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil
	}
	for _, iface := range ifaces {
		if (iface.Flags&net.FlagUp) == 0 || (iface.Flags&net.FlagLoopback) != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			var ip net.IP
			switch a := addr.(type) {
			case *net.IPNet:
				ip = a.IP
			case *net.IPAddr:
				ip = a.IP
			}
			if ip == nil {
				continue
			}
			ip4 := ip.To4()
			if ip4 == nil {
				continue
			}
			if isCGNATIPv4(ip4) {
				out = append(out, ip4.String())
			}
		}
	}
	return out
}

// LocalIPv4s returns a list of RFC1918 IPv4 addresses for this machine.
// It intentionally ignores loopback and link-local addresses.
func LocalIPv4s() []string {
	var out []string
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil
	}
	for _, iface := range ifaces {
		if (iface.Flags&net.FlagUp) == 0 || (iface.Flags&net.FlagLoopback) != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			var ip net.IP
			switch a := addr.(type) {
			case *net.IPNet:
				ip = a.IP
			case *net.IPAddr:
				ip = a.IP
			}
			if ip == nil {
				continue
			}
			ip4 := ip.To4()
			if ip4 == nil {
				continue
			}
			if isPrivateIPv4(ip4) {
				out = append(out, ip4.String())
			}
		}
	}
	return out
}

func isPrivateIPv4(ip net.IP) bool {
	// 10.0.0.0/8
	if ip[0] == 10 {
		return true
	}
	// 172.16.0.0/12
	if ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31 {
		return true
	}
	// 192.168.0.0/16
	if ip[0] == 192 && ip[1] == 168 {
		return true
	}
	return false
}

func isCGNATIPv4(ip net.IP) bool {
	// 100.64.0.0/10
	return ip[0] == 100 && ip[1] >= 64 && ip[1] <= 127
}

type TailscaleDecision struct {
	BaseURL string
	Warn    string
}

func DecideTailscaleBaseURL(port int, serveBaseURL string, tailscaleIPv4 string, serveConfigured bool, dnsName string) TailscaleDecision {
	if serveConfigured {
		if strings.TrimSpace(serveBaseURL) != "" {
			if u, err := url.Parse(serveBaseURL); err == nil && u.Host != "" {
				return TailscaleDecision{BaseURL: serveBaseURL}
			}
			return TailscaleDecision{BaseURL: "", Warn: "Tailscale Serve was configured but a valid Serve URL could not be determined."}
		}
		if dnsName != "" {
			return TailscaleDecision{BaseURL: "https://" + dnsName}
		}
		return TailscaleDecision{BaseURL: "", Warn: "Tailscale Serve configured but DNS name was unavailable."}
	}
	if tailscaleIPv4 != "" {
		return TailscaleDecision{BaseURL: fmt.Sprintf("http://%s:%d", tailscaleIPv4, port)}
	}
	return TailscaleDecision{BaseURL: "", Warn: "Could not determine a Tailscale IPv4 for fallback."}
}
