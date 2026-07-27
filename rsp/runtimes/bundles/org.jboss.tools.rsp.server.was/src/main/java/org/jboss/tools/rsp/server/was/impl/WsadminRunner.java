/*******************************************************************************
 * Copyright (c) 2026. Distributed under license by the project contributors.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 ******************************************************************************/
package org.jboss.tools.rsp.server.was.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shells out to the profile's {@code wsadmin.sh}/{@code wsadmin.bat}, feeding it
 * a generated Jython script, and reports back whether it succeeded.
 *
 * This intentionally avoids linking against IBM's proprietary JMX admin client
 * jars (com.ibm.ws.admin.client_*) so the adapter has no compile/runtime
 * dependency on a WAS installation - it only needs wsadmin to be reachable as
 * an executable inside the configured profile, which is true for every
 * WAS 8.5 / 9.0 profile out of the box.
 *
 * NOTE: this is deliberately simple (synchronous, one wsadmin process per
 * call). AdminApp installs on WAS traditional can take anywhere from a few
 * seconds to a couple of minutes depending on the app and topology; callers
 * should run this off the RSP dispatch thread.
 */
public class WsadminRunner {

	private static final Logger LOG = LoggerFactory.getLogger(WsadminRunner.class);

	private final String profileHome;
	private final String host;
	private final String soapPort;
	private final String adminUser;
	private final String adminPassword;
	private final String lang;

	public WsadminRunner(String profileHome, String host, String soapPort,
			String adminUser, String adminPassword, String lang) {
		this.profileHome = profileHome;
		this.host = host;
		this.soapPort = soapPort;
		this.adminUser = adminUser;
		this.adminPassword = adminPassword;
		this.lang = (lang == null || lang.isEmpty()) ? "jython" : lang;
	}

	public static class WsadminResult {
		public final boolean success;
		public final String output;
		public final int exitCode;

		public WsadminResult(boolean success, String output, int exitCode) {
			this.success = success;
			this.output = output;
			this.exitCode = exitCode;
		}
	}

	private File resolveWsadminExecutable() {
		boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
		String name = windows ? "wsadmin.bat" : "wsadmin.sh";
		return new File(new File(profileHome, "bin"), name);
	}

	/**
	 * Runs the given Jython snippet through wsadmin -f &lt;tmpfile&gt; and
	 * returns the combined stdout/stderr plus success flag (exit code 0).
	 */
	public WsadminResult runScript(String jythonScript) throws IOException, InterruptedException {
		File wsadmin = resolveWsadminExecutable();
		if (!wsadmin.isFile()) {
			return new WsadminResult(false,
					"wsadmin executable not found at " + wsadmin.getAbsolutePath()
					+ " - check the profile home (server.home.dir) attribute", -1);
		}

		File scriptFile = Files.createTempFile("was-rsp-adapter-", ".py").toFile();
		Files.write(scriptFile.toPath(), jythonScript.getBytes(StandardCharsets.UTF_8));

		List<String> cmd = new ArrayList<>();
		cmd.add(wsadmin.getAbsolutePath());
		cmd.add("-lang");
		cmd.add(lang);
		cmd.add("-conntype");
		cmd.add("SOAP");
		if (host != null && !host.isEmpty()) {
			cmd.add("-host");
			cmd.add(host);
		}
		if (soapPort != null && !soapPort.isEmpty()) {
			cmd.add("-port");
			cmd.add(soapPort);
		}
		if (adminUser != null && !adminUser.isEmpty()) {
			cmd.add("-user");
			cmd.add(adminUser);
			cmd.add("-password");
			cmd.add(adminPassword == null ? "" : adminPassword);
		}
		cmd.add("-f");
		cmd.add(scriptFile.getAbsolutePath());

		LOG.info("Running wsadmin: {}", redactPassword(cmd));

		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.directory(new File(profileHome, "bin"));
		pb.redirectErrorStream(true);
		Process process;
		try {
			process = pb.start();
		} finally {
			// best effort cleanup even if start() throws
		}

		StringBuilder output = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				output.append(line).append(System.lineSeparator());
			}
		}
		int exit = process.waitFor();
		scriptFile.delete();

		boolean success = exit == 0 && !output.toString().contains("WASX7015E");
		return new WsadminResult(success, output.toString(), exit);
	}

	private List<String> redactPassword(List<String> cmd) {
		List<String> copy = new ArrayList<>(cmd);
		int idx = copy.indexOf("-password");
		if (idx >= 0 && idx + 1 < copy.size()) {
			copy.set(idx + 1, "****");
		}
		return copy;
	}
}
