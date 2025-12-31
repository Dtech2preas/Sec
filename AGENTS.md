# Agent Instructions - Hysteria VPN Integration

This project has been refactored to use Hysteria V2 (Go backend) instead of SSH/TLS.

## Required Action: Compile Go Native Library

Because the Android environment requires the compiled Go library (AAR) which cannot be generated in this sandbox, the user (or build system) MUST compile the Go code manually.

### Steps to Compile:
1.  **Install Go 1.21+** and **Gomobile**.
    ```bash
    go install golang.org/x/mobile/cmd/gomobile@latest
    gomobile init
    ```
2.  **Navigate to the Go Source**:
    ```bash
    cd app/src/main/go
    ```
3.  **Download Dependencies**:
    ```bash
    go mod tidy
    ```

4.  **Bind the Library**:
    Run the following command to generate the `.aar` file. We name the package `hysteria` so the Java class will be `hysteria.Hysteria`.
    ```bash
    gomobile bind -target=android -o ../../../libs/hysteria.aar .
    ```
    *Note: Ensure you are in `app/src/main/go` when running this.*

5.  **Verify**:
    Check that `app/libs/hysteria.aar` exists.

6.  **Uncomment Code**:
    In `app/src/main/java/com/dtech/vpn/DTechVpnService.kt`, uncomment the lines that reference `hysteria.Hysteria`:
    ```kotlin
    // import hysteria.Hysteria
    ...
    // hysteria.Hysteria.start(fd, serverStr, auth, "")
    ...
    // hysteria.Hysteria.stop()
    ```

## Project Structure
- `app/src/main/go/hysteria.go`: The Go source code bridging Android VPN FD (via Tun2Socks) to Hysteria Core.
- `app/src/main/go/go.mod`: Go module definition.
- `app/libs`: Directory where the compiled `.aar` must be placed.
