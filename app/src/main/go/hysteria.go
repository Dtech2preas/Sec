package hysteria

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"time"

	"github.com/apernet/hysteria/core/v2/client"
	"github.com/xjasonlyu/tun2socks/v2/engine"
)

// 1. Custom Address Wrapper (Preserves Port Range)
type HyAddr struct {
	str string
}

func (a *HyAddr) Network() string { return "udp" }
func (a *HyAddr) String() string  { return a.str }

// Global client reference
var hClient client.Client
var socksListener net.Listener

// 2. Start Function
// Now accepts fd (File Descriptor)
func Start(fd int, serverStr string, authStr string, obfsStr string) (err error) {
	// Panic Recovery for the main Start function
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("panic in Hysteria Core: %v", r)
		}
	}()

	serverAddr := &HyAddr{str: serverStr}

	// FIX: Use the specific client.TLSConfig struct (not crypto/tls)
	tlsConfig := client.TLSConfig{
		InsecureSkipVerify: true,
		ServerName:         "www.bing.com",
	}

	config := &client.Config{
		ServerAddr: serverAddr,
		Auth:       authStr,
		TLSConfig:  tlsConfig,
	}

	// Initialize Hysteria Client
	c, _, err := client.NewClient(config)
	if err != nil {
		return err
	}
	hClient = c

	// Start Local SOCKS5 Bridge in a safe goroutine
	go func() {
		defer func() {
			if r := recover(); r != nil {
				// Log panic? We can't easily log to Android here without a callback
				// but at least we prevent the crash if it's in this goroutine
			}
		}()
		startSocksBridge(c)
	}()

	// Wait a bit for SOCKS5 server to be ready
	time.Sleep(100 * time.Millisecond)

	// Configure and Start Tun2Socks Engine
	key := &engine.Key{
		MTU:      1280, // Match Android VPN MTU
		Device:   fmt.Sprintf("fd://%d", fd),
		Proxy:    "socks5://127.0.0.1:10808",
		LogLevel: "info",
	}
	engine.Insert(key)

	// Start Engine in a safe goroutine
	go func() {
		defer func() {
			if r := recover(); r != nil {
				// Prevent crash
			}
		}()
		engine.Start()
	}()

	return nil
}

// 3. Stop Function
func Stop() {
	// Stop Tun2Socks
	engine.Stop()

	// Close SOCKS5 Listener
	if socksListener != nil {
		socksListener.Close()
	}

	// Close Hysteria Client
	if hClient != nil {
		hClient.Close()
	}
}

// 4. Minimal SOCKS5 Bridge
func startSocksBridge(hyClient client.Client) {
	var err error
	socksListener, err = net.Listen("tcp", "127.0.0.1:10808")
	if err != nil {
		return
	}
	// defer socksListener.Close() // Do not defer close here, as we need it open

	for {
		conn, err := socksListener.Accept()
		if err != nil {
			// Listener closed or error
			return
		}
		go handleSocks5(conn, hyClient)
	}
}

func handleSocks5(conn net.Conn, hyClient client.Client) {
	defer func() {
		if r := recover(); r != nil {
			// Recover from panic in connection handler
		}
		conn.Close()
	}()

	// 1. Version and Auth Negotiation
	buf := make([]byte, 256)
	// Read VER, NMETHODS, METHODS
	if _, err := io.ReadAtLeast(conn, buf[:2], 2); err != nil {
		return
	}
	ver := buf[0]
	nMethods := int(buf[1])
	if ver != 5 {
		return
	}
	if _, err := io.ReadAtLeast(conn, buf[:nMethods], nMethods); err != nil {
		return
	}
	// Reply: VER=5, METHOD=0 (No Auth)
	conn.Write([]byte{0x05, 0x00})

	// 2. Request Details
	// Read VER, CMD, RSV, ATYP
	if _, err := io.ReadFull(conn, buf[:4]); err != nil {
		return
	}
	cmd := buf[1]
	if cmd != 1 { // CONNECT only
		return
	}
	atyp := buf[3]
	var addr string
	switch atyp {
	case 1: // IPv4
		if _, err := io.ReadFull(conn, buf[:4]); err != nil {
			return
		}
		addr = net.IP(buf[:4]).String()
	case 3: // Domain
		if _, err := io.ReadFull(conn, buf[:1]); err != nil {
			return
		}
		addrLen := int(buf[0])
		if _, err := io.ReadFull(conn, buf[:addrLen]); err != nil {
			return
		}
		addr = string(buf[:addrLen])
	case 4: // IPv6
		if _, err := io.ReadFull(conn, buf[:16]); err != nil {
			return
		}
		addr = net.IP(buf[:16]).String()
	default:
		return
	}
	// Read Port
	if _, err := io.ReadFull(conn, buf[:2]); err != nil {
		return
	}
	port := binary.BigEndian.Uint16(buf[:2])
	dest := fmt.Sprintf("%s:%d", addr, port)

	// 3. Connect via Hysteria
	destConn, err := hyClient.TCP(dest)
	if err != nil {
		// Reply with failure
		conn.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer destConn.Close()

	// Reply with success
	// BND.ADDR and BND.PORT (zeros are fine)
	conn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0})

	// 4. Pipe Data
	go func() {
		defer func() {
			if r := recover(); r != nil {
				// Prevent crash
			}
		}()
		io.Copy(conn, destConn)
	}()
	io.Copy(destConn, conn)
}
