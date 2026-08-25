/*******************************************************************************
 * Copyright (c) 2026. Distributed under license by the project contributors.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 ******************************************************************************/
package org.jboss.tools.rsp.server.was.impl;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jboss.tools.rsp.api.ServerManagementAPIConstants;
import org.jboss.tools.rsp.api.dao.CommandLineDetails;
import org.jboss.tools.rsp.api.dao.ServerAttributes;
import org.jboss.tools.rsp.api.dao.StartServerResponse;
import org.jboss.tools.rsp.eclipse.core.runtime.IStatus;
import org.jboss.tools.rsp.eclipse.core.runtime.Status;
import org.jboss.tools.rsp.launching.memento.JSONMemento;
import org.jboss.tools.rsp.server.LauncherSingleton;
import org.jboss.tools.rsp.server.generic.IPublishControllerWithOptions;
import org.jboss.tools.rsp.server.generic.servertype.GenericServerBehavior;
import org.jboss.tools.rsp.server.spi.servertype.IServer;
import org.jboss.tools.rsp.server.spi.servertype.IServerDelegate;
import org.jboss.tools.rsp.server.spi.servertype.IServerWorkingCopy;
import org.jboss.tools.rsp.server.spi.util.StatusConverter;
import org.jboss.tools.rsp.server.was.servertype.impl.IWASServerAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server delegate for WAS traditional (8.5 / 9.0).
 *
 * Design decision (documented here on purpose, so it's easy to challenge
 * later): rather than trying to reverse-engineer WAS's internal java launch
 * command (com.ibm.ws.bootstrap.WSLauncher + a version-specific classpath,
 * which is what startServer.sh itself builds dynamically), start()/stop()
 * are fully overridden here to just call the profile's own startServer.sh /
 * stopServer.sh (or .bat) as an external process. That is IBM's own
 * supported entry point and does not change between fix packs, so it is far
 * less likely to break silently than re-implementing the classpath by hand.
 *
 * The trade-off: we lose the framework's generic ILaunch/IProcess console
 * wiring that Tomcat/GlassFish get "for free" via the java-launch mechanism
 * (see servers.json - startup/shutdown are declared "noOp" there, since
 * they are unused; the real logic lives here). A later iteration could
 * still wire process output into the RSP process/console model - marked as
 * a TODO below.
 */
public class WASServerDelegate extends GenericServerBehavior implements IServerDelegate {

	private static final Logger LOG = LoggerFactory.getLogger(WASServerDelegate.class);
	private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
	private static final String DEBUG_MODE = "debug";

	public WASServerDelegate(IServer server, JSONMemento behaviorMemento) {
		super(server, behaviorMemento);
	}

	@Override
	public void setDependentDefaults(IServerWorkingCopy server) {
		// Intentionally not calling setJavaLaunchDependentDefaults(): that helper
		// assumes a java-launch start/stop, which this adapter does not use.
	}

	@Override
	public StartServerResponse start(String mode) {
		setServerState(IServerDelegate.STATE_STARTING, true);
		File script = resolveScript("startServer");
		String serverName = getServer().getAttribute(IWASServerAttributes.WAS_SERVER_NAME, "server1");

		if (script == null || !script.isFile()) {
			setServerState(IServerDelegate.STATE_STOPPED, true);
			IStatus err = new Status(IStatus.ERROR, Activator.BUNDLE_ID,
					"Could not find " + (script == null ? "startServer script" : script.getAbsolutePath())
					+ " - check the server.home.dir attribute (must be the profile home).");
			return new StartServerResponse(StatusConverter.convert(err), null);
		}

		boolean debug = DEBUG_MODE.equals(mode);
		if (debug) {
			IStatus jdwpStatus = enableJdwp(serverName);
			if (!jdwpStatus.isOK()) {
				setServerState(IServerDelegate.STATE_STOPPED, true);
				return new StartServerResponse(StatusConverter.convert(jdwpStatus), null);
			}
		}

		EXECUTOR.submit(() -> runScriptThenPoll(script, serverName, true));
		if (debug) {
			int timeoutSeconds = getServer().getAttribute(IWASServerAttributes.WAS_POLL_TIMEOUT_SECONDS, 120);
			EXECUTOR.submit(() -> watchForJdwpBanner(serverName, timeoutSeconds));
		}
		CommandLineDetails details = debug ? buildDebugDetails() : null;
		return new StartServerResponse(StatusConverter.convert(Status.OK_STATUS), details);
	}

