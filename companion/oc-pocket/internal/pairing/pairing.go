package pairing

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
)

type Payload struct {
	Version   int    `json:"version"`
	BaseURL   string `json:"baseUrl"`
	Token     string `json:"token"`
	Name      string `json:"name,omitempty"`
	CreatedAt int64  `json:"createdAtMs,omitempty"`
}

const prefixV1 = "oc-pocket-pair:v1:"

func Encode(p Payload) (string, error) {
	if strings.TrimSpace(p.BaseURL) == "" {
		return "", errors.New("baseUrl is required")
	}
	if strings.TrimSpace(p.Token) == "" {
		return "", errors.New("token is required")
	}
	if p.Version == 0 {
		p.Version = 1
	}
	if p.Version != 1 {
		return "", fmt.Errorf("unsupported version: %d", p.Version)
	}

	raw, err := json.Marshal(p)
	if err != nil {
		return "", err
	}
	encoded := base64.RawURLEncoding.EncodeToString(raw)
	return prefixV1 + encoded, nil
}

func Decode(s string) (Payload, error) {
	if !strings.HasPrefix(s, prefixV1) {
		return Payload{}, errors.New("invalid pairing string")
	}
	encoded := strings.TrimPrefix(s, prefixV1)
	raw, err := base64.RawURLEncoding.DecodeString(encoded)
	if err != nil {
		return Payload{}, errors.New("invalid pairing string")
	}
	var p Payload
	if err := json.Unmarshal(raw, &p); err != nil {
		return Payload{}, errors.New("invalid pairing string")
	}
	if p.Version != 1 {
		return Payload{}, fmt.Errorf("unsupported version: %d", p.Version)
	}
	if strings.TrimSpace(p.BaseURL) == "" || strings.TrimSpace(p.Token) == "" {
		return Payload{}, errors.New("invalid pairing string")
	}
	return p, nil
}

func GenerateToken() (string, error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(buf), nil
}
