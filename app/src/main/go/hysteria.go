package hysteria

import (
	"github.com/apernet/hysteria/core/v2/client"
)

// 1. Custom Address Wrapper (Preserves Port Range)
type HyAddr struct {
	str string
}

func (a *HyAddr) Network() string { return "udp" }
func (a *HyAddr) String() string  { return a.str }

// Global client reference
var hClient client.Client

// 2. Start Function
// I have renamed it to 'Start' to match your Android expectations.
func Start(serverStr string, authStr string, obfsStr string) error {
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

	// FIX: Handle 3 return values
	c, _, err := client.NewClient(config)
	if err != nil {
		return err
	}
	hClient = c

	// FIX: Return nil immediately (Client is active upon creation)
	return nil
}

// 3. Stop Function
func Stop() {
	if hClient != nil {
		hClient.Close()
	}
}
