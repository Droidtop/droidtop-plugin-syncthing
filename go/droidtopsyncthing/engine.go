// Copyright (C) 2026 bi0shacker001
// SPDX-License-Identifier: GPL-3.0-or-later

package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/syncthing/syncthing/lib/config"
	"github.com/syncthing/syncthing/lib/events"
	"github.com/syncthing/syncthing/lib/locations"
	"github.com/syncthing/syncthing/lib/protocol"
	"github.com/syncthing/syncthing/lib/svcutil"
	"github.com/syncthing/syncthing/lib/syncthing"
	"github.com/syncthing/syncthing/lib/tlsutil"
)

const (
	// The same values upstream's command line uses (cmd/syncthing, serveCmd).
	deleteRetention     = 10920 * time.Hour
	maintenanceInterval = 8 * time.Hour
	// What cmd/syncthing uses for a device certificate (lib/syncthing).
	certCommonName   = "syncthing"
	certLifetimeDays = 20 * 365
	// Syncthing's own default sync port, when the listen addresses are "default".
	defaultSyncPort = 22000
	logFileName     = "syncthing.log"
	logKeepBytes    = 2 << 20
)

// engine is the one Syncthing this process runs. The configuration lives for the whole process, so droidtop's pages
// can change devices and folders while sync is stopped; the App and its database exist only while sync runs.
type engine struct {
	mu       sync.Mutex
	home     string
	cert     tls.Certificate
	haveCert bool
	evLogger events.Logger
	cfg      config.Wrapper
	cancel   context.CancelFunc
	app      *syncthing.App
	appDone  chan struct{}
	lastErr  string
}

var st engine

var ops = map[string]func([]byte) (any, error){
	"configure":     opConfigure,
	"set_dns":       opSetDNS,
	"load_identity": opLoadIdentity,
	"start":         opStart,
	"stop":          opStop,
	"get_state":     opGetState,
	"put_device":    opPutDevice,
	"delete_device": opDeleteDevice,
	"put_folder":    opPutFolder,
	"delete_folder": opDeleteFolder,
	"set_rescan":    opSetRescan,
	"scan":          opScan,
	"read_log":      opReadLog,
}

func decode(args []byte, into any) error {
	if err := json.Unmarshal(args, into); err != nil {
		return fmt.Errorf("arguments: %w", err)
	}
	return nil
}

// configure {home, dns}: where Syncthing keeps its configuration, database and log (the plugin's own folder, which
// droidtop's broker maps for this process), and the DNS servers to ask. Only the first home counts.
func opConfigure(args []byte) (any, error) {
	var a struct {
		Home string   `json:"home"`
		DNS  []string `json:"dns"`
	}
	if err := decode(args, &a); err != nil {
		return nil, err
	}
	setDNS(a.DNS)
	st.mu.Lock()
	defer st.mu.Unlock()
	if st.home != "" {
		return map[string]any{"home": st.home}, nil
	}
	if !filepath.IsAbs(a.Home) {
		return nil, errors.New("home must be an absolute path")
	}
	if err := os.MkdirAll(a.Home, 0o700); err != nil {
		return nil, err
	}
	for _, dir := range []locations.BaseDirEnum{locations.ConfigBaseDir, locations.DataBaseDir} {
		if err := locations.SetBaseDir(dir, a.Home); err != nil {
			return nil, err
		}
	}
	redirectOutput(filepath.Join(a.Home, logFileName))
	st.home = a.Home
	return map[string]any{"home": st.home}, nil
}

// redirectOutput sends this process's standard output and error, where Syncthing writes its log, to a file in its
// home: the process has no log daemon socket (docs/plugin-api.md 5.3), so this is the only place the log can go.
func redirectOutput(path string) {
	if info, err := os.Stat(path); err == nil && info.Size() > logKeepBytes {
		_ = os.Rename(path, path+".old")
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return
	}
	_ = syscall.Dup3(int(f.Fd()), 1, 0)
	_ = syscall.Dup3(int(f.Fd()), 2, 0)
	fmt.Fprintf(os.Stderr, "--- droidtop started Syncthing's process at %s\n", time.Now().Format(time.RFC3339))
}

