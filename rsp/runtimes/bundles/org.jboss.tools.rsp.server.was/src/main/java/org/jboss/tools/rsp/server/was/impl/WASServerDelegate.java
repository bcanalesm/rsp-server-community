/*******************************************************************************
 * Copyright (c) 2026. Distributed under license by the project contributors.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 ******************************************************************************/
package org.jboss.tools.rsp.server.was.impl;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jboss.tools.rsp.api.dao.StartServerResponse;
import org.jboss.tools.rsp.eclipse.core.runtime.IStatus;
import org.jboss.tools.rsp.eclipse.core.runtime.Status;
import org.jboss.tools.rsp.launching.memento.JSONMemento;
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

		EXECUTOR.submit(() -> runScriptThenPoll(script, serverName, true));
		return new StartServerResponse(StatusConverter.convert(Status.OK_STATUS), null);
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
		try (java.io.BufferedReader r = new java.io.BufferedReader(
				new java.io.InputStreamReader(p.getInputStream()))) {
			String line;
			while ((line = r.readLine()) != null) {
				LOG.info("[{}] {}", starting ? "startServer" : "stopServer", line);
			}
		} catch (IOException e) {
			// Process ended, pipe closed - nothing to do.
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
