/*-----------------------------------------------------------------------------------------------
 *  Copyright (c) Red Hat, Inc. All rights reserved.
 *  Licensed under the EPL v2.0 License. See LICENSE file in the project root for license information.
 *-----------------------------------------------------------------------------------------------*/

import * as fs from 'fs';
import { homedir } from 'os';
import * as path from 'path';
import * as vscode from 'vscode';
import { OPTIONS } from '../constants';

interface ServerTreeContext {
    rsp: string;
    server: { id: string };
}

/**
 * Reads the server's attributes straight off its persisted RSP config file
 * (~/.rsp/<rspId>/servers/<serverId>) instead of going through the RSP
 * protocol - this extension has no client connection of its own (rsp-ui owns
 * that), and these two actions are read-only conveniences that don't
 * justify standing one up.
 *
 * Note: the folder is named after OPTIONS.rspId (the felix process's own
 * workspace id), not ctx.rsp - rsp-ui's "rsp" identifier for tree/command
 * context is this extension's own id (e.g. "lordByron.was-server-connector"),
 * a different value.
 */
export function registerServerInfoCommands(context: vscode.ExtensionContext): void {
    context.subscriptions.push(
        vscode.commands.registerCommand('was.showServerProperties', (ctx: ServerTreeContext) => showServerProperties(ctx)),
        vscode.commands.registerCommand('was.openAdminConsole', (ctx: ServerTreeContext) => openAdminConsole(ctx))
    );
}

function serverConfigPath(ctx: ServerTreeContext): string {
    return path.join(homedir(), '.rsp', OPTIONS.rspId, 'servers', ctx.server.id);
}

function readServerAttributes(ctx: ServerTreeContext): Record<string, string> | null {
    const configPath = serverConfigPath(ctx);
    if (!fs.existsSync(configPath)) {
        return null;
    }
    return JSON.parse(fs.readFileSync(configPath, 'utf8'));
}

async function showServerProperties(ctx: ServerTreeContext): Promise<void> {
    if (!ctx || !ctx.server) {
        vscode.window.showErrorMessage('WAS: select a server first.');
        return;
    }
    const configPath = serverConfigPath(ctx);
    if (!fs.existsSync(configPath)) {
        vscode.window.showErrorMessage(`WAS: could not find server properties at ${configPath}.`);
        return;
    }
    const doc = await vscode.workspace.openTextDocument(configPath);
    await vscode.window.showTextDocument(doc, { preview: true });
}

async function openAdminConsole(ctx: ServerTreeContext): Promise<void> {
    if (!ctx || !ctx.server) {
        vscode.window.showErrorMessage('WAS: select a server first.');
        return;
    }
    const attrs = readServerAttributes(ctx);
    if (!attrs) {
        vscode.window.showErrorMessage(`WAS: could not find server properties at ${serverConfigPath(ctx)}.`);
        return;
    }
    const host = attrs['was.host'] || 'localhost';
    const port = attrs['was.admin.console.port'] || '9060';
    const url = `http://${host}:${port}/ibm/console`;
    await vscode.env.openExternal(vscode.Uri.parse(url));
}
