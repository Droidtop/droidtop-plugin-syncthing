# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## 1.0.0

### Added
- Upstream Syncthing v2.1.6 as a Go c-shared library for arm64-v8a and x86_64 (`libsyncthing.so`), loaded by the
  plugin and run as droidtop's `syncthing` background service in an open process with the `net.sockets` hole only.
- Pairing through droidtop: the plugin announces its Syncthing ID and LAN addresses to every paired device
  (`devices.announce`) and accepts exactly the IDs its counterparts announce (`devices.list_paired`,
  `devices.changed`), with their folders accepted automatically. A `droidtop` folder is shared with all of them.
- Other Syncthing devices by ID, trusted or untrusted (an untrusted one gets folders encrypted, for backups), and
  folders other devices offer, accepted from the plugin's page.
- Options: sync only on Wi-Fi (on), only while charging (off), how often folders are scanned (5 minutes).
- The device key and folder encryption passwords in the plugin's vault; Syncthing's web page, usage and crash
  reports and self-update are off.
