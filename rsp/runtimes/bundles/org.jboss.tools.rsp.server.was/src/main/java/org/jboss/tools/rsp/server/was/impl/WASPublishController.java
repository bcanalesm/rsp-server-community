/*******************************************************************************
 * Copyright (c) 2026. Distributed under license by the project contributors.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 ******************************************************************************/
package org.jboss.tools.rsp.server.was.impl;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.tools.rsp.api.ServerManagementAPIConstants;
import org.jboss.tools.rsp.api.dao.Attributes;
import org.jboss.tools.rsp.api.dao.DeployableReference;
import org.jboss.tools.rsp.api.dao.util.CreateServerAttributesUtility;
import org.jboss.tools.rsp.eclipse.core.runtime.CoreException;
import org.jboss.tools.rsp.eclipse.core.runtime.IStatus;
import org.jboss.tools.rsp.eclipse.core.runtime.Status;
import org.jboss.tools.rsp.server.generic.IPublishControllerWithOptions;
import org.jboss.tools.rsp.server.spi.servertype.IServer;
import org.jboss.tools.rsp.server.spi.servertype.IServerDelegate;
import org.jboss.tools.rsp.server.was.servertype.impl.IWASServerAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publish controller for WAS traditional.
 *
 * Unlike Tomcat/GlassFish/Liberty (which "deploy" by copying a WAR into a
 * hot-deploy folder), WAS traditional has no such folder: every deploy has
 * to go through the admin layer (AdminApp/AdminConfig), which is why this
 * class exists instead of reusing GenericServerSuffixPublishController.
 *
 * Every publishModule() call is self-contained: it builds a small Jython
 * script and hands it to WsadminRunner. That keeps the logic easy to follow
 * and test in isolation, at the cost of being slower than a real JMX
 * connection (each call pays the JVM start-up cost of wsadmin itself).
 */
public class WASPublishController implements IPublishControllerWithOptions {

	private static final Logger LOG = LoggerFactory.getLogger(WASPublishController.class);

	// WebSphere's config repository uses an optimistic, workspace-based save -
	// two wsadmin processes touching the same profile concurrently (e.g. removing
	// two deployables at once, or a manual publish racing the auto-publish thread)
	// can fail with a ConfigServiceException/WorkSpaceException. Each publishModule()
	// call spawns its own wsadmin process, so serialize them per WAS profile.
	private static final Map<String, Object> WSADMIN_LOCKS = new ConcurrentHashMap<>();

	private final IServer server;
	private final IServerDelegate delegate;

	public WASPublishController(IServer server, IServerDelegate delegate) {
		this.server = server;
		this.delegate = delegate;
	}

	@Override
	public Attributes listDeploymentOptions() {
		CreateServerAttributesUtility util = new CreateServerAttributesUtility();
		util.addAttribute(ServerManagementAPIConstants.DEPLOYMENT_OPTION_OUTPUT_NAME,
				ServerManagementAPIConstants.ATTR_TYPE_STRING,
				"Application name to use inside WAS (defaults to the file name without extension).",
				null);
		return util.toPojo();
	}

	@Override
	public IStatus canAddDeployable(DeployableReference ref) {
		return validate(ref);
	}

	@Override
	public IStatus canRemoveDeployable(DeployableReference ref) {
		return Status.OK_STATUS;
	}

	@Override
	public IStatus canPublish() {
		return Status.OK_STATUS;
	}

	@Override
	public void publishStart(int publishType) throws CoreException {
		// Nothing to prepare: each module publish is a self-contained wsadmin call.
	}

	@Override
	public void publishFinish(int publishType) throws CoreException {
		// Nothing to finalize for the same reason.
	}

	@Override
	public int publishModule(DeployableReference reference, int publishRequestType, int modulePublishState)
			throws CoreException {
		String appName = getAppName(reference);
		WsadminRunner runner = createRunner();

		try {
			if (modulePublishState == ServerManagementAPIConstants.PUBLISH_STATE_REMOVE) {
				return uninstall(runner, appName);
			}
			return installOrUpdate(runner, reference, appName);
		} catch (Exception e) {
			LOG.error("Publish failed for {} on {}", appName, server.getName(), e);
			// Keep whatever the last known publish state was rather than crash the
			// whole RSP session - the user will see the failure via the returned status
			// on the client side once error propagation is wired up.
			return delegate.getServerPublishModel().getDeployableState(reference).getPublishState();
		}
	}

