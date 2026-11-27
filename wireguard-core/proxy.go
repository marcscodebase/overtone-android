package main

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

type dialContextFunc func(ctx context.Context, network, addr string) (net.Conn, error)

// localAuthProxy is a loopback-only (127.0.0.1:0) authenticated HTTP/HTTPS proxy
// that dials exclusively through the userspace WireGuard netstack.
type localAuthProxy struct {
	listener     net.Listener
	server       *http.Server
	transport    *http.Transport
	expectedAuth []byte
	port         int
	token        string
	closeOnce    sync.Once

	connMu      sync.Mutex
	closed      bool
	activeConns map[net.Conn]struct{}
}

func startLocalAuthProxy(dialFn dialContextFunc) (*localAuthProxy, error) {
	tokenBytes := make([]byte, 32)
	if _, err := rand.Read(tokenBytes); err != nil {
		return nil, err
	}
	token := base64.RawURLEncoding.EncodeToString(tokenBytes)
	expectedHeader := "Basic " + base64.StdEncoding.EncodeToString([]byte("wg:"+token))

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, err
	}
	tcpAddr, ok := ln.Addr().(*net.TCPAddr)
	if !ok {
		_ = ln.Close()
		return nil, net.InvalidAddrError("expected loopback TCP address")
	}

	transport := &http.Transport{
		DialContext:           dialFn,
		ForceAttemptHTTP2:     false,
		MaxIdleConns:          32,
		MaxIdleConnsPerHost:   16,
		IdleConnTimeout:       60 * time.Second,
		ResponseHeaderTimeout: 30 * time.Second,
		ExpectContinueTimeout: 2 * time.Second,
		DisableCompression:    true,
	}

	p := &localAuthProxy{
		listener:     ln,
		transport:    transport,
		expectedAuth: []byte(expectedHeader),
		port:         tcpAddr.Port,
		token:        token,
		activeConns:  make(map[net.Conn]struct{}),
	}

	p.server = &http.Server{
		Handler:           p,
		ReadHeaderTimeout: 15 * time.Second,
	}

	go func() {
		_ = p.server.Serve(ln)
	}()

	return p, nil
}

func (p *localAuthProxy) Port() int {
	return p.port
}

func (p *localAuthProxy) Token() string {
	return p.token
}

func (p *localAuthProxy) trackConns(c1, c2 net.Conn) bool {
	p.connMu.Lock()
	defer p.connMu.Unlock()
	if p.closed {
		return false
	}
	p.activeConns[c1] = struct{}{}
	p.activeConns[c2] = struct{}{}
	return true
}

func (p *localAuthProxy) untrackConns(c1, c2 net.Conn) {
	p.connMu.Lock()
	delete(p.activeConns, c1)
	delete(p.activeConns, c2)
	p.connMu.Unlock()
}

func (p *localAuthProxy) Close() {
	p.closeOnce.Do(func() {
		p.connMu.Lock()
		p.closed = true
		conns := make([]net.Conn, 0, len(p.activeConns))
		for c := range p.activeConns {
			conns = append(conns, c)
		}
		p.activeConns = make(map[net.Conn]struct{})
		p.connMu.Unlock()

		for _, c := range conns {
			_ = c.Close()
		}
		_ = p.server.Close()
		_ = p.listener.Close()
		p.transport.CloseIdleConnections()
	})
}

func (p *localAuthProxy) isAuthorized(r *http.Request) bool {
	provided := []byte(r.Header.Get("Proxy-Authorization"))
	if len(provided) != len(p.expectedAuth) {
		return false
	}
	return subtle.ConstantTimeCompare(provided, p.expectedAuth) == 1
}

func (p *localAuthProxy) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if !p.isAuthorized(r) {
		w.Header().Set("Proxy-Authenticate", `Basic realm="overtone-wg"`)
		http.Error(w, "Proxy authentication required", http.StatusProxyAuthRequired)
		return
	}

	if r.Method == http.MethodConnect {
		p.handleConnect(w, r)
		return
	}
	p.handleHTTP(w, r)
}

func (p *localAuthProxy) handleConnect(w http.ResponseWriter, r *http.Request) {
	dialCtx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()

	upstream, err := p.transport.DialContext(dialCtx, "tcp", r.Host)
	if err != nil {
		http.Error(w, "WireGuard tunnel dial failed: "+err.Error(), http.StatusBadGateway)
		return
	}

	hijacker, ok := w.(http.Hijacker)
	if !ok {
		_ = upstream.Close()
		http.Error(w, "Hijacking not supported", http.StatusInternalServerError)
		return
	}

	clientConn, bufRW, err := hijacker.Hijack()
	if err != nil {
		_ = upstream.Close()
		return
	}

	if !p.trackConns(clientConn, upstream) {
		_ = clientConn.Close()
		_ = upstream.Close()
		return
	}
	defer p.untrackConns(clientConn, upstream)

	if _, err := clientConn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n")); err != nil {
		_ = clientConn.Close()
		_ = upstream.Close()
		return
	}

	var wg sync.WaitGroup
	wg.Add(2)

	// Client -> Upstream (flush any buffered bytes from Hijack first)
	go func() {
		defer wg.Done()
		if bufRW != nil && bufRW.Reader.Buffered() > 0 {
			if _, err := io.CopyN(upstream, bufRW, int64(bufRW.Reader.Buffered())); err != nil {
				_ = upstream.Close()
				_ = clientConn.Close()
				return
			}
		}
		if _, err := io.Copy(upstream, clientConn); err != nil {
			_ = upstream.Close()
			_ = clientConn.Close()
			return
		}
		closeWrite(upstream)
	}()

	// Upstream -> Client
	go func() {
		defer wg.Done()
		if _, err := io.Copy(clientConn, upstream); err != nil {
			_ = clientConn.Close()
			_ = upstream.Close()
			return
		}
		closeWrite(clientConn)
	}()

	wg.Wait()
	_ = upstream.Close()
	_ = clientConn.Close()
}

func (p *localAuthProxy) handleHTTP(w http.ResponseWriter, r *http.Request) {
	outReq := r.Clone(r.Context())
	outReq.RequestURI = ""
	if outReq.URL.Scheme == "" {
		outReq.URL.Scheme = "http"
	}
	if outReq.URL.Host == "" {
		outReq.URL.Host = r.Host
	}

	stripHopByHopHeaders(outReq.Header)

	resp, err := p.transport.RoundTrip(outReq)
	if err != nil {
		http.Error(w, "WireGuard tunnel request failed: "+err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	respHeaders := resp.Header.Clone()
	stripHopByHopHeaders(respHeaders)
	dstHeader := w.Header()
	for k, vals := range respHeaders {
		for _, v := range vals {
			dstHeader.Add(k, v)
		}
	}
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, resp.Body)
}

func closeWrite(c net.Conn) {
	type halfCloser interface {
		CloseWrite() error
	}
	if hc, ok := c.(halfCloser); ok {
		_ = hc.CloseWrite()
	}
}

func stripHopByHopHeaders(h http.Header) {
	for _, connHeader := range h.Values("Connection") {
		for _, field := range strings.Split(connHeader, ",") {
			if f := strings.TrimSpace(field); f != "" {
				h.Del(f)
			}
		}
	}
	for _, key := range []string{
		"Connection",
		"Keep-Alive",
		"Proxy-Authenticate",
		"Proxy-Authorization",
		"Proxy-Connection",
		"Te",
		"Trailer",
		"Transfer-Encoding",
		"Upgrade",
	} {
		h.Del(key)
	}
}
