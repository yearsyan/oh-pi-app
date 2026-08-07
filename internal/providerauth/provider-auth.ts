import {
	type ApiKeyCredential,
	type AssistantMessage,
	type AuthEvent,
	type AuthInteraction,
	type AuthPrompt,
	type AuthType,
	type Context,
	type Model,
	type Provider,
	type SimpleStreamOptions,
} from "@earendil-works/pi-ai";
import { builtinProviders } from "@earendil-works/pi-ai/providers/all";
import type { ExtensionAPI, ExtensionCommandContext } from "@earendil-works/pi-coding-agent";

const EVENT_PREFIX = "__OHPI_PROVIDER_EVENT__";
const PROMPT_PREFIX = "__OHPI_PROVIDER_PROMPT__";

type CredentialInfo = {
	providerId: string;
	type: AuthType;
};

type AuthStatus = {
	configured: boolean;
	source?: "runtime" | "stored" | "configured" | "environment";
	label?: string;
};

type ProviderRuntime = {
	getProvider(providerId: string): Provider | undefined;
	getModels(providerId: string): readonly Model<any>[];
	getProviderAuthStatus(providerId: string): AuthStatus;
	listCredentials(): Promise<readonly CredentialInfo[]>;
	completeSimple(model: Model<any>, context: Context, options?: SimpleStreamOptions): Promise<AssistantMessage>;
	login(providerId: string, type: AuthType, interaction: AuthInteraction): Promise<unknown>;
	logout(providerId: string): Promise<void>;
};

type RecordedPromptAnswer = {
	type: AuthPrompt["type"];
	value: string;
};

type PromptMetadata = {
	kind: AuthPrompt["type"];
	message: string;
	placeholder?: string;
	descriptions?: string[];
};

function providerRuntime(ctx: ExtensionCommandContext): ProviderRuntime {
	// ModelRuntime owns provider login/logout and the cross-process locked
	// auth.json store. It is intentionally kept behind ModelRegistry in pi's
	// public extension types, but is the only path that preserves every built-in
	// provider's native API-key/OAuth flow. Keep this compatibility check loud so
	// a future pi change fails closed instead of falling back to writing secrets.
	const runtime = (ctx.modelRegistry as unknown as { runtime?: ProviderRuntime }).runtime;
	if (!runtime || typeof runtime.login !== "function" || typeof runtime.logout !== "function") {
		throw new Error("this pi version does not expose the provider authentication runtime");
	}
	return runtime;
}

function builtinProvider(providerID: string): Provider {
	const provider = builtinProviders().find((candidate) => candidate.id === providerID);
	if (!provider) throw new Error(`unknown built-in provider: ${providerID}`);
	return provider;
}

function emit(ctx: ExtensionCommandContext, event: Record<string, unknown>, type: "info" | "warning" | "error" = "info"): void {
	ctx.ui.notify(`${EVENT_PREFIX}${JSON.stringify(event)}`, type);
}

function promptTitle(metadata: PromptMetadata): string {
	return `${PROMPT_PREFIX}${JSON.stringify(metadata)}`;
}

function cancelled(): never {
	throw new Error("login cancelled");
}

async function answerPrompt(ctx: ExtensionCommandContext, prompt: AuthPrompt): Promise<string> {
	if (prompt.type === "select") {
		const labels = prompt.options.map((option, index) => {
			const duplicate = prompt.options.some((other, otherIndex) => otherIndex !== index && other.label === option.label);
			return duplicate ? `${option.label} (${index + 1})` : option.label;
		});
		const selected = await ctx.ui.select(
			promptTitle({
				kind: prompt.type,
				message: prompt.message,
				descriptions: prompt.options.map((option) => option.description ?? ""),
			}),
			labels,
			{ signal: prompt.signal },
		);
		if (selected === undefined) cancelled();
		const index = labels.indexOf(selected);
		if (index < 0) throw new Error("invalid authentication selection");
		return prompt.options[index]?.id ?? cancelled();
	}

	const value = await ctx.ui.input(
		promptTitle({ kind: prompt.type, message: prompt.message, placeholder: prompt.placeholder }),
		prompt.placeholder,
		{ signal: prompt.signal },
	);
	return value ?? cancelled();
}

function forwardAuthEvent(ctx: ExtensionCommandContext, event: AuthEvent): void {
	emit(ctx, { event: event.type, ...event });
}

function authInteraction(ctx: ExtensionCommandContext): AuthInteraction {
	return {
		signal: ctx.signal,
		prompt: (prompt) => answerPrompt(ctx, prompt),
		notify: (event) => forwardAuthEvent(ctx, event),
	};
}

function recordingAuthInteraction(
	ctx: ExtensionCommandContext,
	answers: RecordedPromptAnswer[],
): AuthInteraction {
	const interaction = authInteraction(ctx);
	return {
		...interaction,
		prompt: async (prompt) => {
			const value = await interaction.prompt(prompt);
			answers.push({ type: prompt.type, value });
			return value;
		},
	};
}

function replayAuthInteraction(
	ctx: ExtensionCommandContext,
	answers: readonly RecordedPromptAnswer[],
): AuthInteraction {
	let index = 0;
	return {
		signal: ctx.signal,
		prompt: async (prompt) => {
			const answer = answers[index++];
			if (!answer || answer.type !== prompt.type) {
				throw new Error("provider API-key login changed while validating the credential");
			}
			return answer.value;
		},
		// The first pass already forwarded informational links and progress.
		notify: () => {},
	};
}

