import { readWebReconnectSettings } from "./web-reconnect-settings";

export interface StreamCallbacks<T> {
  onMessage: (event: T) => void;
  onOpen?: () => void;
  onError?: (error: Error) => void;
}

/** Owns one connection at a time. A new connection must send a fresh snapshot. */
export function reconnectingStream<T>(
  connect: (callbacks: StreamCallbacks<T>, signal: AbortSignal) => Promise<void>,
  callbacks: StreamCallbacks<T>,
  options: { onDisconnect?: () => void; initialDelay?: number; maxDelay?: number } = {},
): () => void {
  const controller = new AbortController();
  const initialDelay = options.initialDelay ?? readWebReconnectSettings().delaySeconds * 1000;
  let delay = initialDelay;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let terminal = false;

  const onError = (error: Error) => {
    if (controller.signal.aborted) return;
    const code = "code" in error ? error.code : undefined;
    // Re-authentication reloads the page. Do not keep polling protected/deleted resources.
    terminal = code === 401 || code === 403 || code === 404;
    callbacks.onError?.(error);
  };

  async function run() {
    try {
      await connect(
        {
          onOpen: () => {
            if (!controller.signal.aborted) callbacks.onOpen?.();
          },
          onMessage: (event) => {
            if (controller.signal.aborted) return;
            delay = initialDelay;
            callbacks.onMessage(event);
          },
          onError,
        },
        controller.signal,
      );
    } catch (error) {
      onError(error instanceof Error ? error : new Error(String(error)));
    }
    if (controller.signal.aborted || terminal) return;
    options.onDisconnect?.();
    if (!readWebReconnectSettings().enabled) {
      callbacks.onError?.(new Error("连接已中断，自动重连已关闭，请刷新重试"));
      return;
    }
    timer = setTimeout(() => void run(), delay);
    delay = Math.min(delay * 2, options.maxDelay ?? 30_000);
  }

  void run();
  return () => {
    controller.abort();
    clearTimeout(timer);
  };
}
