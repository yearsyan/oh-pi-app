package gateway

import (
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func TestDetectWorkspaceTechnologyPrioritizesFrameworkAndPreservesEcosystems(t *testing.T) {
	directory := t.TempDir()
	writeTechnologyMarker(t, directory, "vite.config.ts", "export default {}\n")
	writeTechnologyMarker(t, directory, "tsconfig.json", `{}`)
	writeTechnologyMarker(
		t,
		directory,
		"package.json",
		`{"devDependencies":{"vite":"latest","typescript":"latest"}}`,
	)

	detected := detectWorkspaceTechnology(directory)
	if detected.Primary != "vite" {
		t.Fatalf("primary technology = %q, want vite", detected.Primary)
	}
	want := []string{"vite", "typescript", "javascript"}
	if !reflect.DeepEqual(detected.Technologies, want) {
		t.Fatalf("technologies = %v, want %v", detected.Technologies, want)
	}
}

func TestDetectWorkspaceTechnologyUsesSpecificPriority(t *testing.T) {
	tests := []struct {
		name    string
		markers map[string]string
		want    []string
	}{
		{
			name: "next before vite",
			markers: map[string]string{
				"next.config.ts": "export default {}\n",
				"vite.config.ts": "export default {}\n",
				"tsconfig.json":  `{}`,
				"package.json":   `{"dependencies":{"next":"latest","react":"latest"}}`,
			},
			want: []string{"nextjs", "vite", "react", "typescript", "javascript"},
		},
		{
			name: "rust before go",
			markers: map[string]string{
				"Cargo.toml": "[package]\nname = \"example\"\n",
				"go.mod":     "module example.test/project\n",
			},
			want: []string{"rust", "go"},
		},
		{
			name: "flutter before dart",
			markers: map[string]string{
				"pubspec.yaml": "environment:\n  sdk: flutter\n",
			},
			want: []string{"flutter", "dart"},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			directory := t.TempDir()
			for name, contents := range test.markers {
				writeTechnologyMarker(t, directory, name, contents)
			}
			detected := detectWorkspaceTechnology(directory)
			if detected.Primary != test.want[0] || !reflect.DeepEqual(detected.Technologies, test.want) {
				t.Fatalf("detection = %#v, want primary %q and %v", detected, test.want[0], test.want)
			}
		})
	}
}

func TestDetectWorkspaceTechnologyReturnsEmptyListForGenericDirectory(t *testing.T) {
	detected := detectWorkspaceTechnology(t.TempDir())
	if detected.Primary != "generic" || detected.Technologies == nil || len(detected.Technologies) != 0 {
		t.Fatalf("generic detection = %#v", detected)
	}
}

func writeTechnologyMarker(t *testing.T, directory, name, contents string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(directory, name), []byte(contents), 0o600); err != nil {
		t.Fatal(err)
	}
}
