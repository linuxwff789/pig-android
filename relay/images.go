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
	"sort"
	"sync"
	"sync/atomic"
	"time"
)

const (
	imageCacheDirName = "images"
	// One image block above this is left inline: a client that cannot fetch
	// refs must still be able to display it. The app refuses to decode a
	// base64 payload above 8,000,000 characters (MAX_IMAGE_B64_CHARS), and a
	// ref it cannot decode is worse than an inline block — the row would wait
	// forever. 4*ceil(n/3) <= 8,000,000 caps the raw bytes at 6,000,000.
	maxImageRefBytes = 6_000_000
	// A single relay_image_get answers at most this many pictures.
	maxImageRefsPerRequest = 8
	// maxImageFrameBytes ... stay under this many base64 bytes; pictures
	// past the budget spill into a follow-up frame. The worst single picture
	// (maxImageRefBytes) is ~8 MB of base64 and always gets a frame of its
	// own — a frame is the split granularity. The app reads frames with an
	// unbounded readLine and the JSON parser copies the line again, so an
	// unbounded frame can OOM either end.
	maxImageFrameBytes = 8 << 20
	// The cache is a convenience, not a source of truth (pig keeps the bytes
	// inline in the session file), so old pictures can be dropped and are
	// recreated on the next get_messages. This bounds what a long transcript
	// leaves on a phone's storage.
	maxImageCacheBytes = 128 << 20
	// Pruning stats the whole directory, so do it once per this many writes
	// instead of after every picture.
	imageCachePruneEvery = 64
)

var imageCacheWrites atomic.Int64

// imageSendMu serializes relay_image_get answers: assembling a batch holds
// the raw bytes, the base64 string and the marshalled frame at the same
// time, and a few concurrent 8-picture requests would multiply that peak.
var imageSendMu sync.Mutex

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
		if imageCacheWrites.Add(1)%imageCachePruneEvery == 0 {
			s.pruneImageCache()
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
	// A page overlaps the previous one (the window only grows), so the same
	// inline base64 blocks were decoded and hashed again on every page. The
	// rewrite is a pure function of the message bytes, so memoizing by content
	// digest turns the O(n²) re-hash into one pass per message.
	sum := sha256.Sum256(message)
	rewriteCacheMu.Lock()
	cached, ok := rewriteCache[sum]
	rewriteCacheMu.Unlock()
	if ok {
		return cached
	}
	out := s.rewriteMessageImagesFresh(message)
	rewriteCacheMu.Lock()
	if len(rewriteCache) >= rewriteCacheEntries {
		// Reset instead of an LRU: the next page refills it and a transcript
		// this large is already paging.
		rewriteCache = map[[32]byte]json.RawMessage{}
	}
	rewriteCache[sum] = out
	rewriteCacheMu.Unlock()
	return out
}

var (
	rewriteCacheMu sync.Mutex
	rewriteCache   = map[[32]byte]json.RawMessage{}
)

// Bounded so a huge session cannot pin every rewritten message in memory.
const rewriteCacheEntries = 512

func (s *session) rewriteMessageImagesFresh(message json.RawMessage) json.RawMessage {
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
// itself; refs are re-validated so a client cannot read arbitrary paths. The
// answer is split into several frames when the base64 payload passes
// maxImageFrameBytes, so one request can never produce an 85 MB line.
func (s *session) sendImages(id any, refs []string) {
	if len(refs) > maxImageRefsPerRequest {
		refs = refs[:maxImageRefsPerRequest]
	}
	imageSendMu.Lock()
	defer imageSendMu.Unlock()
	images := make([]frame, 0, len(refs))
	missing := make([]string, 0)
	batchBytes := 0
	flush := func() {
		if len(images) == 0 && len(missing) == 0 {
			return
		}
		_ = s.send(frame{"type": "relay_image_data", "id": id, "images": images, "missing": missing})
		images = make([]frame, 0, len(refs))
		missing = make([]string, 0)
		batchBytes = 0
	}
	for _, ref := range refs {
		if !validImageRef(ref) {
			continue
		}
		path := filepath.Join(s.imageCacheDir(), ref)
		info, err := os.Stat(path)
		if err != nil || info.Size() <= 0 || info.Size() > maxImageRefBytes {
			// Without an explicit miss the app cannot tell a lost ref from
			// one still in flight, and the row would spin forever.
			missing = append(missing, ref)
			continue
		}
		raw, err := os.ReadFile(path)
		if err != nil {
			missing = append(missing, ref)
			continue
		}
		encoded := base64.StdEncoding.EncodeToString(raw)
		if batchBytes+len(encoded) > maxImageFrameBytes {
			flush()
		}
		images = append(images, frame{"ref": ref, "data": encoded})
		batchBytes += len(encoded)
	}
	flush()
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

// pruneImageCache evicts the oldest files once the cache is over budget. Pig
// still has every picture inline in its session file, so an evicted ref is
// rebuilt on the next get_messages rather than lost.
func (s *session) pruneImageCache() {
	s.pruneImageCacheTo(maxImageCacheBytes)
}

// pruneImageCacheTo is the testable form: everything under limit stays.
func (s *session) pruneImageCacheTo(limit int64) {
	dir := s.imageCacheDir()
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	type fileEntry struct {
		name string
		size int64
		mod  time.Time
	}
	files := make([]fileEntry, 0, len(entries))
	var total int64
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			continue
		}
		files = append(files, fileEntry{entry.Name(), info.Size(), info.ModTime()})
		total += info.Size()
	}
	if total <= limit {
		return
	}
	sort.Slice(files, func(i, j int) bool { return files[i].mod.Before(files[j].mod) })
	// One prune leaves headroom so the next few dozen writes are free.
	target := limit * 8 / 10
	removed := 0
	for _, file := range files {
		if total <= target {
			break
		}
		if os.Remove(filepath.Join(dir, file.name)) == nil {
			total -= file.size
			removed++
		}
	}
	if removed > 0 {
		logf("image cache: pruned %d file(s), now %d bytes", removed, total)
	}
}