func opSetDNS(args []byte) (any, error) {
	var a struct {
		DNS []string `json:"dns"`
	}
	if err := decode(args, &a); err != nil {
		return nil, err
	}
	setDNS(a.DNS)
	return nil, nil
}

// load_identity {cert, key}: this device's Syncthing identity from the PEM droidtop keeps in the plugin's vault, or a
// new one when both are empty. Answers the device ID and the identity's PEM (for the vault). Then loads (or creates)
// the configuration, with the options droidtop holds Syncthing to.
func opLoadIdentity(args []byte) (any, error) {
	var a struct {
		Cert string `json:"cert"`
		Key  string `json:"key"`
	}
	if err := decode(args, &a); err != nil {
		return nil, err
	}
	st.mu.Lock()
	defer st.mu.Unlock()
	if st.home == "" {
		return nil, errors.New("configure first")
	}
	reply := map[string]any{"generated": false}
	if !st.haveCert {
		var cert tls.Certificate
		var err error
		if a.Cert != "" && a.Key != "" {
			cert, err = tls.X509KeyPair([]byte(a.Cert), []byte(a.Key))
		} else {
			cert, err = tlsutil.NewCertificateInMemory(certCommonName, certLifetimeDays)
			reply["generated"] = true
		}
		if err != nil {
			return nil, fmt.Errorf("identity: %w", err)
		}
		st.cert = cert
		st.haveCert = true
	}
	// Always handed back, so droidtop can keep it in the vault even when an earlier attempt to store it failed.
	certPEM, keyPEM, err := encodeIdentity(st.cert)
	if err != nil {
		return nil, err
	}
	reply["cert"] = certPEM
	reply["key"] = keyPEM
	if st.cfg == nil {
		if err := st.openConfigLocked(); err != nil {
			return nil, err
		}
	}
	reply["deviceId"] = protocol.NewDeviceID(st.cert.Certificate[0]).String()
	return reply, nil
}

func encodeIdentity(cert tls.Certificate) (string, string, error) {
	keyDER, err := x509.MarshalPKCS8PrivateKey(cert.PrivateKey)
	if err != nil {
		return "", "", err
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: cert.Certificate[0]})
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER})
	return string(certPEM), string(keyPEM), nil
}

func (e *engine) openConfigLocked() error {
	ctx, cancel := context.WithCancel(context.Background())
	evLogger := events.NewLogger()
	go func() { _ = evLogger.Serve(ctx) }()
	cfg, err := syncthing.LoadConfigAtStartup(locations.Get(locations.ConfigFile), e.cert, evLogger, false, false)
	if err != nil {
		cancel()
		return fmt.Errorf("configuration: %w", err)
	}
	go func() { _ = cfg.Serve(ctx) }()
	e.evLogger, e.cfg, e.cancel = evLogger, cfg, cancel
	return e.holdOptions()
}

// holdOptions: what droidtop's Syncthing always is. No web page of its own on this device (droidtop draws its
// pages, and a page on loopback would be reachable by every app here), no usage or crash reports, no self-update
// (droidtop updates the plugin), no file watcher (the sandbox refuses inotify, so folders are rescanned instead),
// and new folders under the plugin's own folder.
func (e *engine) holdOptions() error {
	folders := filepath.Join(e.home, "folders")
	if err := os.MkdirAll(folders, 0o700); err != nil {
		return err
	}
	w, err := e.cfg.Modify(func(c *config.Configuration) {
		c.GUI.Enabled = false
		c.Options.StartBrowser = false
		c.Options.URAccepted = -1
		c.Options.CREnabled = false
		c.Options.AutoUpgradeIntervalH = 0
		c.Defaults.Folder.Path = folders
		c.Defaults.Folder.FSWatcherEnabled = false
		for i := range c.Folders {
			c.Folders[i].FSWatcherEnabled = false
		}
	})
	if err != nil {
		return err
	}
	w.Wait()
	return e.cfg.Save()
}

