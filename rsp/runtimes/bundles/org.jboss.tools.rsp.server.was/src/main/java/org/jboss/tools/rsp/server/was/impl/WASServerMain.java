/*******************************************************************************
 * Copyright (c) 2026. Distributed under license by the project contributors.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 ******************************************************************************/
package org.jboss.tools.rsp.server.was.impl;

import org.jboss.tools.rsp.server.LauncherSingleton;
import org.jboss.tools.rsp.server.ServerManagementServerLauncher;
import org.jboss.tools.rsp.server.generic.GenericServerExtensionModel;

/**
 * Standalone entry point, useful for running/debugging this single adapter
 * outside of the full OSGi distribution (see the exec-maven-plugin config
 * in pom.xml). Mirrors GlassfishServerMain / LibertyServerMain.
 */
public class WASServerMain extends ServerManagementServerLauncher {

	public static void main(String[] args) throws Exception {
		WASServerMain instance = new WASServerMain(args.length > 0 ? args[0] : "27512");
		LauncherSingleton.getDefault().setLauncher(instance);
		instance.launch();
		instance.shutdownOnInput();
	}

	public WASServerMain(String port) {
		super(port);
	}

	@Override
	public void launch(int port) throws Exception {
		GenericServerExtensionModel model = new GenericServerExtensionModel(serverImpl.getModel(),
				Activator.getDelegateProviderImpl(),
				Activator.getServerTypeModelStreamImpl());
		model.registerExtensions();
		super.launch(port);
	}
}
