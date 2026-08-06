package gateway

import (
	"encoding/json"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/gorilla/websocket"
)

func TestProvidersAPIListsOnlyNonSecretMetadata(t *testing.T) {
	_, server := startTestGateway(t, t.TempDir())
	endpoint := server.URL + "/api/providers"

	unauthorized, err := http.Get(endpoint)
	if err != nil {
		t.Fatalf("get unauthorized providers: %v", err)
	}
	_ = unauthorized.Body.Close()
	if unauthorized.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unauthorized status = %d, want 401", unauthorized.StatusCode)
	}

	request, err := http.NewRequest(http.MethodGet, endpoint, nil)
	if err != nil {
		t.Fatalf("create providers request: %v", err)
	}
	request.Header.Set("Authorization", "Bearer "+testToken)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("get providers: %v", err)
	}
	defer response.Body.Close()
	var payload providersResponse
	if err := json.NewDecoder(response.Body).Decode(&payload); err != nil {
		t.Fatalf("decode providers: %v", err)
	}
	if response.StatusCode != http.StatusOK {
		t.Fatalf("providers status = %d, want 200", response.StatusCode)
	}
	if len(payload.Providers) != 2 {
		t.Fatalf("providers = %#v, want two", payload.Providers)
	}
	openAI := payload.Providers[0]
	if openAI.ID != "openai" || openAI.Configured || len(openAI.AuthMethods) != 1 || len(openAI.Models) != 1 {
		t.Fatalf("OpenAI provider = %#v", openAI)
	}
	codex := payload.Providers[1]
	if codex.ID != "openai-codex" || !codex.Configured || codex.StoredAuthType != "oauth" {
		t.Fatalf("Codex provider = %#v", codex)
	}
}

func TestProviderLogoutInvalidatesCapabilities(t *testing.T) {
	probeLog := filepath.Join(t.TempDir(), "capability-probes.log")
	t.Setenv("OHPI_TEST_PROBE_LOG", probeLog)
	_, server := startTestGateway(t, t.TempDir())
	workDir := t.TempDir()
	capabilitiesURL := server.URL + "/api/capabilities?work_dir=" + url.QueryEscape(workDir)

	getAuthenticated(t, capabilitiesURL)
	request, err := http.NewRequest(http.MethodDelete, server.URL+"/api/providers/openai/credential", nil)
	if err != nil {
		t.Fatalf("create logout request: %v", err)
	}
	request.Header.Set("Authorization", "Bearer "+testToken)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("logout provider: %v", err)
	}
	_ = response.Body.Close()
	if response.StatusCode != http.StatusNoContent {
		t.Fatalf("logout status = %d, want 204", response.StatusCode)
	}
	getAuthenticated(t, capabilitiesURL)

	data, err := os.ReadFile(probeLog)
	if err != nil {
		t.Fatalf("read capability probe log: %v", err)
	}
	if starts := strings.Fields(string(data)); len(starts) != 2 {
		t.Fatalf("capability probes after logout = %d, want 2", len(starts))
	}
}

func TestProviderAuthWebSocketForwardsOnlyDialogResponses(t *testing.T) {
	_, server := startTestGateway(t, t.TempDir())
	endpoint := "ws" + strings.TrimPrefix(server.URL, "http") +
		"/api/provider-auth?token=" + url.QueryEscape(testToken) +
		"&provider_id=openai&auth_type=api_key"
	connection, response, err := websocket.DefaultDialer.Dial(endpoint, nil)
	if err != nil {
		t.Fatalf("dial provider auth: %v (status %d)", err, responseStatus(response))
	}
	defer connection.Close()

	ready := readEvent(t, connection)
	if ready.string("type") != "ohpi_provider" || ready.string("event") != "ready" {
		t.Fatalf("provider ready event = %#v", ready)
	}
	prompt := readEvent(t, connection)
	if prompt.string("event") != "prompt" || prompt.string("kind") != "secret" ||
		prompt.string("id") != "provider-prompt" {
		t.Fatalf("provider prompt = %#v", prompt)
	}
	writeJSON(t, connection, map[string]any{
		"type": "prompt", "id": "malicious", "message": "must not reach pi",
	})
	writeJSON(t, connection, map[string]any{
		"type": "extension_ui_response", "id": "provider-prompt", "value": "test-key",
	})
	complete := readEvent(t, connection)
	if complete.string("event") != "complete" || complete.string("action") != "login" ||
		complete.string("provider_id") != "openai" {
		t.Fatalf("provider completion = %#v", complete)
	}
}

func TestProviderAuthOutputMapsOAuthEventsAndIgnoresOtherUI(t *testing.T) {
	devicePayload, err := json.Marshal(map[string]any{
		"event": "device_code", "userCode": "ABCD-EFGH",
		"verificationUri": "https://example.test/device", "intervalSeconds": 5,
	})
	if err != nil {
		t.Fatalf("encode device event: %v", err)
	}
	line, err := json.Marshal(map[string]any{
		"type": "extension_ui_request", "method": "notify",
		"message": providerEventPrefix + string(devicePayload),
	})
	if err != nil {
		t.Fatalf("encode RPC event: %v", err)
	}
	event, terminal, found := providerAuthOutput(line)
	if !found || terminal || event.Event != "device_code" || event.UserCode != "ABCD-EFGH" ||
		event.VerifyURI != "https://example.test/device" || event.Interval != 5 {
		t.Fatalf("mapped device event = %#v, terminal=%v, found=%v", event, terminal, found)
	}

	unrelated, err := json.Marshal(map[string]any{
		"type": "extension_ui_request", "method": "input", "id": "foreign",
		"title": "Third-party extension prompt",
	})
	if err != nil {
		t.Fatalf("encode unrelated UI event: %v", err)
	}
	if event, terminal, found := providerAuthOutput(unrelated); found || terminal || event.Event != "" {
		t.Fatalf("unrelated extension UI leaked through: %#v, terminal=%v, found=%v", event, terminal, found)
	}
}

func getAuthenticated(t *testing.T, endpoint string) {
	t.Helper()
	request, err := http.NewRequest(http.MethodGet, endpoint, nil)
	if err != nil {
		t.Fatalf("create authenticated request: %v", err)
	}
	request.Header.Set("Authorization", "Bearer "+testToken)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("get %s: %v", endpoint, err)
	}
	_ = response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("GET %s status = %d, want 200", endpoint, response.StatusCode)
	}
}
