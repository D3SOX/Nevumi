package main

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strings"
	"time"
)

// relays maps a music server origin to its loopback relay. Guarded by lock.
var relays = map[string]*relayServer{}

type relayServer struct {
	url    string
	server *http.Server
}

// relay serves origin over the tailnet at a loopback URL with a secret path prefix.
// Players fetch stream URLs themselves, so a plain HTTP address is the one route every player supports.
func relay(origin string) (string, error) {
	target, err := url.Parse(origin)
	if err != nil || (target.Scheme != "http" && target.Scheme != "https") || target.Host == "" {
		return "", errors.New("Enter the server's HTTP or HTTPS address")
	}
	lock.Lock()
	defer lock.Unlock()
	if server == nil {
		return "", errNotConnected
	}
	if existing, ok := relays[origin]; ok {
		return existing.url, nil
	}
	secret := make([]byte, 32)
	if _, err := rand.Read(secret); err != nil {
		return "", err
	}
	token := hex.EncodeToString(secret)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	node := server
	transport := &http.Transport{
		// Each connection waits for the node with its own deadline, so creating the relay never blocks.
		// The deadline covers only connection setup, not the streamed response.
		DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
			defer cancel()
			if _, err := node.Up(ctx); err != nil {
				return nil, errNotConnected
			}
			return node.Dial(ctx, network, address)
		},
		ForceAttemptHTTP2:   true,
		IdleConnTimeout:     90 * time.Second,
		TLSHandshakeTimeout: 10 * time.Second,
	}
	proxy := &httputil.ReverseProxy{
		Rewrite:       func(request *httputil.ProxyRequest) { request.SetURL(target) },
		Transport:     transport,
		FlushInterval: -1,
	}
	handler := http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		first, rest, _ := strings.Cut(strings.TrimPrefix(request.URL.Path, "/"), "/")
		if subtle.ConstantTimeCompare([]byte(first), []byte(token)) != 1 {
			http.NotFound(writer, request)
			return
		}
		request.URL.Path = "/" + rest
		request.URL.RawPath = ""
		proxy.ServeHTTP(writer, request)
	})
	relayed := &relayServer{"http://" + listener.Addr().String() + "/" + token, &http.Server{Handler: handler, ReadHeaderTimeout: 10 * time.Second}}
	go relayed.server.Serve(listener)
	relays[origin] = relayed
	return relayed.url, nil
}

// closeRelays stops every relay. The caller holds lock.
func closeRelays() {
	for origin, relayed := range relays {
		relayed.server.Close()
		delete(relays, origin)
	}
}
