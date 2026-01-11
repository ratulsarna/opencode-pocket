package gateway

import (
	"context"
	"crypto/subtle"
	"errors"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strings"
	"time"
)

type Options struct {
	ListenAddr string
	Upstream   string
	Token      string
}

type Server struct {
	opts   Options
	server *http.Server
	ln     net.Listener
}

func New(opts Options) (*Server, error) {
	if strings.TrimSpace(opts.ListenAddr) == "" {
		return nil, errors.New("ListenAddr is required")
	}
	if strings.TrimSpace(opts.Upstream) == "" {
		return nil, errors.New("Upstream is required")
	}
	if strings.TrimSpace(opts.Token) == "" {
		return nil, errors.New("Token is required")
	}

	upstreamURL, err := url.Parse(opts.Upstream)
	if err != nil {
		return nil, err
	}

	ln, err := net.Listen("tcp", opts.ListenAddr)
	if err != nil {
		return nil, err
	}

	proxy := httputil.NewSingleHostReverseProxy(upstreamURL)
	proxy.FlushInterval = 25 * time.Millisecond

	origDirector := proxy.Director
	proxy.Director = func(r *http.Request) {
		origDirector(r)
		// Never forward client auth credentials to the upstream.
		r.Header.Del("Authorization")
	}

	proxy.ErrorHandler = func(w http.ResponseWriter, r *http.Request, e error) {
		http.Error(w, "bad gateway", http.StatusBadGateway)
	}

	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authHeader := r.Header.Get("Authorization")
		token := strings.TrimPrefix(authHeader, "Bearer ")
		if authHeader == token || subtle.ConstantTimeCompare([]byte(token), []byte(opts.Token)) != 1 {
			w.Header().Set("Content-Type", "text/plain; charset=utf-8")
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte("unauthorized"))
			return
		}
		proxy.ServeHTTP(w, r)
	})

	srv := &http.Server{
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
	}

	return &Server{
		opts:   opts,
		server: srv,
		ln:     ln,
	}, nil
}

func (s *Server) Start(ctx context.Context) error {
	if s == nil || s.server == nil || s.ln == nil {
		return errors.New("server not initialized")
	}

	shutdownDone := make(chan struct{})
	go func() {
		defer close(shutdownDone)
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_ = s.server.Shutdown(shutdownCtx)
	}()

	err := s.server.Serve(s.ln)
	<-shutdownDone
	if err == nil || errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

func (s *Server) Close() error {
	if s == nil {
		return nil
	}
	if s.server != nil {
		_ = s.server.Close()
	}
	if s.ln != nil {
		_ = s.ln.Close()
	}
	return nil
}

func (s *Server) BaseURL() string {
	if s == nil || s.ln == nil {
		return ""
	}
	return "http://" + s.ln.Addr().String()
}
