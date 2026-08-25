package gateway

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

type technologyDetection struct {
	Primary      string
	Technologies []string
}

// detectWorkspaceTechnology intentionally checks specific frameworks before
// their generic language manifests. For example, a Vite TypeScript workspace
// is presented as Vite while still advertising TypeScript and JavaScript.
func detectWorkspaceTechnology(directory string) technologyDetection {
	entries, err := os.ReadDir(directory)
	if err != nil {
		return technologyDetection{Primary: "generic", Technologies: []string{}}
	}
	files := make(map[string]struct{}, len(entries))
	for _, entry := range entries {
		files[strings.ToLower(entry.Name())] = struct{}{}
	}
	has := func(names ...string) bool {
		for _, name := range names {
			if _, ok := files[strings.ToLower(name)]; ok {
				return true
			}
		}
		return false
	}
	hasSuffix := func(suffixes ...string) bool {
		for name := range files {
			for _, suffix := range suffixes {
				if strings.HasSuffix(name, suffix) {
					return true
				}
			}
		}
		return false
	}

	packages := readPackageNames(filepath.Join(directory, "package.json"))
	hasPackage := func(names ...string) bool {
		for _, name := range names {
			if _, ok := packages[name]; ok {
				return true
			}
		}
		return false
	}

	ordered := make([]string, 0, 8)
	add := func(id string, present bool) {
		if !present {
			return
		}
		for _, existing := range ordered {
			if existing == id {
				return
			}
		}
		ordered = append(ordered, id)
	}

	// Frameworks and build systems are more informative than their language.
	add("nextjs", has("next.config.js", "next.config.mjs", "next.config.ts") || hasPackage("next"))
	add("nuxt", has("nuxt.config.js", "nuxt.config.ts") || hasPackage("nuxt"))
	add("svelte", has("svelte.config.js", "svelte.config.ts") || hasPackage("svelte", "@sveltejs/kit"))
	add("angular", has("angular.json") || hasPackage("@angular/core"))
	add("vite", has("vite.config.js", "vite.config.mjs", "vite.config.cjs", "vite.config.ts", "vite.config.mts") || hasPackage("vite"))
	add("vue", hasPackage("vue"))
	add("react", hasPackage("react", "react-native"))
	add("flutter", has("pubspec.yaml") && fileContains(filepath.Join(directory, "pubspec.yaml"), "sdk: flutter"))

	// Language and ecosystem markers, in specificity order.
	add("rust", has("cargo.toml"))
	add("go", has("go.mod"))
	add("kotlin", has("settings.gradle.kts", "build.gradle.kts"))
	add("swift", has("package.swift"))
	add("dotnet", hasSuffix(".sln", ".csproj", ".fsproj", ".vbproj"))
	add("python", has("pyproject.toml", "requirements.txt", "setup.py", "setup.cfg", "pipfile", "poetry.lock"))
	add("typescript", has("tsconfig.json") || hasPackage("typescript"))
	add("javascript", has("package.json"))
	add("php", has("composer.json"))
	add("ruby", has("gemfile", "rakefile"))
	add("elixir", has("mix.exs"))
	add("dart", has("pubspec.yaml"))
	add("cpp", has("cmakelists.txt", "meson.build") || hasSuffix(".cpp", ".cc", ".cxx"))
	add("java", has("pom.xml", "settings.gradle", "build.gradle"))

	if len(ordered) == 0 {
		return technologyDetection{Primary: "generic", Technologies: []string{}}
	}
	return technologyDetection{Primary: ordered[0], Technologies: ordered}
}

func readPackageNames(path string) map[string]struct{} {
	info, err := os.Stat(path)
	if err != nil || !info.Mode().IsRegular() || info.Size() > 1<<20 {
		return nil
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil
	}
	var manifest struct {
		Dependencies         map[string]json.RawMessage `json:"dependencies"`
		DevDependencies      map[string]json.RawMessage `json:"devDependencies"`
		PeerDependencies     map[string]json.RawMessage `json:"peerDependencies"`
		OptionalDependencies map[string]json.RawMessage `json:"optionalDependencies"`
	}
	if json.Unmarshal(data, &manifest) != nil {
		return nil
	}
	names := make([]string, 0)
	for _, group := range []map[string]json.RawMessage{
		manifest.Dependencies,
		manifest.DevDependencies,
		manifest.PeerDependencies,
		manifest.OptionalDependencies,
	} {
		for name := range group {
			names = append(names, strings.ToLower(name))
		}
	}
	sort.Strings(names)
	result := make(map[string]struct{}, len(names))
	for _, name := range names {
		result[name] = struct{}{}
	}
	return result
}

func fileContains(path, needle string) bool {
	info, err := os.Stat(path)
	if err != nil || !info.Mode().IsRegular() || info.Size() > 1<<20 {
		return false
	}
	data, err := os.ReadFile(path)
	return err == nil && strings.Contains(strings.ToLower(string(data)), strings.ToLower(needle))
}
