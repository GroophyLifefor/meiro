package sync

import (
	"errors"
	"os"
	"strings"

	"github.com/elianiva/meiro/internal/fsutil"
)

// ErrNoToken reports that no session token is saved.
var ErrNoToken = errors.New("not signed in for sync")

// TokenStore keeps the sync session token in a file only its user can read,
// apart from the settings, which are not private. Creating one does no work.
type TokenStore struct {
	Path string
}

// Load reads the saved token.
func (s *TokenStore) Load() (string, error) {
	data, err := os.ReadFile(s.Path)
	if errors.Is(err, os.ErrNotExist) {
		return "", ErrNoToken
	}
	if err != nil {
		return "", err
	}
	token := strings.TrimSpace(string(data))
	if token == "" {
		return "", ErrNoToken
	}
	return token, nil
}

// Save keeps a token.
func (s *TokenStore) Save(token string) error {
	return fsutil.WriteFileAtomic(s.Path, []byte(token), 0o600)
}

// Delete forgets the saved token.
func (s *TokenStore) Delete() error {
	if err := os.Remove(s.Path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}
