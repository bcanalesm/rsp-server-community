/*-----------------------------------------------------------------------------------------------
 *  Copyright (c) Red Hat, Inc. All rights reserved.
 *  Licensed under the EPL v2.0 License. See LICENSE file in the project root for license information.
 *-----------------------------------------------------------------------------------------------*/

import * as cp from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import * as vscode from 'vscode';
import { Protocol, RSPClient, ServerState } from 'rsp-client';
import { FelixRspController } from './controller';

const OUTPUT_CHANNEL_NAME = 'WAS Build';
const STATE_POLL_INTERVAL_MS = 1000;
const STATE_WAIT_TIMEOUT_MS = 120000;

/**
 * Builds a Maven project with `mvn clean package`, locates the resulting
 * .ear/.war under target/, then deploys and starts/restarts it directly via
 * our own RSPClient connection.
 *
 * This does NOT delegate to rsp-ui's own "Run on Server"/"Debug on Server"
 * commands: their selectServer() step always shows a quick pick, even when
 * there's only one server to choose from, which makes this command look
 * "stuck" waiting on a picker the user has to notice and click. Talking to
 * the RSP server directly lets us skip that prompt when there's no real
 * ambiguity, and only ask when there's genuinely more than one server.
 */
export function registerBuildAndDeployCommands(context: vscode.ExtensionContext, rspApi: FelixRspController): void {
    context.subscriptions.push(
        vscode.commands.registerCommand('was.buildAndRun', (uri: vscode.Uri) => buildAndDeploy(uri, false, rspApi)),
        vscode.commands.registerCommand('was.buildAndDebug', (uri: vscode.Uri) => buildAndDeploy(uri, true, rspApi))
    );
}

async function buildAndDeploy(uri: vscode.Uri, debug: boolean, rspApi: FelixRspController): Promise<void> {
    if (!uri || !uri.fsPath) {
        vscode.window.showErrorMessage('WAS: select a project folder (containing pom.xml) to build and deploy.');
        return;
    }

    const projectDir = uri.fsPath;
    const pomPath = path.join(projectDir, 'pom.xml');
    if (!fs.existsSync(pomPath)) {
        vscode.window.showErrorMessage(`WAS: no pom.xml found in ${projectDir}.`);
        return;
    }

    const outputChannel = vscode.window.createOutputChannel(OUTPUT_CHANNEL_NAME);
    outputChannel.show(true);
    outputChannel.appendLine(`Running "mvn clean package" in ${projectDir} ...`);

    try {
        await runMaven(projectDir, outputChannel);
    } catch (err) {
        vscode.window.showErrorMessage(`WAS: Maven build failed. See the "${OUTPUT_CHANNEL_NAME}" output for details.`);
        return;
    }

    const artifact = findBuiltArtifact(path.join(projectDir, 'target'));
    if (!artifact) {
        vscode.window.showErrorMessage(
            `WAS: build succeeded, but no .ear or .war was found under ${path.join(projectDir, 'target')}.`);
        return;
    }

    outputChannel.appendLine(`Built ${artifact}`);
    outputChannel.appendLine(debug ? 'Deploying and starting in debug mode...' : 'Deploying and starting...');

    try {
        await deployAndStart(rspApi, artifact, debug, outputChannel);
    } catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        vscode.window.showErrorMessage(`WAS: ${message}`);
        outputChannel.appendLine(`Error: ${message}`);
    }
}

async function deployAndStart(
        rspApi: FelixRspController, artifactPath: string, debug: boolean,
        outputChannel: vscode.OutputChannel): Promise<void> {
    if (!rspApi.getPort()) {
        throw new Error('the RSP server is not running yet - try again in a moment.');
    }

    const client = new RSPClient(rspApi.getHost(), rspApi.getPort());
    await client.connect(5000);
    try {
        const server = await pickServer(client);
        const deployableRef: Protocol.DeployableReference = { label: artifactPath, path: artifactPath };
        try {
            await client.getOutgoingHandler().addDeployable({ server, deployableReference: deployableRef });
        } catch (e) {
            // Already added from a previous run - fine, publish() below picks up the new content either way.
        }

        outputChannel.appendLine(`Publishing to ${server.id}...`);
        // publish() blocks the RPC call itself until the wsadmin work finishes, which
        // routinely exceeds rsp-client's hardcoded 7.5s client-side timeout (unrelated
        // to how long the actual publish takes) - publishAsync() acks immediately
        // instead, so wait for the deployable's own publish state to settle separately.
        await client.getOutgoingHandler().publishAsync({ server, kind: ServerState.PUBLISH_FULL });
        await waitForPublishState(client, server, artifactPath, ServerState.PUBLISH_STATE_NONE);
        outputChannel.appendLine('Publish complete.');

        const mode = debug ? ServerState.RUN_MODE_DEBUG : ServerState.RUN_MODE_RUN;
        const currentState = await client.getOutgoingHandler().getServerState(server);
        if (currentState.state === ServerState.STARTED && currentState.runMode === mode) {
            outputChannel.appendLine('Server already running in the right mode - published in place.');
            return;
        }
        if (currentState.state === ServerState.STARTED) {
            outputChannel.appendLine('Restarting to switch run mode...');
            await client.getOutgoingHandler().stopServerAsync({ id: server.id, force: false });
            await waitForRunState(client, server, ServerState.STOPPED);
        }
        outputChannel.appendLine(`Starting in ${mode} mode...`);
        await client.getOutgoingHandler().startServerAsync({
            mode,
            params: { serverType: server.type.id, id: server.id, attributes: {} }
        });
    } finally {
        client.disconnect();
    }
}