async function validateApiKey(
	ctx: ExtensionCommandContext,
	runtime: ProviderRuntime,
	provider: Provider,
	credential: ApiKeyCredential,
): Promise<void> {
	if (typeof runtime.completeSimple !== "function") {
		throw new Error("this pi version does not support API-key validation");
	}
	const textModels = runtime.getModels(provider.id).filter((model) => model.input.includes("text"));
	const model = textModels.find((candidate) => !candidate.reasoning) ?? textModels[0];
	if (!model) throw new Error(`${provider.name} has no text model available for API-key validation`);

	emit(ctx, { event: "validating" });
	const result = await runtime.completeSimple(
		model,
		{
			messages: [{ role: "user", content: ".", timestamp: Date.now() }],
		},
		{
			// An empty override is intentional for ambient/profile credentials: it
			// prevents an older stored key from being selected during validation.
			apiKey: credential.key ?? "",
			env: credential.env,
			maxTokens: 1,
			maxRetries: 0,
			timeoutMs: 15_000,
			cacheRetention: "none",
			signal: ctx.signal,
		},
	);
	if (result.stopReason === "error") {
		throw new Error(`API key validation failed: ${result.errorMessage?.trim() || "provider rejected the request"}`);
	}
	if (result.stopReason === "aborted") {
		if (ctx.signal.aborted) cancelled();
		throw new Error(`API key validation failed: ${result.errorMessage?.trim() || "request was aborted"}`);
	}
}

function modelSummary(model: Model<any>): Record<string, unknown> {
	return {
		id: model.id,
		name: model.name,
		reasoning: model.reasoning,
		input: model.input,
	};
}

async function listProviders(ctx: ExtensionCommandContext): Promise<void> {
	const runtime = providerRuntime(ctx);
	const credentials = new Map((await runtime.listCredentials()).map((credential) => [credential.providerId, credential.type]));
	const providers = builtinProviders().map((catalogProvider) => {
		const provider = runtime.getProvider(catalogProvider.id) ?? catalogProvider;
		const status = runtime.getProviderAuthStatus(catalogProvider.id);
		const authMethods: Array<Record<string, string>> = [];
		if (provider.auth.oauth) {
			authMethods.push({
				type: "oauth",
				name: provider.auth.oauth.name,
				label: provider.auth.oauth.loginLabel ?? "",
			});
		}
		if (provider.auth.apiKey?.login) {
			authMethods.push({ type: "api_key", name: provider.auth.apiKey.name, label: "" });
		}
		return {
			id: catalogProvider.id,
			name: provider.name,
			configured: status.configured,
			auth_source: status.source ?? "",
			auth_label: status.label ?? "",
			stored_auth_type: credentials.get(catalogProvider.id) ?? "",
			auth_methods: authMethods,
			models: runtime.getModels(catalogProvider.id).map(modelSummary),
		};
	});
	providers.sort((left, right) => left.name.localeCompare(right.name) || left.id.localeCompare(right.id));
	emit(ctx, { event: "providers", providers });
}

async function loginProvider(
	ctx: ExtensionCommandContext,
	providerID: string,
	authType: AuthType,
): Promise<void> {
	const catalogProvider = builtinProvider(providerID);
	const runtime = providerRuntime(ctx);
	const provider = runtime.getProvider(providerID) ?? catalogProvider;
	if (authType === "oauth" && !provider.auth.oauth) {
		throw new Error(`${provider.name} does not support account login`);
	}
	if (authType === "api_key" && !provider.auth.apiKey?.login) {
		throw new Error(`${provider.name} does not support API-key login`);
	}
	if (authType === "api_key") {
		const answers: RecordedPromptAnswer[] = [];
		const credential = await provider.auth.apiKey!.login!(recordingAuthInteraction(ctx, answers));
		await validateApiKey(ctx, runtime, provider, credential);
		// Persist only after the candidate credential has successfully completed
		// a minimal provider request. Replaying the deterministic built-in login
		// keeps storage ownership and cross-process locking inside ModelRuntime.
		await runtime.login(providerID, authType, replayAuthInteraction(ctx, answers));
	} else {
		await runtime.login(providerID, authType, authInteraction(ctx));
	}
	emit(ctx, {
		event: "complete",
		action: "login",
		provider_id: providerID,
		provider_name: provider.name,
		auth_type: authType,
	});
}

async function logoutProvider(ctx: ExtensionCommandContext, providerID: string): Promise<void> {
	const catalogProvider = builtinProvider(providerID);
	const runtime = providerRuntime(ctx);
	const provider = runtime.getProvider(providerID) ?? catalogProvider;
	await runtime.logout(providerID);
	emit(ctx, {
		event: "complete",
		action: "logout",
		provider_id: providerID,
		provider_name: provider.name,
	});
}

export default function providerAuthExtension(pi: ExtensionAPI): void {
	pi.registerCommand("ohpi-provider", {
		description: "Oh Pi App internal provider authentication bridge",
		handler: async (rawArguments, ctx) => {
			const [action = "", providerID = "", authType = ""] = rawArguments.trim().split(/\s+/u);
			try {
				switch (action) {
					case "list":
						await listProviders(ctx);
						return;
					case "login":
						if (authType !== "api_key" && authType !== "oauth") {
							throw new Error("authentication type must be api_key or oauth");
						}
						await loginProvider(ctx, providerID, authType);
						return;
					case "logout":
						await logoutProvider(ctx, providerID);
						return;
					default:
						throw new Error("provider action must be list, login, or logout");
				}
			} catch (error) {
				emit(
					ctx,
					{
						event: "error",
						action,
						provider_id: providerID,
						message: error instanceof Error ? error.message : String(error),
					},
					"error",
				);
			}
		},
	});
}
