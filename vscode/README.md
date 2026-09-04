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
| `was.debug.port` | no | JDWP port the server's JVM listens on when started in **Debug** mode (default `7777`) |
| `was.admin.console.port` | no | HTTP port of the WAS admin console, used by **WAS: Open Admin Console** (default `9060`) |

## Deploying

Add a `.war` or `.ear` as a deployable on the server, then Publish. The application name inside WAS defaults to the file name (without extension); you can override it via the deployment option shown when adding the deployable. Publish output (the real `wsadmin`/`AdminApp` log, not just a "started" toast) streams live into the server's own Output channel (`Server: <name>`) as it happens.

For multiple related deployables (e.g. several WARs that make up one application), add each one once - they stay associated with the server. From then on, right-click the **server** itself (not each deployable) and choose **Full Publish** to push whatever's currently built for all of them in one action.

Right-click a `.war`/`.ear` file in the Explorer and choose **Run on Server** or **Debug on Server** to add + publish + start (or restart) it in a single step.

## Building a project from source

If what you're working on is a Maven project (a `pom.xml` producing a `.war`/`.ear`) rather than an already-built artifact, right-click the **project folder** in the Explorer and choose **WAS: Build & Run on Server** or **WAS: Build & Debug on Server**. This runs `mvn clean package`, finds the resulting `.ear` (or `.war`) under `target/`, and deploys + starts (or restarts) it - re-run the same command after editing code to push an updated build. Unlike rsp-ui's own "Run on Server"/"Debug on Server", this never prompts you to pick a server when there's only one configured.

## Remote debugging

Right-click the server → **Debug** (instead of Start), or use **WAS: Build & Debug on Server** above, to launch it with the JDWP debug agent enabled on `was.debug.port`. The extension configures the JVM's debug settings via `wsadmin` before launch, then starts the server; once the debug agent reports it's listening, VS Code's Java debugger attaches automatically (`Debug (Remote)` in the Run and Debug view) - set breakpoints in your servlet/EJB source beforehand.

Once attached, editing the body of an existing method and saving hot-swaps it into the running JVM immediately - no rebuild/redeploy needed. Structural changes (new methods/fields/classes, changed signatures) do need a fresh **Build & Debug on Server**.

## Commands

Right-click a server in the Servers view for two more WAS-specific actions:

- **WAS: Show Server Properties** - opens the server's underlying config file for a quick look at every attribute.
- **WAS: Open Admin Console** - opens `http://<was.host>:<was.admin.console.port>/ibm/console` in your default browser.

Everything else (the Servers view itself, start/stop, and publish actions) comes from [rsp-ui](https://github.com/redhat-developer/vscode-rsp-ui#available-commands).
