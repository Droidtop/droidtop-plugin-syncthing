// Copyright (C) 2026 bi0shacker001
// SPDX-License-Identifier: GPL-3.0-or-later

package main

import (
	"context"
	"errors"
	"net"
	"strings"
	"sync"
)

// Android resolves names through netd's Unix socket, which this process's filter refuses (docs/plugin-api.md 5.3,
// "Open processes"). So every lookup goes through Go's own resolver, dialing the DNS servers of the active network
// that droidtop reports (net.get_links). Everything in Syncthing that resolves a name (global discovery, relays, the
// peers' dynamic addresses) uses net.DefaultResolver, directly or through a net.Dialer.

var (
	dnsMu      sync.RWMutex
	dnsServers []string
)

func init() {
	net.DefaultResolver = &net.Resolver{PreferGo: true, Dial: dialDNS}
}

func setDNS(servers []string) {
	clean := make([]string, 0, len(servers))
	for _, s := range servers {
		s = strings.TrimSpace(s)
		if s == "" {
			continue
		}
		if net.ParseIP(strings.SplitN(s, "%", 2)[0]) == nil {
			continue
		}
		clean = append(clean, s)
	}
	dnsMu.Lock()
	dnsServers = clean
	dnsMu.Unlock()
}

// dialDNS ignores the server Go's resolver picked (it has no resolv.conf on Android and falls back to loopback) and
// dials droidtop's servers in order.
func dialDNS(ctx context.Context, network, address string) (net.Conn, error) {
	dnsMu.RLock()
	servers := dnsServers
	dnsMu.RUnlock()
	if len(servers) == 0 {
		return nil, errors.New("no DNS servers known for the active network")
	}
	var d net.Dialer
	var lastErr error
	for _, server := range servers {
		conn, err := d.DialContext(ctx, network, net.JoinHostPort(server, "53"))
		if err == nil {
			return conn, nil
		}
		lastErr = err
	}
	return nil, lastErr
}
