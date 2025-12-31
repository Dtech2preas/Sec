package hysteria

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strings"

	"github.com/apernet/hysteria/core/v2/client"
	"github.com/xjasonlyu/tun2socks/v2/engine"
)

var (
	activeClient client.Client
	ctxCancel    context.CancelFunc
)

// Start initiates the Hysteria client and bridges it to the Android VPN TUN interface.
// fd: File descriptor from VpnService.Builder().establish().getFd() (Android uses int)
// server: "host:port" or "host:1-65535"
// auth: "user:password"
// obfs: Obfuscation password (optional)
// We use int32 for fd to ensure it maps to Java 'int', avoiding potential long/int mismatch.
func Start(fd int32, server string, auth string, obfs string) error {
	if activeClient != nil {
		Stop()
	}

	// 1. Configuration
	if server == "" || auth == "" {
		return errors.New("server and auth are required")
	}

	user, pass, found := strings.Cut(auth, ":")
	if !found {
		return errors.New("auth must be in user:password format")
	}

	// Define Local SOCKS5 Port
	socksPort := 10808
	socksAddr := fmt.Sprintf("127.0.0.1:%d", socksPort)

	// Prepare Hysteria Config
	hyConfig := &client.Config{
		ServerAddr: server,
		Auth:       user + ":" + pass,
		TLS: client.TLSConfig{
			Insecure: true,
			SNI:      "google.com", // Default SNI
		},
		Obfs: client.ObfsConfig{
			Password: obfs,
		},
		SOCKS5: &client.SOCKS5Config{
			Listen: socksAddr, // Bind directly to fixed port
		},
		FastOpen: true,
	}

	c, err := client.NewClient(hyConfig)
	if err != nil {
		return fmt.Errorf("failed to create hysteria client: %v", err)
	}

	// 2. Start Hysteria Client
	ctx, cancel := context.WithCancel(context.Background())
	ctxCancel = cancel

	err = c.Start(ctx)
	if err != nil {
		cancel()
		return fmt.Errorf("failed to start hysteria client: %v", err)
	}
	activeClient = c

	// 3. Start Tun2Socks Engine
	// We pass the FD to tun2socks using the fd:// scheme.
	key := &engine.Key{
		Device:   fmt.Sprintf("fd://%d", fd),
		Proxy:    fmt.Sprintf("socks5://%s", socksAddr),
		LogLevel: "info",
		MTU:      1280, // Match the Android side MTU
	}

	// Insert and Start
	engine.Insert(key)
	if err := engine.Start(); err != nil {
		Stop()
		return fmt.Errorf("failed to start tun2socks: %v", err)
	}

	return nil
}

// Stop disconnects the client.
func Stop() {
	// Stop Tun2Socks
	engine.Stop()

	// Stop Hysteria
	if ctxCancel != nil {
		ctxCancel()
		ctxCancel = nil
	}
	if activeClient != nil {
		activeClient.Close()
		activeClient = nil
	}
}
