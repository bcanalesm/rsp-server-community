/*******************************************************************************
 * Copyright (c) 2026. Distributed under license by the project contributors.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 ******************************************************************************/
package org.jboss.tools.rsp.server.was.impl;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.tools.rsp.launching.utils.FileUtil;
import org.jboss.tools.rsp.server.spi.discovery.ServerBeanType;
import org.jboss.tools.rsp.server.was.servertype.impl.IWASServerAttributes;

/**
 * Detects a WAS traditional profile home from disk (used by the "add server
 * from disk" wizard) and picks the 8.5 vs 9.0 adapter type.
 *
 * WAS profiles record their real version in properties/version/profile.version,
 * but that file is XML (e.g. {@code <version>8.5.5.18</version>}), and none of
 * the generic discovery mechanisms available via servers.json ("manifest",
 * "jarManifest", "properties" - see GenericServerTypeExtensionModel) can parse
 * XML content. Hence a small hand-rolled ServerBeanType instead of a
 * declarative "discoveries" block in servers.json.
 */
public class WASServerBeanType extends ServerBeanType {

	private static final Pattern VERSION_PATTERN = Pattern.compile("<version>\\s*([0-9.]+)\\s*</version>");

	public WASServerBeanType() {
		super("was.traditional.discovery", "IBM WebSphere Application Server (traditional)");
	}

	@Override
	public boolean isServerRoot(File location) {
		return hasStartScript(location) && getFullVersion(location) != null;
	}

	private boolean hasStartScript(File location) {
		return new File(location, "bin/startServer.sh").isFile()
				|| new File(location, "bin/startServer.bat").isFile();
	}

	@Override
	public String getFullVersion(File root) {
		File versionFile = new File(root, "properties/version/profile.version");
		if (!versionFile.isFile()) {
			return null;
		}
		try {
			String contents = FileUtil.getContents(versionFile);
			Matcher m = VERSION_PATTERN.matcher(contents);
			if (m.find()) {
				return m.group(1);
			}
		} catch (Exception e) {
			// not a WAS profile, or unreadable - treated as no match
		}
		return null;
	}

	@Override
	public String getUnderlyingTypeId(File root) {
		return getId();
	}

	@Override
	public String getServerAdapterTypeId(String version) {
		if (version != null && version.startsWith("9.")) {
			return IWASServerAttributes.WAS_SERVER_TYPE_PREFIX + "90";
		}
		return IWASServerAttributes.WAS_SERVER_TYPE_PREFIX + "85";
	}
}
