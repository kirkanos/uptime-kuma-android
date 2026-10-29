// Package fcm sends data messages through the Firebase Cloud Messaging HTTP v1 API using a
// service account, without depending on the Google client libraries.
package fcm

import (
	"crypto/rsa"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"os"
)

// DefaultTokenURI is Google's OAuth 2.0 token endpoint, used when the service account file
// does not name one.
const DefaultTokenURI = "https://oauth2.googleapis.com/token"

// Credentials is the parsed subset of a Google service account key file that the relay needs.
type Credentials struct {
	ProjectID   string
	ClientEmail string
	KeyID       string
	TokenURI    string
	Key         *rsa.PrivateKey
}

type serviceAccountFile struct {
	Type         string `json:"type"`
	ProjectID    string `json:"project_id"`
	PrivateKeyID string `json:"private_key_id"`
	PrivateKey   string `json:"private_key"`
	ClientEmail  string `json:"client_email"`
	TokenURI     string `json:"token_uri"`
}

// LoadCredentials reads and parses a service account JSON file.
func LoadCredentials(path string) (*Credentials, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("reading service account file: %w", err)
	}
	return ParseCredentials(data)
}

// ParseCredentials parses the JSON of a service account key ("Generate new private key" in the
// Firebase console, Project settings -> Service accounts).
func ParseCredentials(data []byte) (*Credentials, error) {
	var f serviceAccountFile
	if err := json.Unmarshal(data, &f); err != nil {
		return nil, fmt.Errorf("service account file is not valid JSON: %w", err)
	}
	if f.Type != "service_account" {
		return nil, fmt.Errorf("service account file has type %q, want \"service_account\"", f.Type)
	}
	if f.ClientEmail == "" {
		return nil, errors.New("service account file has no client_email")
	}
	if f.PrivateKey == "" {
		return nil, errors.New("service account file has no private_key")
	}
	key, err := parsePrivateKey([]byte(f.PrivateKey))
	if err != nil {
		return nil, err
	}
	tokenURI := f.TokenURI
	if tokenURI == "" {
		tokenURI = DefaultTokenURI
	}
	return &Credentials{
		ProjectID:   f.ProjectID,
		ClientEmail: f.ClientEmail,
		KeyID:       f.PrivateKeyID,
		TokenURI:    tokenURI,
		Key:         key,
	}, nil
}

func parsePrivateKey(pemBytes []byte) (*rsa.PrivateKey, error) {
	block, _ := pem.Decode(pemBytes)
	if block == nil {
		return nil, errors.New("service account private_key is not PEM encoded")
	}
	if k, err := x509.ParsePKCS8PrivateKey(block.Bytes); err == nil {
		rsaKey, ok := k.(*rsa.PrivateKey)
		if !ok {
			return nil, errors.New("service account private_key is not an RSA key")
		}
		return rsaKey, nil
	}
	k, err := x509.ParsePKCS1PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("parsing service account private_key: %w", err)
	}
	return k, nil
}