func opStart(args []byte) (any, error) {
	st.mu.Lock()
	defer st.mu.Unlock()
	if st.cfg == nil {
		return nil, errors.New("load the identity first")
	}
	if st.runningLocked() {
		return map[string]any{"running": true}, nil
	}
	sdb, err := syncthing.OpenDatabase(locations.Get(locations.Database), deleteRetention)
	if err != nil {
		st.lastErr = err.Error()
		return nil, fmt.Errorf("database: %w", err)
	}
	app, err := syncthing.New(st.cfg, sdb, st.evLogger, st.cert, syncthing.Options{
		NoUpgrade:             true,
		DBMaintenanceInterval: maintenanceInterval,
	})
	if err != nil {
		_ = sdb.Close()
		st.lastErr = err.Error()
		return nil, err
	}
	if err := app.Start(); err != nil {
		st.lastErr = err.Error()
		return nil, err
	}
	done := make(chan struct{})
	go func() { app.Wait(); close(done) }()
	st.app, st.appDone = app, done
	st.lastErr = ""
	return map[string]any{"running": true}, nil
}

func opStop(args []byte) (any, error) {
	st.mu.Lock()
	app := st.app
	st.app, st.appDone = nil, nil
	st.mu.Unlock()
	if app != nil {
		app.Stop(svcutil.ExitSuccess)
		if err := app.Error(); err != nil {
			st.mu.Lock()
			st.lastErr = err.Error()
			st.mu.Unlock()
		}
	}
	return map[string]any{"running": false}, nil
}

func (e *engine) runningLocked() bool {
	if e.app == nil {
		return false
	}
	select {
	case <-e.appDone:
		// It stopped on its own: keep why, for droidtop's page and the service's restart.
		if err := e.app.Error(); err != nil {
			e.lastErr = err.Error()
		}
		e.app, e.appDone = nil, nil
		return false
	default:
		return true
	}
}

type deviceState struct {
	ID         string   `json:"id"`
	Name       string   `json:"name"`
	Addresses  []string `json:"addresses"`
	Untrusted  bool     `json:"untrusted"`
	Paused     bool     `json:"paused"`
	AutoAccept bool     `json:"autoAccept"`
	Connected  bool     `json:"connected"`
}

type folderDevice struct {
	ID        string `json:"id"`
	Encrypted bool   `json:"encrypted"`
}

type folderState struct {
	ID          string         `json:"id"`
	Label       string         `json:"label"`
	Path        string         `json:"path"`
	Type        string         `json:"type"`
	Paused      bool           `json:"paused"`
	RescanS     int            `json:"rescanS"`
	Devices     []folderDevice `json:"devices"`
	State       string         `json:"state,omitempty"`
	StateSince  int64          `json:"stateSince,omitempty"`
	Error       string         `json:"error,omitempty"`
	GlobalBytes int64          `json:"globalBytes"`
	LocalBytes  int64          `json:"localBytes"`
	NeedBytes   int64          `json:"needBytes"`
	GlobalFiles int            `json:"globalFiles"`
	LocalFiles  int            `json:"localFiles"`
}

type pendingFolder struct {
	ID        string `json:"id"`
	Label     string `json:"label"`
	Device    string `json:"device"`
	Encrypted bool   `json:"encrypted"`
}

