//go:build windows

package gateway

// Windows does not allow os.File.Sync on a directory handle opened through
// os.Open. The file itself is flushed before each atomic rename, so directory
// syncing is intentionally a no-op on Windows.
func syncDirectory(string) error {
	return nil
}