	/**
	 * The "Listening for transport dt_socket" banner that rsp-ui watches for
	 * (to know when it's safe to attach the debugger) is printed by the actual
	 * server JVM to native_stdout.log, not by the startServer.sh launcher tool
	 * whose output we already relay in logProcessOutput() - that's a detached
	 * process. So it needs its own small tail-follow.
	 */
	private void watchForJdwpBanner(String serverName, int timeoutSeconds) {
		String profileHome = getServer().getAttribute(IWASServerAttributes.WAS_PROFILE_HOME, "");
		File logFile = new File(new File(new File(profileHome, "logs"), serverName), "native_stdout.log");
		long readFrom = logFile.isFile() ? logFile.length() : 0;
		long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;

		while (System.currentTimeMillis() < deadline) {
			if (logFile.isFile()) {
				try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
					raf.seek(readFrom);
					String line;
					while ((line = raf.readLine()) != null) {
						if (line.contains("Listening for transport dt_socket")) {
							fireServerStreamAppended("startServer", line);
							return;
						}
					}
					readFrom = raf.getFilePointer();
				} catch (IOException e) {
					// log file may be rotating - just retry on the next iteration.
				}
			}
			try {
				Thread.sleep(500);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	@Override
	public CommandLineDetails getStartLaunchCommand(String mode, ServerAttributes params) {
		return DEBUG_MODE.equals(mode) ? buildDebugDetails() : null;
	}

	private CommandLineDetails buildDebugDetails() {
		String host = getServer().getAttribute(IWASServerAttributes.WAS_HOST, "localhost");
		String port = getServer().getAttribute(IWASServerAttributes.WAS_DEBUG_PORT, "7777");
		Map<String, String> props = new HashMap<>();
		props.put(ServerManagementAPIConstants.DEBUG_DETAILS_HOST, host);
		props.put(ServerManagementAPIConstants.DEBUG_DETAILS_PORT, port);
		props.put(ServerManagementAPIConstants.DEBUG_DETAILS_TYPE, "java");
		return new CommandLineDetails(null, null, null, props);
	}

	/**
	 * Enables the JDWP debug agent on server1's JVM before launch, via an offline
	 * wsadmin connection (-conntype NONE) that edits the profile's local config
	 * directly - there is no SOAP admin service to connect to yet since the
	 * server is still stopped at this point. JVM args only take effect on the
	 * next launch, which is exactly what's about to happen.
	 */
	private IStatus enableJdwp(String serverName) {
		String node = getServer().getAttribute(IWASServerAttributes.WAS_NODE_NAME, "");
		String port = getServer().getAttribute(IWASServerAttributes.WAS_DEBUG_PORT, "7777");
		String serverPath = (node.isEmpty() ? "" : "/Node:" + node) + "/Server:" + serverName + "/";

		String script = ""
				+ "serverId = AdminConfig.getid('" + serverPath + "')\n"
				+ "jvmId = AdminConfig.list('JavaVirtualMachine', serverId)\n"
				+ "AdminConfig.modify(jvmId, [['debugMode', 'true'], ['debugArgs', "
				+ "'-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=" + port + "']])\n"
				+ "AdminConfig.save()\n"
				+ "print 'WAS-RSP-ADAPTER: JDWP debug agent enabled on port " + port + "'\n";

		try {
			WsadminRunner.WsadminResult result = WsadminRunner.forServer(getServer()).runLocalScript(script);
			LOG.info("wsadmin local (enable debug) output:\n{}", result.output);
			if (!result.success) {
				return new Status(IStatus.ERROR, Activator.BUNDLE_ID,
						"Failed to enable the JVM debug agent via wsadmin (exit " + result.exitCode
						+ "). See log for details.");
			}
			return Status.OK_STATUS;
		} catch (Exception e) {
			LOG.error("Failed to enable the JVM debug agent", e);
			return new Status(IStatus.ERROR, Activator.BUNDLE_ID,
					"Failed to enable the JVM debug agent: " + e.getMessage());
		}
	}

	@Override
	public IStatus stop(boolean force) {
		setServerState(IServerDelegate.STATE_STOPPING, true);
		File script = resolveScript("stopServer");
		String serverName = getServer().getAttribute(IWASServerAttributes.WAS_SERVER_NAME, "server1");

		if (script == null || !script.isFile()) {
			IStatus err = new Status(IStatus.ERROR, Activator.BUNDLE_ID,
					"Could not find " + (script == null ? "stopServer script" : script.getAbsolutePath()));
			setServerState(IServerDelegate.STATE_STARTED, true);
			return err;
		}

		EXECUTOR.submit(() -> runScriptThenPoll(script, serverName, false));
		return Status.OK_STATUS;
	}

	/** Runs startServer.sh/stopServer.sh <serverName> and then polls the HTTP port until it flips state. */
	private void runScriptThenPoll(File script, String serverName, boolean starting) {
		try {
			ProcessBuilder pb = new ProcessBuilder(script.getAbsolutePath(), serverName);
			pb.directory(script.getParentFile());
			pb.redirectErrorStream(true);
			Process p = pb.start();
			// Drain output so the process never blocks on a full pipe; also handy for
			// troubleshooting via the bundle's logger until real console wiring exists.
			new Thread(() -> logProcessOutput(p, starting)).start();
			p.waitFor();
		} catch (IOException | InterruptedException e) {
			LOG.error("Failed to run {}", script.getAbsolutePath(), e);
			setServerState(starting ? IServerDelegate.STATE_STOPPED : IServerDelegate.STATE_STARTED, true);
			return;
		}

		String host = getServer().getAttribute(IWASServerAttributes.WAS_HOST, "localhost");
		int httpPort = getServer().getAttribute(IWASServerAttributes.WAS_HTTP_PORT, 9080);
		int timeoutSeconds = getServer().getAttribute(IWASServerAttributes.WAS_POLL_TIMEOUT_SECONDS, 120);

		boolean up = pollPort(host, httpPort, timeoutSeconds, starting);
		if (starting) {
			setServerState(up ? IServerDelegate.STATE_STARTED : IServerDelegate.STATE_STOPPED, true);
		} else {
			// For shutdown we don't require the port to close (some topologies keep a
			// front-end port open); trust the script's exit and just mark stopped.
			setServerState(IServerDelegate.STATE_STOPPED, true);
		}
	}

	/** Polls host:port until it accepts (waitingForUp=true) or refuses (waitingForUp=false) connections. */
	private boolean pollPort(String host, int port, int timeoutSeconds, boolean waitingForUp) {
		long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
		while (System.currentTimeMillis() < deadline) {
			boolean open = isPortOpen(host, port);
			if (open == waitingForUp) {
				return true;
			}
			try {
				Thread.sleep(2000);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		LOG.warn("Timed out after {}s waiting for {}:{} to be {}", timeoutSeconds, host, port,
				waitingForUp ? "up" : "down");
		return false;
	}

	private boolean isPortOpen(String host, int port) {
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress(host, port), 2000);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	private void logProcessOutput(Process p, boolean starting) {
		String processId = starting ? "startServer" : "stopServer";
		try (java.io.BufferedReader r = new java.io.BufferedReader(
				new java.io.InputStreamReader(p.getInputStream()))) {
			String line;
			while ((line = r.readLine()) != null) {
				LOG.info("[{}] {}", processId, line);
				// Relayed to the client so its "Listening for transport dt_socket" watcher
				// (used to auto-attach a Java debugger once "debug" mode is started) can see it -
				// this delegate uses its own ProcessBuilder instead of the framework's
				// generic java-launch mechanism, so nothing else forwards this output.
				fireServerStreamAppended(processId, line);
			}
		} catch (IOException e) {
			// Process ended, pipe closed - nothing to do.
		}
	}

	private void fireServerStreamAppended(String processId, String line) {
		try {
			LauncherSingleton.getDefault().getLauncher().getModel().getServerModel()
					.fireServerStreamAppended(getServer(), processId,
							ServerManagementAPIConstants.STREAM_TYPE_SYSOUT, line + System.lineSeparator());
		} catch (Exception e) {
			LOG.debug("Could not relay process output to clients", e);
		}
	}

	private File resolveScript(String baseName) {
		String profileHome = getServer().getAttribute(IWASServerAttributes.WAS_PROFILE_HOME, (String) null);
		if (profileHome == null || profileHome.isEmpty()) {
			return null;
		}
		boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
		String fileName = baseName + (windows ? ".bat" : ".sh");
		return new File(new File(profileHome, "bin"), fileName);
	}

	@Override
	protected IPublishControllerWithOptions createPublishController() {
		return new WASPublishController(getServer(), this);
	}
}
