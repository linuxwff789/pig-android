package main

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Android has no /etc/resolv.conf: Termux keeps its own under $PREFIX/etc, and
// bionic apps get their servers from netd instead of a file. The relay is built
// CGO-free, so its Go resolver reads /etc/resolv.conf, finds nothing and falls
// back to [::1]:53 - where no resolver listens - and every provider fetch failed
// with:
//
//	lookup linuxwff.eu.cc on [::1]:53: read udp ...: connection refused
//
// pig itself is CGO enabled and resolves through libc, which is why chat worked
// while "刷新模型列表" did not. Read the Termux file explicitly and, when that
// is missing too, ask public resolvers.

// publicDNS is the last resort; PIG_DNS overrides the whole list.
var publicDNS = []string{"8.8.8.8:53", "1.1.1.1:53", "223.5.5.5:53"}

// dnsServers returns the resolvers to try in order: PIG_DNS, the Termux
// resolv.conf, then public servers.
func dnsServers() []string {
	servers := parseDNSServers(os.Getenv("PIG_DNS"))
	if len(servers) > 0 {
		return servers
	}
	var paths []string
	if prefix := os.Getenv("PREFIX"); prefix != "" {
		paths = append(paths, filepath.Join(prefix, "etc", "resolv.conf"))
	}
	paths = append(paths,
		"/data/data/com.termux/files/usr/etc/resolv.conf",
		"/etc/resolv.conf",
	)
	for _, path := range paths {
		file, err := os.Open(path)
		if err != nil {
			continue
		}
		scanner := bufio.NewScanner(file)
		for scanner.Scan() {
			line := strings.TrimSpace(scanner.Text())
			fields := strings.Fields(line)
			if len(fields) >= 2 && fields[0] == "nameserver" {
				servers = append(servers, withDNSPort(fields[1]))
			}
		}
		file.Close()
		if len(servers) > 0 {
			break
		}
	}
	return append(servers, publicDNS...)
}

// parseDNSServers accepts "1.1.1.1 8.8.8.8:53" (spaces or commas).
func parseDNSServers(raw string) []string {
	out := make([]string, 0, 4)
	for _, field := range strings.FieldsFunc(raw, func(r rune) bool {
		return r == ',' || r == ' ' || r == '\t' || r == '\n'
	}) {
		out = append(out, withDNSPort(field))
	}
	return out
}

func withDNSPort(server string) string {
	server = strings.TrimSpace(server)
	if server == "" {
		return ""
	}
	if _, _, err := net.SplitHostPort(server); err == nil {
		return server
	}
	if strings.HasPrefix(server, "[") && strings.HasSuffix(server, "]") {
		return server + ":53"
	}
	if strings.Count(server, ":") > 1 { // bare IPv6
		return "[" + server + "]:53"
	}
	return net.JoinHostPort(server, "53")
}

// lookupHost resolves host with the platform resolver first (it may work when
// /etc/resolv.conf exists) and then with each entry of dnsServers.
func lookupHost(ctx context.Context, host string) ([]string, error) {
	if addrs, err := net.DefaultResolver.LookupHost(ctx, host); err == nil && len(addrs) > 0 {
		return addrs, nil
	}
	var lastErr error
	for _, server := range dnsServers() {
		server := server
		if server == "" {
			continue
		}
		resolver := &net.Resolver{
			PreferGo: true,
			Dial: func(ctx context.Context, _, _ string) (net.Conn, error) {
				dialer := net.Dialer{Timeout: 4 * time.Second}
				return dialer.DialContext(ctx, "udp", server)
			},
		}
		addrs, err := resolver.LookupHost(ctx, host)
		if err == nil && len(addrs) > 0 {
			return addrs, nil
		}
		if err != nil {
			lastErr = err
		}
	}
	if lastErr == nil {
		lastErr = errors.New("no DNS server answered")
	}
	return nil, fmt.Errorf("lookup %s: %w", host, lastErr)
}

// providerHTTPClient is the client every provider request uses. Its dialer
// resolves names itself so it does not depend on /etc/resolv.conf existing.
func providerHTTPClient() *http.Client {
	dialer := &net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}
	transport := &http.Transport{
		Proxy: http.ProxyFromEnvironment,
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			host, port, err := net.SplitHostPort(addr)
			if err != nil || net.ParseIP(host) != nil {
				return dialer.DialContext(ctx, network, addr)
			}
			ips, err := lookupHost(ctx, host)
			if err != nil {
				return nil, err
			}
			var lastErr error
			for _, ip := range ips {
				conn, err := dialer.DialContext(ctx, network, net.JoinHostPort(ip, port))
				if err == nil {
					return conn, nil
				}
				lastErr = err
			}
			if lastErr == nil {
				lastErr = fmt.Errorf("dial %s: no usable address", addr)
			}
			return nil, lastErr
		},
		TLSHandshakeTimeout: 15 * time.Second,
		IdleConnTimeout:     90 * time.Second,
	}
	return &http.Client{Timeout: providerFetchTimeout, Transport: transport}
}
