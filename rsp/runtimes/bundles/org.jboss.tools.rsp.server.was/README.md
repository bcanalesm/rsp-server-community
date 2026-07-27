# WAS traditional (8.5 / 9.0) server adapter for RSP / VS Code

This module adds IBM WebSphere Application Server *traditional* (8.5 and 9.0)
support to the [rsp-server-community](https://github.com/redhat-developer/rsp-server-community)
project - the same backend that powers the **Community Server Connectors**
VS Code extension (Tomcat, GlassFish, Payara, Karaf, Felix, Jetty, WebSphere
Liberty). Dropping this module into that repo and rebuilding the extension
adds "IBM WebSphere Application Server 8.5 / 9.0" as new server types you can
create, start/stop, and deploy WAR/EAR files to, from VS Code's server view.

## Why this needed custom code (and not just a JSON entry)

Every other server in this repo (Tomcat, GlassFish, Liberty...) is defined
almost entirely in a declarative `servers.json`: start/stop launches a Java
main class with a fixed classpath, and "deploy" is just copying a WAR into a
hot-deploy folder. WAS traditional doesn't offer either of those:

- There's no simple, version-stable Java entry point for start/stop -
  `startServer.sh` itself assembles a large, fix-pack-specific classpath at
  runtime. Reverse engineering that felt too fragile to depend on.
- There's no hot-deploy folder. Every deploy has to go through the admin
  layer (`AdminApp`/`AdminConfig`), which only exists via JMX or the
  `wsadmin` scripting shell.

So this adapter takes the more robust route: it shells out to the profile's
own `bin/startServer.sh` / `bin/stopServer.sh` for lifecycle control, and to
`bin/wsadmin.sh` (feeding it a small generated Jython script) for deploy.
Both are IBM's own supported CLI entry points and are stable across fix
packs, unlike a hand-reconstructed JVM invocation.

## Files in this module

| File | Purpose |
|---|---|
| `src/main/resources/servers.json` | Declares the two server types (`com.ibm.was.server.85`, `com.ibm.was.server.90`) and their configurable attributes (profile home, server name, SOAP/HTTP ports, credentials, etc). |
| `impl/Activator.java` | OSGi bundle activator; registers the server types and wires `WASServerDelegate` as their implementation. Copy-pattern of the GlassFish/Liberty adapters. |
| `impl/WASServerDelegate.java` | Overrides `start()`/`stop()` to run the profile scripts and poll the HTTP port for state changes; overrides `createPublishController()` to plug in the wsadmin-based publish logic instead of the default folder-copy one. |
| `impl/WASPublishController.java` | Implements deploy/undeploy by generating a Jython script (`AdminApp.install`/`update`/`uninstall` + `AdminConfig.save` + best-effort app start) and handing it to `WsadminRunner`. |
| `impl/WsadminRunner.java` | Small process-execution helper: writes the Jython script to a temp file, runs `wsadmin.sh -f <script>` with the configured host/port/credentials, and reports success/failure. |
| `impl/WASServerMain.java` | Optional standalone entry point for running/debugging just this adapter outside the full distribution. |
| `servertype/impl/IWASServerAttributes.java` | Attribute key constants shared by the delegate and publish controller. |

## What's genuinely done vs. what's an MVP simplification

Done and should work as described:

- Creating a WAS 8.5 or 9.0 server pointing at a profile home.
- Start / stop via the profile's own scripts, with state reported back to
  the IDE by polling the configured HTTP port.
- Deploy / redeploy / remove of a single WAR or EAR via `wsadmin`.
- Secrets: `was.admin.password` is marked `"secret": "true"` in
  `servers.json`, so the VS Code UI will store/prompt for it the same way
  it does for other servers requiring credentials.

Known simplifications - flagged here on purpose, not hidden:

- **Base profiles / single server only.** Node/cell targeting exists as an
  attribute but the adapter doesn't model ND clusters, multiple nodes, or
  the deployment manager. That's a reasonable next milestone, not an MVP
  requirement.
- **No auto-discovery.** Tomcat/GlassFish can detect "this folder is a
  Tomcat 10.x install" from a jar manifest. WAS's manifest layout wasn't
  verified for this MVP, so servers must be created manually with a known
  profile path.
- **No live console streaming yet.** `startServer`/`stopServer` output is
  captured and written to the adapter's logger (visible via the RSP
  server's own logs), but it isn't wired into VS Code's process/console
  view the way the java-launch mechanism gives other adapters for free.
- **Incremental publish isn't implemented** - every publish is a full
  `AdminApp install`/`update`. Fine for iterating on a single app; slower
  than it could be for large EARs.
- **The exact `AdminApp.install` options are a starting point, not a final
  answer.** Context root, virtual host, and classloader policy are not yet
  exposed as attributes - right now every app gets `-contextroot /<appname>`.
  You will very likely want to extend `WASPublishController` with more
  `AdminApp` options once you're testing against a real profile.

## Building

This module was authored and reviewed against the real `rsp-server` /
`rsp-server-community` sources (cloned locally to check every interface it
implements), but **it has not been compiled** in the environment that wrote
it: the sandbox's network policy blocks Maven Central and the Eclipse p2
repositories that Tycho needs (`mvn ... compile` fails immediately with
`403 Forbidden` / "Unresolveable build extension: tycho-maven-plugin").
Build it somewhere with normal internet access - your own machine or a CI
runner both work fine:

```bash
# 1. Build the RSP server (all bundles, including this one)
cd rsp-server-community/rsp
mvn clean install

# 2. Build the VS Code extension, bundling the server you just built
cd ../vscode
npm install
npm run build      # unpacks rsp/distribution/distribution.community/target/*.zip into vscode/server/
npm run test        # optional
npx vsce package    # produces a .vsix

# 3a. Install it locally to try it out
code --install-extension *.vsix

# 3b. Or publish your own build under your own publisher id
npx vsce publish
```

If step 1 fails on a fresh checkout, first confirm the *unmodified* upstream
repo builds for you (some Tycho/p2 mirrors are occasionally flaky) before
assuming this module broke something.

## Testing against a real profile

1. Create a server of type "IBM WebSphere Application Server 8.5" (or 9.0)
   in the VS Code server view.
2. Set `server.home.dir` to the profile home (e.g.
   `/opt/IBM/WebSphere/AppServer/profiles/AppSrv01`), and `was.server.name`
   to the server inside it (default `server1`).
3. If administrative security is enabled on the profile, fill in
   `was.admin.user` / `was.admin.password`; otherwise leave them blank.
4. Start the server, then drag a WAR onto it (or use the deploy command) to
   exercise `WASPublishController`.
5. Watch the RSP server's own log output (SLF4J) for the `wsadmin` command
   lines and their output while you iterate - that's currently the most
   direct way to see what's happening during install/update/uninstall.

## Contributing this upstream

Since this reuses `rsp-server-community`'s own extension model, the
cleanest path to a durable, officially-distributed VS Code plugin is a pull
request against that repository (adding this module + the `category.xml`
and `bundles/pom.xml` entries already updated here) rather than maintaining
a permanent private fork.
