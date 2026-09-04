/*******************************************************************************
 * Copyright (c) 2026. Distributed under license by the project contributors.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 ******************************************************************************/
package org.jboss.tools.rsp.server.was.impl;

import org.jboss.tools.rsp.api.ServerManagementAPIConstants;
import org.jboss.tools.rsp.server.LauncherSingleton;
import org.jboss.tools.rsp.server.spi.servertype.IServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pushes a line of process/script output to every connected RSP client (the
 * VS Code "Server: <name>" Output channel among them), instead of leaving it
 * only in the bundle's own SLF4J log file. This adapter shells out to
 * wsadmin/startServer.sh/stopServer.sh directly with its own ProcessBuilder
 * calls rather than the framework's generic java-launch mechanism, so
 * nothing else does this relaying automatically.
 */
final class RspOutputRelay {

	private static final Logger LOG = LoggerFactory.getLogger(RspOutputRelay.class);

	private RspOutputRelay() {
	}

	static void appendLine(IServer server, String processId, String line) {
		try {
			LauncherSingleton.getDefault().getLauncher().getModel().getServerModel()
					.fireServerStreamAppended(server, processId,
							ServerManagementAPIConstants.STREAM_TYPE_SYSOUT, line + System.lineSeparator());
		} catch (Exception e) {
			LOG.debug("Could not relay process output to clients", e);
		}
	}
}
