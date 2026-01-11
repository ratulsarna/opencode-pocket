package launchd

import (
	"bytes"
	"errors"
	"fmt"
	"html"
)

type PlistOptions struct {
	Label       string
	Program     string
	ProgramArgs []string
	StdoutPath  string
	StderrPath  string
	RunAtLoad   bool
	KeepAlive   bool
}

func RenderPlist(opts PlistOptions) ([]byte, error) {
	if opts.Label == "" {
		return nil, errors.New("Label is required")
	}
	if opts.Program == "" {
		return nil, errors.New("Program is required")
	}

	var b bytes.Buffer
	b.WriteString(`<?xml version="1.0" encoding="UTF-8"?>` + "\n")
	b.WriteString(`<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">` + "\n")
	b.WriteString(`<plist version="1.0">` + "\n")
	b.WriteString(`<dict>` + "\n")

	writeKeyString(&b, "Label", opts.Label)

	b.WriteString(`<key>ProgramArguments</key>` + "\n")
	b.WriteString(`<array>` + "\n")
	b.WriteString(`<string>` + html.EscapeString(opts.Program) + `</string>` + "\n")
	for _, arg := range opts.ProgramArgs {
		b.WriteString(`<string>` + html.EscapeString(arg) + `</string>` + "\n")
	}
	b.WriteString(`</array>` + "\n")

	writeKeyBool(&b, "RunAtLoad", opts.RunAtLoad)
	writeKeyBool(&b, "KeepAlive", opts.KeepAlive)

	if opts.StdoutPath != "" {
		writeKeyString(&b, "StandardOutPath", opts.StdoutPath)
	}
	if opts.StderrPath != "" {
		writeKeyString(&b, "StandardErrorPath", opts.StderrPath)
	}

	b.WriteString(`</dict>` + "\n")
	b.WriteString(`</plist>` + "\n")
	return b.Bytes(), nil
}

func writeKeyString(b *bytes.Buffer, key, value string) {
	b.WriteString(fmt.Sprintf("<key>%s</key>\n", html.EscapeString(key)))
	b.WriteString(fmt.Sprintf("<string>%s</string>\n", html.EscapeString(value)))
}

func writeKeyBool(b *bytes.Buffer, key string, value bool) {
	b.WriteString(fmt.Sprintf("<key>%s</key>\n", html.EscapeString(key)))
	if value {
		b.WriteString("<true/>\n")
	} else {
		b.WriteString("<false/>\n")
	}
}
