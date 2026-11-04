// Image payloads are the only part of a transcript that is both large and
// repeated. The session file keeps them inline as base64 (pig's format), but a
// client that asks for `imageMode=ref` gets a content hash instead of the
// bytes: the frame stays in the KB range, and the picture is fetched once and
// cached by hash in `~/.pig/cache/images` (never backed up, recreated on
// demand). Old clients keep receiving base64, and any failure to store a ref
// falls back to the original block, so no transcript stops rendering.
package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
)

const (
	imageCacheDirName = "images"
	// One image block above this is left inline: a client that cannot fetch
	// refs must still be able to display it.
	maxImageRefBytes = 8 << 20
	// A single relay_image_get answers at most this many pictures.
	maxImageRefsPerRequest = 8
)

func (s *session) imageCacheDir() string {
	return filepath.Join(s.cwd, ".pig", "cache", imageCacheDirName)
}

// validImageRef only accepts the hex digest this relay produces. The value
// becomes a file name, so anything else (slashes, dots) must never reach it.
func validImageRef(ref string) bool {
	if len(ref) != 64 {
		return false
	}
	for i := 0; i < len(ref); i++ {
		c := ref[i]
		if (c < '0' || c > '9') && (c < 'a' || c > 'f') {
			return false
		}
	}
	return true
}

// storeImage decodes one base64 block, writes the bytes under their content
// hash (a second copy is a no-op) and returns the replacement block.
func (s *session) storeImage(data, mimeType string) (map[string]any, bool) {
	raw, err := base64.StdEncoding.DecodeString(data)
	if err != nil || len(raw) == 0 || len(raw) > maxImageRefBytes {
		return nil, false
	}
	sum := sha256.Sum256(raw)
	ref := hex.EncodeToString(sum[:])
	dir := s.imageCacheDir()
	path := filepath.Join(dir, ref)
	if _, err := os.Stat(path); err != nil {
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return nil, false
		}
		// writeFileAtomic is the same temp+rename helper settings use; a
		// half-written picture would be served as a broken image forever.
		if err := writeFileAtomic(path, raw); err != nil {
			return nil, false
		}
	}
	if mimeType == "" {
		mimeType = "image/png"
	}
	return map[string]any{
		"type":     "image",
		"ref":      ref,
		"mimeType": mimeType,
		"bytes":    len(raw),
	}, true
}

// rewriteHistoryImages replaces inline base64 image blocks with refs. Only the
// window the client asked for is touched; the session file is never modified.
func (s *session) rewriteHistoryImages(messages []json.RawMessage) []json.RawMessage {
	out := make([]json.RawMessage, len(messages))
	for i, message := range messages {
		if !bytes.Contains(message, []byte(`"type":"image"`)) &&
			!bytes.Contains(message, []byte(`"type": "image"`)) {
			out[i] = message
			continue
		}
		out[i] = s.rewriteMessageImages(message)
	}
	return out
}

func (s *session) rewriteMessageImages(message json.RawMessage) json.RawMessage {
	// RawMessage per key keeps every field this build does not model (usage,
	// timestamps, odd provider extras) byte-for-byte, unlike map[string]any.
	var doc map[string]json.RawMessage
	if json.Unmarshal(message, &doc) != nil {
		return message
	}
	rawContent, ok := doc["content"]
	if !ok {
		return message
	}
	var content []json.RawMessage
	if json.Unmarshal(rawContent, &content) != nil {
		return message
	}
	changed := false
	for i, rawBlock := range content {
		var block struct {
			Type     string `json:"type"`
			Data     string `json:"data"`
			MimeType string `json:"mimeType"`
		}
		if json.Unmarshal(rawBlock, &block) != nil || block.Type != "image" || block.Data == "" {
			continue
		}
		ref, ok := s.storeImage(block.Data, block.MimeType)
		if !ok {
			continue
		}
		encoded, err := json.Marshal(ref)
		if err != nil {
			continue
		}
		content[i] = encoded
		changed = true
	}
	if !changed {
		return message
	}
	encodedContent, err := json.Marshal(content)
	if err != nil {
		return message
	}
	doc["content"] = encodedContent
	out, err := json.Marshal(doc)
	if err != nil {
		return message
	}
	return out
}

// sendImages answers relay_image_get. Each ref is a file the relay wrote
// itself; refs are re-validated so a client cannot read arbitrary paths.
func (s *session) sendImages(id any, refs []string) {
	if len(refs) > maxImageRefsPerRequest {
		refs = refs[:maxImageRefsPerRequest]
	}
	images := make([]frame, 0, len(refs))
	for _, ref := range refs {
		if !validImageRef(ref) {
			continue
		}
		path := filepath.Join(s.imageCacheDir(), ref)
		info, err := os.Stat(path)
		if err != nil || info.Size() <= 0 || info.Size() > maxImageRefBytes {
			continue
		}
		raw, err := os.ReadFile(path)
		if err != nil {
			continue
		}
		images = append(images, frame{
			"ref":  ref,
			"data": base64.StdEncoding.EncodeToString(raw),
		})
	}
	_ = s.send(frame{"type": "relay_image_data", "id": id, "images": images})
}

// imageCacheBytes reports what the ref cache costs on disk (diagnostics).
func (s *session) imageCacheBytes() int64 {
	var total int64
	entries, err := os.ReadDir(s.imageCacheDir())
	if err != nil {
		return 0
	}
	for _, entry := range entries {
		if info, err := entry.Info(); err == nil {
			total += info.Size()
		}
	}
	return total
}
