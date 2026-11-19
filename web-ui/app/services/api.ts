import ky, { type Options, HTTPError, TimeoutError } from "ky";

interface ErrorResponse {
  error: string;
  code: number;
}

interface WebAuthTokenResponse {
  token: string;
  expiresAt: number;
}

interface WebAuthRequiredEventDetail {
  message: string;
  code: number;
}

export class ApiError extends Error {
  code: number;

  constructor(message: string, code: number) {
    super(message);
    this.name = "ApiError";
    this.code = code;
  }
}

const WEB_AUTH_STORAGE_KEY = "rikkahub:web-auth";
const WEB_AUTH_REQUIRED_EVENT = "rikkahub:web-auth-required";
const WEB_AUTH_EXPIRY_SKEW_MILLIS = 10_000;

function isBrowser(): boolean {
  return typeof window !== "undefined";
}

function readStoredWebAuth(): WebAuthTokenResponse | null {
  if (!isBrowser()) return null;
  // Keep the bearer token scoped to the current browser session. Media requests use
  // the HttpOnly cookie issued by /auth/token and never receive a token in their URL.
  const raw = window.sessionStorage.getItem(WEB_AUTH_STORAGE_KEY);
  if (!raw) return null;

  try {
    const parsed = JSON.parse(raw) as Partial<WebAuthTokenResponse>;
    if (typeof parsed.token !== "string" || typeof parsed.expiresAt !== "number") {
      return null;
    }
    return { token: parsed.token, expiresAt: parsed.expiresAt };
  } catch {
    return null;
  }
}

function isWebAuthExpired(expiresAt: number): boolean {
  return Date.now() >= expiresAt - WEB_AUTH_EXPIRY_SKEW_MILLIS;
}

function getValidWebAuthToken(): string | null {
  const auth = readStoredWebAuth();
  if (!auth) return null;
  if (isWebAuthExpired(auth.expiresAt)) {
    clearWebAuthToken();
    return null;
  }
  return auth.token;
}

function dispatchWebAuthRequired(detail: WebAuthRequiredEventDetail) {
  if (!isBrowser()) return;
  window.dispatchEvent(new CustomEvent<WebAuthRequiredEventDetail>(WEB_AUTH_REQUIRED_EVENT, { detail }));
}

const kyInstance = ky.create({
  prefixUrl: "/api",
  timeout: 30000,
  hooks: {
    beforeRequest: [
      (request) => {
        const token = getValidWebAuthToken();
        if (!token || request.headers.has("Authorization")) return;
        request.headers.set("Authorization", `Bearer ${token}`);
      },
    ],
  },
});

async function handleError(error: unknown): Promise<never> {
  if (error instanceof TimeoutError) {
    throw new ApiError("手机 Web 服务响应超时。若切到后台后出现，请在手机应用信息中允许后台运行，并检查电池限制；vivo / iQOO 需将后台耗电管理设为“允许后台耗电”。", 0);
  }
  if (error instanceof HTTPError) {
    const { response } = error;
    let errorData: ErrorResponse | undefined;
    try {
      errorData = await response.json();
    } catch {
      // Ignore JSON parse error
    }
    const code = errorData?.code ?? response.status;
    const message = errorData?.error ?? error.message;
    const isAuthTokenEndpoint = response.url.includes("/api/auth/token");
    if (code === 401 && !isAuthTokenEndpoint) {
      clearWebAuthToken();
      dispatchWebAuthRequired({ message, code });
    }
    throw new ApiError(message, code);
  }
  throw error;
}

export function setWebAuthToken(token: string, expiresAt: number): void {
  if (!isBrowser()) return;
  window.sessionStorage.setItem(WEB_AUTH_STORAGE_KEY, JSON.stringify({ token, expiresAt }));
}

export function clearWebAuthToken(): void {
  if (!isBrowser()) return;
  window.sessionStorage.removeItem(WEB_AUTH_STORAGE_KEY);
}

export function onWebAuthRequired(
  listener: (detail: WebAuthRequiredEventDetail) => void,
): () => void {
  if (!isBrowser()) return () => {};

  const handler = (event: Event) => {
    const customEvent = event as CustomEvent<WebAuthRequiredEventDetail>;
    listener(customEvent.detail);
  };
  window.addEventListener(WEB_AUTH_REQUIRED_EVENT, handler);

  return () => {
    window.removeEventListener(WEB_AUTH_REQUIRED_EVENT, handler);
  };
}