async function pickServer(client: RSPClient): Promise<Protocol.ServerHandle> {
    const handles = await client.getOutgoingHandler().getServerHandles();
    if (!handles || handles.length === 0) {
        throw new Error('no servers are configured - add a WAS server first.');
    }
    if (handles.length === 1) {
        return handles[0];
    }
    const picked = await vscode.window.showQuickPick(
        handles.map((h) => h.id), { placeHolder: 'Select the server to deploy to' });
    if (!picked) {
        throw new Error('no server selected.');
    }
    return handles.find((h) => h.id === picked) as Protocol.ServerHandle;
}

function waitForRunState(client: RSPClient, server: Protocol.ServerHandle, state: number): Promise<void> {
    return new Promise<void>((resolve, reject) => {
        let settled = false;
        const finish = (fn: () => void) => {
            if (!settled) {
                settled = true;
                fn();
            }
        };
        const timer = setTimeout(
            () => finish(() => reject(new Error('timed out waiting for the server to stop.'))), STATE_WAIT_TIMEOUT_MS);

        client.getIncomingHandler().onServerStateChanged((event: Protocol.ServerState) => {
            if (event.server.id === server.id && event.state === state) {
                clearTimeout(timer);
                finish(resolve);
            }
        });

        // In case the state change already happened before the listener above was registered.
        const poll = setInterval(async () => {
            if (settled) {
                clearInterval(poll);
                return;
            }
            const current = await client.getOutgoingHandler().getServerState(server);
            if (current.state === state) {
                clearInterval(poll);
                clearTimeout(timer);
                finish(resolve);
            }
        }, STATE_POLL_INTERVAL_MS);
    });
}

function waitForPublishState(
        client: RSPClient, server: Protocol.ServerHandle, deployablePath: string, publishState: number): Promise<void> {
    const matches = (state: Protocol.ServerState) => {
        const ds = state.deployableStates.find((d) => d.reference.path === deployablePath);
        return ds !== undefined && ds.publishState === publishState;
    };

    return new Promise<void>((resolve, reject) => {
        let settled = false;
        const finish = (fn: () => void) => {
            if (!settled) {
                settled = true;
                fn();
            }
        };
        const timer = setTimeout(
            () => finish(() => reject(new Error('timed out waiting for publish to finish.'))), STATE_WAIT_TIMEOUT_MS);

        client.getIncomingHandler().onServerStateChanged((event: Protocol.ServerState) => {
            if (event.server.id === server.id && matches(event)) {
                clearTimeout(timer);
                finish(resolve);
            }
        });

        // In case the state change already happened before the listener above was registered.
        const poll = setInterval(async () => {
            if (settled) {
                clearInterval(poll);
                return;
            }
            const current = await client.getOutgoingHandler().getServerState(server);
            if (matches(current)) {
                clearInterval(poll);
                clearTimeout(timer);
                finish(resolve);
            }
        }, STATE_POLL_INTERVAL_MS);
    });
}

function runMaven(projectDir: string, outputChannel: vscode.OutputChannel): Promise<void> {
    return new Promise<void>((resolve, reject) => {
        const mvnCmd = process.platform === 'win32' ? 'mvn.cmd' : 'mvn';
        const proc = cp.spawn(mvnCmd, ['clean', 'package'], { cwd: projectDir, shell: true });

        proc.stdout?.on('data', (data) => outputChannel.append(data.toString()));
        proc.stderr?.on('data', (data) => outputChannel.append(data.toString()));

        proc.on('error', (err) => {
            outputChannel.appendLine(`Failed to launch Maven: ${err.message}`);
            reject(err);
        });
        proc.on('close', (code) => {
            if (code === 0) {
                resolve();
            } else {
                reject(new Error(`mvn exited with code ${code}`));
            }
        });
    });
}

/**
 * Prefers an .ear (a full enterprise application) over a bare .war, since a
 * Maven EAR reactor build's ear module target/ also contains the inner .war
 * as an intermediate artifact.
 */
function findBuiltArtifact(targetDir: string): string | null {
    if (!fs.existsSync(targetDir)) {
        return null;
    }
    const files = fs.readdirSync(targetDir);
    const ear = files.find((f) => f.endsWith('.ear'));
    if (ear) {
        return path.join(targetDir, ear);
    }
    const war = files.find((f) => f.endsWith('.war'));
    if (war) {
        return path.join(targetDir, war);
    }
    return null;
}
