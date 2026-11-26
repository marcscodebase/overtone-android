package main

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

func directDial(ctx context.Context, network, addr string) (net.Conn, error) {
	var d net.Dialer
	return d.DialContext(ctx, network, addr)
}

func TestLocalAuthProxyRejectsUnauthenticatedAndWrongToken(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("secret-audio"))
	}))
	defer upstream.Close()

	proxy, err := startLocalAuthProxy(directDial)
	if err != nil {
		t.Fatalf("startLocalAuthProxy failed: %v", err)
	}
	defer proxy.Close()

	noAuthProxyURL, _ := url.Parse(fmt.Sprintf("http://127.0.0.1:%d", proxy.Port()))
	noAuthClient := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(noAuthProxyURL)}}
	resp, err := noAuthClient.Get(upstream.URL)
	if err != nil {
		t.Fatalf("GET without auth errored: %v", err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusProxyAuthRequired {
		t.Fatalf("expected 407 without auth, got %d", resp.StatusCode)
	}

	wrongAuthProxyURL, _ := url.Parse(fmt.Sprintf("http://wg:wrongtoken@127.0.0.1:%d", proxy.Port()))
	wrongAuthClient := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(wrongAuthProxyURL)}}
	resp2, err := wrongAuthClient.Get(upstream.URL)
	if err != nil {
		t.Fatalf("GET with wrong auth errored: %v", err)
	}
	_ = resp2.Body.Close()
	if resp2.StatusCode != http.StatusProxyAuthRequired {
		t.Fatalf("expected 407 with wrong auth, got %d", resp2.StatusCode)
	}
}

func TestLocalAuthProxyStreamsHttpWithRangeAndStripsProxyAuth(t *testing.T) {
	var seenProxyAuth string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seenProxyAuth = r.Header.Get("Proxy-Authorization")
		if r.Header.Get("Range") == "bytes=0-4" {
			w.Header().Set("Content-Range", "bytes 0-4/10")
			w.WriteHeader(http.StatusPartialContent)
			_, _ = w.Write([]byte("FLAC!"))
			return
		}
		_, _ = w.Write([]byte("FLAC!AUDIO"))
	}))
	defer upstream.Close()

	proxy, err := startLocalAuthProxy(directDial)
	if err != nil {
		t.Fatalf("startLocalAuthProxy failed: %v", err)
	}
	defer proxy.Close()

	proxyURL, _ := url.Parse(fmt.Sprintf("http://wg:%s@127.0.0.1:%d", proxy.Token(), proxy.Port()))
	client := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(proxyURL)}}

	req, _ := http.NewRequest(http.MethodGet, upstream.URL+"/stream", nil)
	req.Header.Set("Range", "bytes=0-4")
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("proxied HTTP GET failed: %v", err)
	}
	defer resp.Body.Close()

	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusPartialContent {
		t.Fatalf("expected 206, got %d", resp.StatusCode)
	}
	if string(body) != "FLAC!" {
		t.Fatalf("expected FLAC!, got %q", string(body))
	}
	if seenProxyAuth != "" {
		t.Fatalf("Proxy-Authorization leaked to upstream: %q", seenProxyAuth)
	}
}

func TestLocalAuthProxyHandlesHttpsConnectTunnel(t *testing.T) {
	upstream := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("hello-over-tls"))
	}))
	defer upstream.Close()

	proxy, err := startLocalAuthProxy(directDial)
	if err != nil {
		t.Fatalf("startLocalAuthProxy failed: %v", err)
	}
	defer proxy.Close()

	proxyURL, _ := url.Parse(fmt.Sprintf("http://wg:%s@127.0.0.1:%d", proxy.Token(), proxy.Port()))
	client := &http.Client{
		Transport: &http.Transport{
			Proxy:           http.ProxyURL(proxyURL),
			TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
		},
	}

	resp, err := client.Get(upstream.URL + "/System/Info/Public")
	if err != nil {
		t.Fatalf("proxied HTTPS GET failed: %v", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if string(body) != "hello-over-tls" {
		t.Fatalf("expected hello-over-tls, got %q", string(body))
	}
}

func TestParseAddrsCsvAndIpcStats(t *testing.T) {
	addrs, err := parseAddrsCsv("10.0.0.2/32, fd00::2/128")
	if err != nil {
		t.Fatalf("parseAddrsCsv failed: %v", err)
	}
	if len(addrs) != 2 || addrs[0].String() != "10.0.0.2" || addrs[1].String() != "fd00::2" {
		t.Fatalf("unexpected parsed addrs: %v", addrs)
	}

	ipc := strings.Join([]string{
		"public_key=abcd",
		"endpoint=1.2.3.4:51820",
		"last_handshake_time_sec=1732650000",
		"rx_bytes=123456",
		"tx_bytes=654321",
	}, "\n")
	rx, tx, hs, ep := parseIpcStats(ipc)
	if rx != 123456 || tx != 654321 || hs != 1732650000 || ep != "1.2.3.4:51820" {
		t.Fatalf("unexpected stats: rx=%d tx=%d hs=%d ep=%q", rx, tx, hs, ep)
	}
}