	private int installOrUpdate(WsadminRunner runner, DeployableReference reference, String appName)
			throws Exception {
		IStatus valid = validate(reference);
		if (!valid.isOK()) {
			throw new CoreException(valid);
		}

		String path = new File(reference.getPath()).getAbsolutePath().replace("\\", "\\\\");
		String node = server.getAttribute(IWASServerAttributes.WAS_NODE_NAME, "");
		String serverName = server.getAttribute(IWASServerAttributes.WAS_SERVER_NAME, "server1");

		String nodeOpt = node.isEmpty() ? "" : " -node " + node;

		// Jython: install if new, update (fullyReplace) if the app already exists.
		String script = ""
				+ "appName = '" + appName + "'\n"
				+ "path = '" + path + "'\n"
				// AdminApp.exists() isn't present in every WAS 8.5.x fix pack; AdminApp.list()
				// has been available since early WAS versions, so check membership there instead.
				+ "exists = appName in AdminApp.list().splitlines()\n"
				+ "if exists:\n"
				+ "    print 'WAS-RSP-ADAPTER: updating existing application ' + appName\n"
				+ "    AdminApp.update(appName, 'app', '[-operation update -contents ' + path + ']')\n"
				+ "else:\n"
				+ "    print 'WAS-RSP-ADAPTER: installing new application ' + appName\n"
				+ "    AdminApp.install(path, '[-appname ' + appName + '" + nodeOpt + " -server " + serverName
				// Without an explicit virtual host mapping, AdminApp.install fails with
				// ADMA0010E ("A virtual host is not specified for web module ...").
				// The console fills this in for you; scripting doesn't, so wildcard-map
				// every web module to default_host.
				+ " -contextroot /' + appName + ' -MapWebModToVH [[ .* .* default_host ]]]')\n"
				+ "AdminConfig.save()\n"
				+ "# Best-effort start; ignore failures if the server isn't running yet.\n"
				+ "try:\n"
				+ "    mgr = AdminControl.queryNames('type=ApplicationManager,process=" + serverName + ",*')\n"
				+ "    if len(mgr) > 0:\n"
				+ "        AdminControl.invoke(mgr, 'startApplication', appName)\n"
				+ "        print 'WAS-RSP-ADAPTER: start requested for ' + appName\n"
				+ "except:\n"
				+ "    print 'WAS-RSP-ADAPTER: server not running, skipped start of ' + appName\n";

		WsadminRunner.WsadminResult result;
		synchronized (wsadminLock()) {
			result = runner.runScript(script);
		}
		LOG.info("wsadmin install/update output for {}:\n{}", appName, result.output);
		if (!result.success) {
			throw new CoreException(new Status(IStatus.ERROR, Activator.BUNDLE_ID,
					"wsadmin failed to install/update " + appName + " (exit " + result.exitCode + "). See log for details."));
		}
		return ServerManagementAPIConstants.PUBLISH_STATE_NONE;
	}

	private Object wsadminLock() {
		String profileHome = server.getAttribute(IWASServerAttributes.WAS_PROFILE_HOME, "");
		return WSADMIN_LOCKS.computeIfAbsent(profileHome, k -> new Object());
	}

	private int uninstall(WsadminRunner runner, String appName) throws Exception {
		String script = ""
				+ "appName = '" + appName + "'\n"
				+ "if appName in AdminApp.list().splitlines():\n"
				+ "    AdminApp.uninstall(appName)\n"
				+ "    AdminConfig.save()\n"
				+ "    print 'WAS-RSP-ADAPTER: uninstalled ' + appName\n"
				+ "else:\n"
				+ "    print 'WAS-RSP-ADAPTER: ' + appName + ' was already absent'\n";

		WsadminRunner.WsadminResult result;
		synchronized (wsadminLock()) {
			result = runner.runScript(script);
		}
		LOG.info("wsadmin uninstall output for {}:\n{}", appName, result.output);
		if (!result.success) {
			throw new CoreException(new Status(IStatus.ERROR, Activator.BUNDLE_ID,
					"wsadmin failed to uninstall " + appName + " (exit " + result.exitCode + "). See log for details."));
		}
		return ServerManagementAPIConstants.PUBLISH_STATE_NONE;
	}

	private WsadminRunner createRunner() {
		return WsadminRunner.forServer(server);
	}

	private String getAppName(DeployableReference ref) {
		Map<String, Object> options = ref.getOptions();
		String k = ServerManagementAPIConstants.DEPLOYMENT_OPTION_OUTPUT_NAME;
		if (options != null && options.get(k) != null) {
			return stripExtension(baseName((String) options.get(k)));
		}
		if (ref.getLabel() != null && !ref.getLabel().isEmpty()) {
			return stripExtension(baseName(ref.getLabel()));
		}
		return stripExtension(new File(ref.getPath()).getName());
	}

	/**
	 * The label/output-name coming from the client is sometimes a bare name and
	 * sometimes a full filesystem path (rsp-ui sets label = path for local
	 * deployables), so always reduce to the base file name before using it as
	 * a WAS application name - AdminApp.install rejects names containing path
	 * separators or a drive-letter colon.
	 */
	private String baseName(String pathOrName) {
		return new File(pathOrName).getName();
	}

	private String stripExtension(String name) {
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	private IStatus validate(DeployableReference ref) {
		String path = ref.getPath();
		if (path == null) {
			return new Status(IStatus.ERROR, Activator.BUNDLE_ID, "Deployable has no filesystem path.");
		}
		File f = new File(path);
		if (!f.exists()) {
			return new Status(IStatus.ERROR, Activator.BUNDLE_ID, "File does not exist: " + path);
		}
		if (!path.endsWith(".war") && !path.endsWith(".ear")) {
			return new Status(IStatus.ERROR, Activator.BUNDLE_ID,
					"Only .war and .ear deployables are supported by this MVP adapter: " + path);
		}
		return Status.OK_STATUS;
	}
}
