/*******************************************************************************
 * Copyright (c) 2026. Distributed under license by the project contributors.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 ******************************************************************************/
package org.jboss.tools.rsp.server.was.servertype.impl;

import org.jboss.tools.rsp.api.DefaultServerAttributes;

/**
 * Attribute keys understood by the WAS (traditional) server adapter.
 * These mirror the "required"/"optional"/"staticDefaults" entries declared
 * in servers.json for the "was.template" template.
 */
public interface IWASServerAttributes extends DefaultServerAttributes {

	/** Prefix shared by every WAS traditional server type id declared in servers.json */
	public static final String WAS_SERVER_TYPE_PREFIX = "com.ibm.was.server.";

	/**
	 * Required. Filesystem path to the WAS *profile* home, e.g.
	 * {@code /opt/IBM/WebSphere/AppServer/profiles/AppSrv01}.
	 * This is the directory that contains bin/startServer.sh, bin/stopServer.sh
	 * and bin/wsadmin.sh for this profile.
	 */
	public static final String WAS_PROFILE_HOME = DefaultServerAttributes.SERVER_HOME_DIR;

	/** Required. The application server name inside the profile, e.g. "server1". */
	public static final String WAS_SERVER_NAME = "was.server.name";

	/** Optional. Node name; only needed for AdminApp targeting on ND cells. */
	public static final String WAS_NODE_NAME = "was.node.name";

	/** Optional. Cell name; informational, only needed for ND topologies. */
	public static final String WAS_CELL_NAME = "was.cell.name";

	/** Host wsadmin/the admin console connects to. Defaults to localhost. */
	public static final String WAS_HOST = "was.host";

	/** SOAP connector port used by wsadmin. Defaults to 8880 (Base/ND default). */
	public static final String WAS_SOAP_PORT = "was.soap.port";

	/** HTTP transport port used to poll whether the server is up. Defaults to 9080. */
	public static final String WAS_HTTP_PORT = "was.http.port";

	/** Optional. Admin console/wsadmin user, only required when administrative security is enabled. */
	public static final String WAS_ADMIN_USER = "was.admin.user";

	/** Optional, secret. Admin console/wsadmin password. */
	public static final String WAS_ADMIN_PASSWORD = "was.admin.password";

	/** Optional. Scripting language passed to wsadmin via -lang. Defaults to "jython". */
	public static final String WAS_WSADMIN_LANG = "was.wsadmin.lang";

	/** Optional. Number of seconds to wait for the server to report up/down before giving up. */
	public static final String WAS_POLL_TIMEOUT_SECONDS = "was.poll.timeout.seconds";
}