export function appendWebAuthQuery(url: string): string {
  // Kept as a compatibility helper for callers that resolve media URLs. Authentication
  // is carried by the HttpOnly same-site cookie, never by query parameters.
  return url;
}

/**
 * API client with unwrapped response data
 */
const api = {
  async get<T>(url: string, options?: Options): Promise<T> {
    try {
      return await kyInstance.get(url, options).json<T>();
    } catch (error) {
      return handleError(error);
    }
  },
  async post<T>(url: string, data?: unknown, options?: Options): Promise<T> {
    try {
      return await kyInstance.post(url, { ...options, json: data }).json<T>();
    } catch (error) {
      return handleError(error);
    }
  },
  async postMultipart<T>(url: string, formData: FormData, options?: Options): Promise<T> {
    try {
      return await kyInstance.post(url, { ...options, body: formData }).json<T>();
    } catch (error) {
      return handleError(error);
    }
  },
  async put<T>(url: string, data?: unknown, options?: Options): Promise<T> {
    try {
      return await kyInstance.put(url, { ...options, json: data }).json<T>();
    } catch (error) {
      return handleError(error);
    }
  },
  async patch<T>(url: string, data?: unknown, options?: Options): Promise<T> {
    try {
      return await kyInstance.patch(url, { ...options, json: data }).json<T>();
    } catch (error) {
      return handleError(error);
    }
  },
  async delete<T>(url: string, options?: Options): Promise<T> {
    try {
      return await kyInstance.delete(url, options).json<T>();
    } catch (error) {
      return handleError(error);
    }
  },
};

export async function requestWebAuthToken(password: string): Promise<WebAuthTokenResponse> {
  const response = await api.post<WebAuthTokenResponse>("auth/token", { password });
  setWebAuthToken(response.token, response.expiresAt);
  return response;
}

export async function logoutWebSession(): Promise<void> {
  // Retain local state on network failure: a cookie session has not been logged out yet.
  await api.post("auth/logout", {});
  clearWebAuthToken();
}

export interface SSEEvent<T> {
  event: string;
  data: T;
  id?: string;
}

export interface SSECallbacks<T> {
  onMessage: (event: SSEEvent<T>) => void;
  onError?: (error: Error) => void;
  onOpen?: () => void;
  onClose?: () => void;
}

/**
 * Create an SSE connection using ky (supports auth headers)
 */
async function sse<T>(
  url: string,
  callbacks: SSECallbacks<T>,
  options?: Options & { signal?: AbortSignal },
): Promise<void> {
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;

  try {
    const response = await kyInstance.get(url, {
      ...options,
      headers: {
        ...options?.headers,
        // Ktor returns JSON for authentication/errors before opening the stream.
        Accept: "text/event-stream, application/json",
      },
      timeout: false,
    });

    callbacks.onOpen?.();

    reader = response.body?.getReader();
    if (!reader) {
      throw new ApiError("Response body is not readable", 0);
    }

    const decoder = new TextDecoder();
    let buffer = "";
    let currentEvent = "message";
    let currentData = "";
    let currentId: string | undefined;

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop() ?? "";

      for (const line of lines) {
        const trimmedLine = line.replace(/\r$/, "");
        if (trimmedLine.startsWith("event:")) {
          currentEvent = trimmedLine.slice(6).trim();
        } else if (trimmedLine.startsWith("data:")) {
          currentData += (currentData ? "\n" : "") + trimmedLine.slice(5).trim();
        } else if (trimmedLine.startsWith("id:")) {
          currentId = trimmedLine.slice(3).trim();
        } else if (trimmedLine === "") {
          if (currentData) {
            try {
              const data = JSON.parse(currentData) as T;
              callbacks.onMessage({ event: currentEvent, data, id: currentId });
            } catch {
              // Ignore JSON parse error
            }
          }
          currentEvent = "message";
          currentData = "";
          currentId = undefined;
        }
      }
    }
  } catch (error) {
    if (error instanceof Error && error.name === "AbortError") {
      // Ignored: intentional abort
    } else {
      try {
        await handleError(error);
      } catch (handledError) {
        callbacks.onError?.(
          handledError instanceof Error ? handledError : new Error(String(handledError)),
        );
      }
    }
  } finally {
    reader?.releaseLock();
    callbacks.onClose?.();
  }
}

export { sse };
export default api;
