import { sse, type SSEEvent } from "~/services/api";
import { reconnectingStream } from "./reconnecting-stream";

/**
 * Multiplexed SSE client for `/api/events`.
 *
 * A single shared connection carries several event types (settings, conversation list
 * invalidation, ...). Consumers subscribe by event name; the connection is opened on the
 * first subscriber, reused by all, reconnected on drop, and closed when nobody listens.
 *
 * @see app/src/main/java/me/rerere/rikkahub/web/routes/EventsRoutes.kt
 */

export const EVENT_SETTINGS = "settings";
export const EVENT_CONVERSATION_LIST_INVALIDATE = "conversation_list_invalidate";
export const EVENT_FOLDERS = "folders";

type EventListener = (data: unknown) => void;

const listeners = new Map<string, Set<EventListener>>();
let stopConnection: (() => void) | null = null;

function hasListeners(): boolean {
  for (const set of listeners.values()) {
    if (set.size > 0) return true;
  }
  return false;
}

function dispatch(event: string, data: unknown) {
  const set = listeners.get(event);
  if (!set) return;
  for (const listener of set) {
    try {
      listener(data);
    } catch (error) {
      console.error(`Events listener for "${event}" failed:`, error);
    }
  }
}

function startConnection() {
  if (stopConnection || !hasListeners()) return;
  stopConnection = reconnectingStream<SSEEvent<unknown>>(
    (callbacks, signal) => sse<unknown>("events", callbacks, { signal }),
    {
      onMessage: ({ event, data }) => {
        dispatch(event, data);
      },
      onError: (error) => {
        console.error("Events SSE error:", error);
      },
    },
  );
}

function stopIfIdle() {
  if (hasListeners()) return;
  stopConnection?.();
  stopConnection = null;
}

/**
 * Subscribe to a single event type on the shared `/api/events` connection.
 * Returns an unsubscribe function.
 */
export function subscribeToEvent<T>(eventType: string, listener: (data: T) => void): () => void {
  let set = listeners.get(eventType);
  if (!set) {
    set = new Set();
    listeners.set(eventType, set);
  }
  const wrapped = listener as EventListener;
  set.add(wrapped);
  startConnection();

  return () => {
    set.delete(wrapped);
    stopIfIdle();
  };
}
