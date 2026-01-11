package executil

import (
	"bytes"
	"context"
	"os/exec"
)

type Runner struct{}

func NewRunner() Runner {
	return Runner{}
}

func (Runner) Run(ctx context.Context, name string, args ...string) (stdout string, stderr string, exitCode int, err error) {
	cmd := exec.CommandContext(ctx, name, args...)
	var outBuf bytes.Buffer
	var errBuf bytes.Buffer
	cmd.Stdout = &outBuf
	cmd.Stderr = &errBuf
	runErr := cmd.Run()

	code := 0
	if cmd.ProcessState != nil {
		code = cmd.ProcessState.ExitCode()
	}

	return outBuf.String(), errBuf.String(), code, runErr
}
