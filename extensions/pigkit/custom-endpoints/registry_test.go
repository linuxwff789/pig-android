package customendpoints

import "testing"

func TestCachedModelsFillsMissingSizes(t *testing.T) {
	// Older relays cached the endpoint listing as {id,name}. pig reads a
	// missing contextWindow as 0 and then asks for a single output token, so
	// the extension must normalize the cache before registering it.
	got := cachedModels([]any{
		map[string]any{"id": "m1", "name": "m1"},
		map[string]any{"id": "m2", "name": "m2", "contextWindow": 0.0, "maxTokens": 0.0},
		map[string]any{"id": "m3", "name": "m3", "contextWindow": 1000000.0, "maxTokens": 4096.0},
	}, nil)
	if len(got) != 3 {
		t.Fatalf("models: %#v", got)
	}
	for i, want := range [][2]float64{
		{defaultCtxWindow, defaultMaxTokens},
		{defaultCtxWindow, defaultMaxTokens},
		{1000000, 4096},
	} {
		model := got[i].(map[string]any)
		if model["contextWindow"] != want[0] || model["maxTokens"] != want[1] {
			t.Fatalf("model %d: %#v", i, model)
		}
	}
}