// get_state: the configuration and, while sync runs, what each device and folder is doing.
func opGetState(args []byte) (any, error) {
	st.mu.Lock()
	defer st.mu.Unlock()
	out := map[string]any{"running": false, "home": st.home}
	if st.lastErr != "" {
		out["error"] = st.lastErr
	}
	if st.cfg == nil {
		return out, nil
	}
	running := st.runningLocked()
	out["running"] = running
	if st.lastErr != "" {
		out["error"] = st.lastErr
	}
	myID := st.cfg.MyID()
	out["deviceId"] = myID.String()
	out["listenPort"] = listenPort(st.cfg.Options().RawListenAddresses)
	out["rescanS"] = st.cfg.DefaultFolder().RescanIntervalS
	var internals *syncthing.Internals
	if running {
		internals = st.app.Internals
	}

	devices := []deviceState{}
	for _, d := range st.cfg.DeviceList() {
		if d.DeviceID == myID {
			continue
		}
		ds := deviceState{
			ID: d.DeviceID.String(), Name: d.Name, Addresses: d.Addresses, Untrusted: d.Untrusted,
			Paused: d.Paused, AutoAccept: d.AutoAcceptFolders,
		}
		if internals != nil {
			ds.Connected = internals.IsConnectedTo(d.DeviceID)
		}
		devices = append(devices, ds)
	}
	sort.Slice(devices, func(i, j int) bool { return devices[i].Name < devices[j].Name })
	out["devices"] = devices

	untrusted := map[protocol.DeviceID]bool{}
	for _, d := range st.cfg.DeviceList() {
		untrusted[d.DeviceID] = d.Untrusted
	}
	folders := []folderState{}
	for _, f := range st.cfg.FolderList() {
		fs := folderState{
			ID: f.ID, Label: f.Label, Path: f.Path, Type: f.Type.String(), Paused: f.Paused, RescanS: f.RescanIntervalS,
			Devices: []folderDevice{},
		}
		for _, fd := range f.Devices {
			if fd.DeviceID == myID {
				continue
			}
			fs.Devices = append(fs.Devices, folderDevice{ID: fd.DeviceID.String(), Encrypted: fd.EncryptionPassword != "" && untrusted[fd.DeviceID]})
		}
		if internals != nil {
			if state, since, err := internals.FolderState(f.ID); err == nil {
				fs.State = state
				if !since.IsZero() {
					fs.StateSince = since.UnixMilli()
				}
			} else {
				fs.State = "error"
				fs.Error = err.Error()
			}
			if c, err := internals.GlobalSize(f.ID); err == nil {
				fs.GlobalBytes, fs.GlobalFiles = c.Bytes, c.Files
			}
			if c, err := internals.LocalSize(f.ID); err == nil {
				fs.LocalBytes, fs.LocalFiles = c.Bytes, c.Files
			}
			if c, err := internals.NeedSize(f.ID, protocol.LocalDeviceID); err == nil {
				fs.NeedBytes = c.Bytes
			}
		}
		folders = append(folders, fs)
	}
	sort.Slice(folders, func(i, j int) bool { return folders[i].Label < folders[j].Label })
	out["folders"] = folders

	pending := []pendingFolder{}
	if internals != nil {
		if found, err := internals.PendingFolders(protocol.EmptyDeviceID); err == nil {
			for id, p := range found {
				for device, offer := range p.OfferedBy {
					pending = append(pending, pendingFolder{ID: id, Label: offer.Label, Device: device.String(), Encrypted: offer.RemoteEncrypted})
				}
			}
		}
	}
	sort.Slice(pending, func(i, j int) bool { return pending[i].Label < pending[j].Label })
	out["pending"] = pending
	return out, nil
}

// listenPort is the TCP port Syncthing listens on, for the addresses this device announces to its counterparts.
func listenPort(addresses []string) int {
	for _, a := range addresses {
		if a == "default" {
			return defaultSyncPort
		}
		if rest, ok := strings.CutPrefix(a, "tcp://"); ok {
			if _, port, err := net.SplitHostPort(rest); err == nil {
				if n, err := strconv.Atoi(port); err == nil {
					return n
				}
			}
		}
	}
	return defaultSyncPort
}

