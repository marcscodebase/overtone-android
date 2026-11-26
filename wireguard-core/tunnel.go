package main

import (
	"context"
	"errors"
	"fmt"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

type StartResult struct {
	Success bool   `json:"success"`
	Port    int    `json:"port"`
	Token   string `json:"token"`
	Error   string `json:"error,omitempty"`
}

type StatusResult struct {
	Running               bool   `json:"running"`
	ProxyPort             int    `json:"proxyPort"`
	RxBytes               int64  `json:"rxBytes"`
	TxBytes               int64  `json:"txBytes"`
	LastHandshakeEpochSec int64  `json:"lastHandshakeEpochSec"`
	Endpoint              string `json:"endpoint,omitempty"`
	Error                 string `json:"error,omitempty"`
}

type DialTestResult struct {
	Success   bool   `json:"success"`
	LatencyMs int64  `json:"latencyMs"`
	Error     string `json:"error,omitempty"`
}

type tunnelController struct {
	mu    sync.Mutex
	dev   *device.Device
	tnet  *netstack.Net
	proxy *localAuthProxy
}

var globalTunnel = &tunnelController{}

func (c *tunnelController) Start(uapiConfig, localAddrsCsv, dnsCsv string, mtu int) StartResult {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.stopLocked()

	localAddrs, err := parseAddrsCsv(localAddrsCsv)
	if err != nil || len(localAddrs) == 0 {
		msg := "invalid interface address"
		if err != nil {
			msg = err.Error()
		}
		return StartResult{Success: false, Error: msg}
	}

	dnsAddrs, _ := parseAddrsCsv(dnsCsv)
	if len(dnsAddrs) == 0 {
		dnsAddrs = []netip.Addr{netip.MustParseAddr("1.1.1.1")}
	}

	if mtu < 1280 || mtu > 1500 {
		mtu = 1280
	}

	tunDev, tnet, err := netstack.CreateNetTUN(localAddrs, dnsAddrs, mtu)
	if err != nil {
		return StartResult{Success: false, Error: fmt.Sprintf("netstack create failed: %v", err)}
	}

	wgDev := device.NewDevice(tunDev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, ""))
	if err := wgDev.IpcSet(uapiConfig); err != nil {
		wgDev.Close()
		return StartResult{Success: false, Error: fmt.Sprintf("wireguard config failed: %v", err)}
	}

	if err := wgDev.Up(); err != nil {
		wgDev.Close()
		return StartResult{Success: false, Error: fmt.Sprintf("wireguard up failed: %v", err)}
	}

	proxy, err := startLocalAuthProxy(tnet.DialContext)
	if err != nil {
		wgDev.Close()
		return StartResult{Success: false, Error: fmt.Sprintf("local proxy start failed: %v", err)}
	}

	c.dev = wgDev
	c.tnet = tnet
	c.proxy = proxy

	return StartResult{
		Success: true,
		Port:    proxy.Port(),
		Token:   proxy.Token(),
	}
}

func (c *tunnelController) Stop() StatusResult {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.stopLocked()
	return StatusResult{Running: false}
}

func (c *tunnelController) stopLocked() {
	if c.proxy != nil {
		c.proxy.Close()
		c.proxy = nil
	}
	if c.dev != nil {
		c.dev.Close()
		c.dev = nil
	}
	c.tnet = nil
}

func (c *tunnelController) Status() StatusResult {
	c.mu.Lock()
	dev := c.dev
	proxy := c.proxy
	c.mu.Unlock()

	if dev == nil || proxy == nil {
		return StatusResult{Running: false}
	}

	ipcRaw, err := dev.IpcGet()
	if err != nil {
		return StatusResult{
			Running:   true,
			ProxyPort: proxy.Port(),
			Error:     err.Error(),
		}
	}

	rx, tx, hs, ep := parseIpcStats(ipcRaw)
	return StatusResult{
		Running:               true,
		ProxyPort:             proxy.Port(),
		RxBytes:               rx,
		TxBytes:               tx,
		LastHandshakeEpochSec: hs,
		Endpoint:              ep,
	}
}

func (c *tunnelController) DialTest(hostPort string, timeoutMs int) DialTestResult {
	c.mu.Lock()
	tnet := c.tnet
	c.mu.Unlock()

	if tnet == nil {
		return DialTestResult{Success: false, Error: "WireGuard tunnel is not running"}
	}
	if timeoutMs <= 0 {
		timeoutMs = 5000
	}

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()

	start := time.Now()
	nc, err := tnet.DialContext(ctx, "tcp", hostPort)
	elapsed := time.Since(start).Milliseconds()
	if err != nil {
		return DialTestResult{
			Success:   false,
			LatencyMs: elapsed,
			Error:     err.Error(),
		}
	}
	_ = nc.Close()
	return DialTestResult{
		Success:   true,
		LatencyMs: elapsed,
	}
}

func parseAddrsCsv(csv string) ([]netip.Addr, error) {
	trimmed := strings.TrimSpace(csv)
	if trimmed == "" {
		return nil, nil
	}
	parts := strings.Split(trimmed, ",")
	out := make([]netip.Addr, 0, len(parts))
	for _, raw := range parts {
		token := strings.TrimSpace(raw)
		if token == "" {
			continue
		}
		if slash := strings.IndexByte(token, '/'); slash >= 0 {
			token = strings.TrimSpace(token[:slash])
		}
		addr, err := netip.ParseAddr(token)
		if err != nil {
			return nil, fmt.Errorf("invalid IP address %q: %w", token, err)
		}
		out = append(out, addr)
	}
	if len(out) == 0 {
		return nil, errors.New("no valid IP addresses provided")
	}
	return out, nil
}

func parseIpcStats(ipc string) (rxBytes int64, txBytes int64, lastHandshakeSec int64, endpoint string) {
	for _, line := range strings.Split(ipc, "\n") {
		idx := strings.IndexByte(line, '=')
		if idx <= 0 {
			continue
		}
		key := strings.TrimSpace(line[:idx])
		val := strings.TrimSpace(line[idx+1:])
		switch key {
		case "rx_bytes":
			if v, err := strconv.ParseInt(val, 10, 64); err == nil {
				rxBytes += v
			}
		case "tx_bytes":
			if v, err := strconv.ParseInt(val, 10, 64); err == nil {
				txBytes += v
			}
		case "last_handshake_time_sec":
			if v, err := strconv.ParseInt(val, 10, 64); err == nil && v > lastHandshakeSec {
				lastHandshakeSec = v
			}
		case "endpoint":
			if val != "" {
				endpoint = val
			}
		}
	}
	return rxBytes, txBytes, lastHandshakeSec, endpoint
}
