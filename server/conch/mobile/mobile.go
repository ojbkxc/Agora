package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/newo-ether/conch/buildinfo"
	"github.com/newo-ether/conch/config"
	"github.com/newo-ether/conch/crypto"
	"github.com/newo-ether/conch/handler"
	"github.com/newo-ether/conch/shell"
)

var (
	mu      sync.Mutex
	running bool
	srv     *http.Server
	jobMgr  *shell.JobManager
	cancel  context.CancelFunc
	keyPair *crypto.KeyPair
	apiKey  []byte
)

func Start(apiKeyStr string, port int, jobDir string) error {
	mu.Lock()
	defer mu.Unlock()

	if running {
		return errors.New("conch is already running")
	}

	cfg := &config.Config{
		Port:              port,
		Host:              "127.0.0.1",
		APIKey:            apiKeyStr,
		Timeout:           30 * time.Second,
		MaxTimeout:        120 * time.Second,
		AllowNoAuth:       apiKeyStr == "",
		JobDir:            jobDir,
		JobRetention:      168 * time.Hour,
		MaxJobRuntime:     86400 * time.Second,
		MaxJobOutputBytes: 256 * 1024,
		MaxJobs:           100,
	}
	if err := cfg.Validate(); err != nil {
		return fmt.Errorf("invalid configuration: %w", err)
	}

	kp, err := crypto.GenerateKeyPair()
	if err != nil {
		return fmt.Errorf("failed to generate X25519 key pair: %w", err)
	}
	keyPair = kp
	apiKey = []byte(cfg.APIKey)

	nonceTracker := crypto.NewNonceTracker()
	rateLimiter := handler.NewRateLimiter(20, 40, cfg.TrustedProxies...)

	executor := shell.NewExecutor(cfg.Timeout, cfg.MaxTimeout)
	jm, err := shell.NewJobManager(
		executor,
		cfg.JobDir,
		cfg.JobRetention,
		cfg.MaxJobRuntime,
		cfg.MaxJobOutputBytes,
		cfg.MaxJobs,
	)
	if err != nil {
		rateLimiter.Close()
		return fmt.Errorf("failed to initialize shell job manager: %w", err)
	}
	jobMgr = jm

	executeHandler := &handler.ExecuteHandler{
		Executor: executor,
		APIKey:   apiKey,
		KeyPair:  keyPair,
	}

	mux := http.NewServeMux()
	auth := handler.AuthMiddleware(apiKey, nonceTracker)

	fileReadHandler := &handler.FileReadHandler{APIKey: apiKey, KeyPair: keyPair}
	fileImageHandler := &handler.FileImageHandler{APIKey: apiKey, KeyPair: keyPair}
	fileWriteHandler := &handler.FileWriteHandler{APIKey: apiKey, KeyPair: keyPair}
	fileEditHandler := &handler.FileEditHandler{APIKey: apiKey, KeyPair: keyPair}
	fileGlobHandler := &handler.FileGlobHandler{APIKey: apiKey, KeyPair: keyPair}
	fileGrepHandler := &handler.FileGrepHandler{APIKey: apiKey, KeyPair: keyPair}

	mux.Handle("POST /execute", auth(executeHandler))
	mux.Handle("POST /jobs/start", auth(&handler.JobHandler{
		Action: "start", Jobs: jobMgr, APIKey: apiKey, KeyPair: keyPair,
	}))
	mux.Handle("POST /jobs/list", auth(&handler.JobHandler{
		Action: "list", Jobs: jobMgr, APIKey: apiKey, KeyPair: keyPair,
	}))
	mux.Handle("POST /jobs/get", auth(&handler.JobHandler{
		Action: "get", Jobs: jobMgr, APIKey: apiKey, KeyPair: keyPair,
	}))
	mux.Handle("POST /jobs/stop", auth(&handler.JobHandler{
		Action: "stop", Jobs: jobMgr, APIKey: apiKey, KeyPair: keyPair,
	}))
	mux.Handle("POST /jobs/ack", auth(&handler.JobHandler{
		Action: "ack", Jobs: jobMgr, APIKey: apiKey, KeyPair: keyPair,
	}))
	mux.Handle("POST /file/read", auth(fileReadHandler))
	mux.Handle("POST /file/image", auth(fileImageHandler))
	mux.Handle("POST /file/write", auth(fileWriteHandler))
	mux.Handle("POST /file/edit", auth(fileEditHandler))
	mux.Handle("POST /file/glob", auth(fileGlobHandler))
	mux.Handle("POST /file/grep", auth(fileGrepHandler))
	mux.HandleFunc("GET /health", handler.HealthHandler)
	mux.HandleFunc("GET /version", handler.VersionHandler)
	mux.HandleFunc("GET /public-key", func(w http.ResponseWriter, r *http.Request) {
		nonce, err := crypto.GenerateNonce()
		if err != nil {
			http.Error(w, `{"error":"internal error"}`, http.StatusInternalServerError)
			return
		}
		pubKey := keyPair.PublicKeyBase64()
		sig := crypto.SignPayload(apiKey, nonce, pubKey)
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{
			"public_key": pubKey,
			"nonce":      nonce,
			"signature":  sig,
		})
	})

	_, c := context.WithCancel(context.Background())
	cancel = c

	srv = &http.Server{
		Addr:              fmt.Sprintf("%s:%d", cfg.Host, cfg.Port),
		Handler:           rateLimiter.Middleware(mux),
		ReadTimeout:       10 * time.Second,
		ReadHeaderTimeout: 5 * time.Second,
		WriteTimeout:      0,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    64 << 10,
	}

	running = true
	go func() {
		log.Printf("conch listening on %s:%d", cfg.Host, cfg.Port)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Printf("ERROR: HTTP server stopped unexpectedly: %v", err)
		}
	}()

	return nil
}

func Stop() error {
	mu.Lock()
	defer mu.Unlock()

	if !running {
		return errors.New("conch is not running")
	}

	if cancel != nil {
		cancel()
	}

	jobShutdownCtx, cancelJobs := context.WithTimeout(context.Background(), 10*time.Second)
	if jobMgr != nil {
		if err := jobMgr.Close(jobShutdownCtx); err != nil {
			log.Printf("ERROR: background job shutdown incomplete: %v", err)
		}
	}
	cancelJobs()

	shutdownCtx, cancelServer := context.WithTimeout(context.Background(), 5*time.Second)
	if srv != nil {
		if err := srv.Shutdown(shutdownCtx); err != nil {
			log.Printf("ERROR: HTTP shutdown incomplete: %v", err)
		}
	}
	cancelServer()

	running = false
	srv = nil
	jobMgr = nil
	cancel = nil
	keyPair = nil
	apiKey = nil

	return nil
}

func PublicKey() string {
	mu.Lock()
	defer mu.Unlock()
	if keyPair == nil {
		return ""
	}
	return keyPair.PublicKeyBase64()
}

func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return running
}

func SetShellPath(path string) {
	shell.SetShellPath(path)
}

func Version() string {
	return buildinfo.String("conch")
}
