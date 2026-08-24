# WebSphere Application Server Connector

[![License](https://img.shields.io/badge/license-EPLv2.0-brightgreen.png?style=for-the-badge)](https://github.com/bcanalesm/rsp-server-community/blob/master/vscode/LICENSE)

## Summary

Manage an existing **IBM WebSphere Application Server (traditional) 8.5 or 9.0** installation directly from VS Code: create a server pointing at a local profile, start/stop it, and deploy `.war`/`.ear` files to it via `wsadmin`.

This extension is built on the open source [rsp-server-community](https://github.com/redhat-developer/rsp-server-community) project (Runtime Server Protocol), and depends on the [RSP UI extension](https://marketplace.visualstudio.com/items?itemName=redhat.vscode-rsp-ui), which is installed automatically and provides the Servers view and its commands.

By [nicadevs.com](https://nicadevs.com).

## Requirements

- An existing WAS 8.5 or 9.0 (traditional) installation and profile on the machine VS Code runs on. This extension does **not** download or install WAS - IBM's license doesn't allow that; you point it at a profile you already have.
- The profile's SOAP connector reachable from VS Code (`wsadmin` is invoked against it), and administrative security credentials if the profile has them enabled.

## Adding a server

Use the Servers view's **"+"** action and choose **"No, use server on disk"**, then browse to the WAS *profile* home (the folder containing `bin/startServer.sh`/`.bat`, `bin/wsadmin.sh`/`.bat`, and `properties/version/profile.version`). The extension reads the profile's real version to register it as WAS 8.5 or 9.0 automatically.

## Server parameters

Right-click a server → **Edit Server** to change these:

| Parameter | Required | Description |
|---|---|---|
| `server.home.dir` | yes | Path to the WAS *profile* home |
| `was.server.name` | yes | Application server name inside the profile (default `server1`) |
| `was.node.name` | no | Node name; only needed to disambiguate targeting on ND cells |
| `was.cell.name` | no | Cell name; informational only |
| `was.host` | no | Host `wsadmin` and the HTTP poller connect to (default `localhost`) |
| `was.soap.port` | no | SOAP connector port used by `wsadmin` (default `8880`) |
| `was.http.port` | no | HTTP transport port polled to detect the server is up (default `9080`) |
| `was.admin.user` / `was.admin.password` | no | Only needed if administrative security is enabled |
| `was.wsadmin.lang` | no | Scripting language passed to `wsadmin -lang` (default `jython`) |
| `was.poll.timeout.seconds` | no | Seconds to wait for start/stop before giving up (default `120`) |

## Deploying

Add a `.war` or `.ear` as a deployable on the server, then Publish. The application name inside WAS defaults to the file name (without extension); you can override it via the deployment option shown when adding the deployable.

## Commands and features

This extension provides no additional commands beyond those available in [rsp-ui](https://github.com/redhat-developer/vscode-rsp-ui#available-commands); the Servers view, start/stop, and publish actions all come from there.
