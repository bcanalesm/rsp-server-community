/*******************************************************************************
 * Copyright (c) 2026. Distributed under license by the project contributors.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 ******************************************************************************/
package org.jboss.tools.rsp.server.was.impl;

import java.io.InputStream;

import org.jboss.tools.rsp.launching.memento.JSONMemento;
import org.jboss.tools.rsp.server.ServerCoreActivator;
import org.jboss.tools.rsp.server.generic.GenericServerActivator;
import org.jboss.tools.rsp.server.generic.IServerBehaviorFromJSONProvider;
import org.jboss.tools.rsp.server.generic.IServerBehaviorProvider;
import org.jboss.tools.rsp.server.spi.servertype.IServer;
import org.jboss.tools.rsp.server.spi.servertype.IServerDelegate;
import org.jboss.tools.rsp.server.was.servertype.impl.IWASServerAttributes;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Activator extends GenericServerActivator {
	public static final String BUNDLE_ID = "org.jboss.tools.rsp.server.was";
	private static final Logger LOG = LoggerFactory.getLogger(Activator.class);

	@Override
	public void start(BundleContext context) throws Exception {
		LOG.info("Bundle {} starting...", context.getBundle().getSymbolicName());
		addExtensions(ServerCoreActivator.BUNDLE_ID, context);
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		LOG.info("Bundle {} stopping...", context.getBundle().getSymbolicName());
		removeExtensions(ServerCoreActivator.BUNDLE_ID, context);
	}

	@Override
	protected String getBundleId() {
		return BUNDLE_ID;
	}

	@Override
	protected InputStream getServerTypeModelStream() {
		return getServerTypeModelStreamImpl();
	}

	public static final InputStream getServerTypeModelStreamImpl() {
		return Activator.class.getResourceAsStream("/servers.json");
	}

	protected IServerBehaviorFromJSONProvider getDelegateProvider() {
		return getDelegateProviderImpl();
	}

	public static IServerBehaviorFromJSONProvider getDelegateProviderImpl() {
		return new IServerBehaviorFromJSONProvider() {
			@Override
			public IServerBehaviorProvider loadBehaviorFromJSON(String serverTypeId, JSONMemento behaviorMemento) {
				return new IServerBehaviorProvider() {
					@Override
					public IServerDelegate createServerDelegate(String typeId, IServer server) {
						if (typeId.startsWith(IWASServerAttributes.WAS_SERVER_TYPE_PREFIX)) {
							return new WASServerDelegate(server, behaviorMemento);
						}
						return null;
					}
				};
			}
		};
	}
}