func parseDevice(id string) (protocol.DeviceID, error) {
	d, err := protocol.DeviceIDFromString(strings.TrimSpace(id))
	if err != nil {
		return d, fmt.Errorf("not a Syncthing device ID: %q", id)
	}
	return d, nil
}

// put_device {id, name, addresses, untrusted, autoAccept}: adds the device, or changes those fields of it.
func opPutDevice(args []byte) (any, error) {
	var a struct {
		ID         string   `json:"id"`
		Name       string   `json:"name"`
		Addresses  []string `json:"addresses"`
		Untrusted  bool     `json:"untrusted"`
		AutoAccept bool     `json:"autoAccept"`
		Paused     bool     `json:"paused"`
	}
	if err := decode(args, &a); err != nil {
		return nil, err
	}
	id, err := parseDevice(a.ID)
	if err != nil {
		return nil, err
	}
	cfg, err := currentConfig()
	if err != nil {
		return nil, err
	}
	if id == cfg.MyID() {
		return nil, errors.New("that is this device's own ID")
	}
	addresses := a.Addresses
	if len(addresses) == 0 {
		addresses = []string{"dynamic"}
	}
	return nil, modify(cfg, func(c *config.Configuration) {
		d, _, ok := c.Device(id)
		if !ok {
			d = cfg.DefaultDevice()
			d.DeviceID = id
		}
		d.Name = a.Name
		d.Addresses = addresses
		d.Untrusted = a.Untrusted
		d.AutoAcceptFolders = a.AutoAccept && !a.Untrusted
		d.Paused = a.Paused
		if d.Untrusted {
			d.Introducer = false
		}
		c.SetDevice(d)
	})
}

func opDeleteDevice(args []byte) (any, error) {
	var a struct {
		ID string `json:"id"`
	}
	if err := decode(args, &a); err != nil {
		return nil, err
	}
	id, err := parseDevice(a.ID)
	if err != nil {
		return nil, err
	}
	cfg, err := currentConfig()
	if err != nil {
		return nil, err
	}
	w, err := cfg.RemoveDevice(id)
	if err != nil {
		return nil, err
	}
	w.Wait()
	return nil, cfg.Save()
}

// put_folder {id, label, path, devices: [{id, password}]}: adds the folder (its folder and marker made), or changes its
// label and the devices it is shared with. A device that is untrusted gets the folder encrypted with its password.
func opPutFolder(args []byte) (any, error) {
	var a struct {
		ID      string `json:"id"`
		Label   string `json:"label"`
		Path    string `json:"path"`
		Devices []struct {
			ID       string `json:"id"`
			Password string `json:"password"`
		} `json:"devices"`
	}
	if err := decode(args, &a); err != nil {
		return nil, err
	}
	if strings.TrimSpace(a.ID) == "" {
		return nil, errors.New("a folder needs an id")
	}
	cfg, err := currentConfig()
	if err != nil {
		return nil, err
	}
	devices := []config.FolderDeviceConfiguration{{DeviceID: cfg.MyID()}}
	for _, d := range a.Devices {
		id, err := parseDevice(d.ID)
		if err != nil {
			return nil, err
		}
		if dev, ok := cfg.Device(id); ok && dev.Untrusted && d.Password == "" {
			return nil, fmt.Errorf("an untrusted device needs the folder's encryption password")
		}
		devices = append(devices, config.FolderDeviceConfiguration{DeviceID: id, EncryptionPassword: d.Password})
	}
	existing, exists := cfg.Folder(a.ID)
	folder := existing
	if !exists {
		folder = cfg.DefaultFolder()
		folder.ID = a.ID
		folder.Path = a.Path
		if folder.Path == "" {
			folder.Path = filepath.Join(cfg.DefaultFolder().Path, safeName(a.Label, a.ID))
		}
		if !filepath.IsAbs(folder.Path) {
			return nil, errors.New("a folder's path must be absolute")
		}
		if err := folder.CreateRoot(); err != nil {
			return nil, fmt.Errorf("could not make the folder: %w", err)
		}
		if err := folder.CreateMarker(); err != nil {
			return nil, fmt.Errorf("could not mark the folder: %w", err)
		}
	}
	folder.Label = a.Label
	folder.Devices = devices
	folder.FSWatcherEnabled = false
	return nil, modify(cfg, func(c *config.Configuration) { c.SetFolder(folder) })
}

