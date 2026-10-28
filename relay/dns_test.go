package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestParseDNSServers(t *testing.T) {
	got := parseDNSServers("1.1.1.1, 9.9.9.9:5353 2001:db8::1")
	want := []string{"1.1.1.1:53", "9.9.9.9:5353", "[2001:db8::1]:53"}
	if len(got) != len(want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("got %v, want %v", got, want)
		}
	}
}

func TestDNSServersUsesPrefixResolvConf(t *testing.T) {
	root := t.TempDir()
	if err := os.MkdirAll(filepath.Join(root, "etc"), 0o755); err != nil {
		t.Fatal(err)
	}
	conf := "search example.com\nnameserver 4.4.4.4\nnameserver 2001:4860:4860::8888\n"
	if err := os.WriteFile(filepath.Join(root, "etc", "resolv.conf"), []byte(conf), 0o644); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PREFIX", root)
	t.Setenv("PIG_DNS", "")
	got := dnsServers()
	if len(got) < 2 || got[0] != "4.4.4.4:53" || got[1] != "[2001:4860:4860::8888]:53" {
		t.Fatalf("prefix resolv.conf not used: %v", got)
	}
}

func TestDNSServersEnvWins(t *testing.T) {
	t.Setenv("PIG_DNS", "192.0.2.53")
	got := dnsServers()
	if len(got) != 1 || got[0] != "192.0.2.53:53" {
		t.Fatalf("PIG_DNS ignored: %v", got)
	}
}

func TestWithDNSPort(t *testing.T) {
	cases := map[string]string{
		"":               "",
		"8.8.8.8":        "8.8.8.8:53",
		"8.8.8.8:5353":   "8.8.8.8:5353",
		"2001:db8::1":    "[2001:db8::1]:53",
		"[2001:db8::1]":  "[2001:db8::1]:53",
		"dns.example:53": "dns.example:53",
	}
	for in, want := range cases {
		if got := withDNSPort(in); got != want {
			t.Errorf("withDNSPort(%q) = %q, want %q", in, got, want)
		}
	}
}