// safeName is a folder name made from a label: letters, digits, space, dot, dash and underscore, else the id.
func safeName(label, id string) string {
	var b strings.Builder
	for _, r := range strings.TrimSpace(label) {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', r == ' ', r == '.', r == '-', r == '_':
			b.WriteRune(r)
		default:
			b.WriteRune('_')
		}
	}
	name := strings.Trim(b.String(), " .")
	if name == "" {
		name = id
	}
	return name
}

// delete_folder {id}: stops syncing the folder; its files stay where they are.
func opDeleteFolder(args []byte) (any, error) {
	var a struct {
		ID string `json:"id"`
	}
	if err := decode(args, &a); err != nil {
		return nil, err
	}
	cfg, err := currentConfig()
	if err != nil {
		return nil, err
	}
	w, err := cfg.RemoveFolder(a.ID)
	if err != nil {
		return nil, err
	}
	w.Wait()
	return nil, cfg.Save()
}

// set_rescan {seconds}: how often every folder is scanned for changes (there is no watcher in the sandbox).
func opSetRescan(args []byte) (any, error) {
	var a struct {
		Seconds int `json:"seconds"`
	}
	if err := decode(args, &a); err != nil {
		return nil, err
	}
	if a.Seconds < 60 || a.Seconds > 24*3600 {
		return nil, errors.New("seconds must be between 60 and 86400")
	}
	cfg, err := currentConfig()
	if err != nil {
		return nil, err
	}
	return nil, modify(cfg, func(c *config.Configuration) {
		c.Defaults.Folder.RescanIntervalS = a.Seconds
		for i := range c.Folders {
			c.Folders[i].RescanIntervalS = a.Seconds
		}
	})
}

// scan: every folder now, while sync runs.
func opScan(args []byte) (any, error) {
	st.mu.Lock()
	defer st.mu.Unlock()
	if !st.runningLocked() {
		return nil, errors.New("sync is not running")
	}
	failed := map[string]string{}
	for id, err := range st.app.Internals.ScanFolders() {
		if err != nil {
			failed[id] = err.Error()
		}
	}
	return map[string]any{"failed": failed}, nil
}

// read_log {bytes}: the end of Syncthing's log.
func opReadLog(args []byte) (any, error) {
	var a struct {
		Bytes int64 `json:"bytes"`
	}
	if err := decode(args, &a); err != nil {
		return nil, err
	}
	if a.Bytes <= 0 || a.Bytes > 64<<10 {
		a.Bytes = 16 << 10
	}
	st.mu.Lock()
	home := st.home
	st.mu.Unlock()
	if home == "" {
		return map[string]any{"text": ""}, nil
	}
	f, err := os.Open(filepath.Join(home, logFileName))
	if err != nil {
		return map[string]any{"text": ""}, nil
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return nil, err
	}
	start := info.Size() - a.Bytes
	if start < 0 {
		start = 0
	}
	buf := make([]byte, info.Size()-start)
	n, _ := f.ReadAt(buf, start)
	return map[string]any{"text": strings.ToValidUTF8(string(buf[:n]), "?")}, nil
}

func currentConfig() (config.Wrapper, error) {
	st.mu.Lock()
	defer st.mu.Unlock()
	if st.cfg == nil {
		return nil, errors.New("load the identity first")
	}
	return st.cfg, nil
}

func modify(cfg config.Wrapper, fn config.ModifyFunction) error {
	w, err := cfg.Modify(fn)
	if err != nil {
		return err
	}
	w.Wait()
	return cfg.Save()
}
