export const WEB_RECONNECT_KEY = "rikkahub:web-reconnect";
export type WebReconnectSettings = { enabled: boolean; delaySeconds: number };
export function readWebReconnectSettings(): WebReconnectSettings {
  const defaults = { enabled: true, delaySeconds: 1 };
  if (typeof window === "undefined") return defaults;
  try {
    const value = JSON.parse(window.localStorage.getItem(WEB_RECONNECT_KEY) ?? "null");
    if (
      !value ||
      typeof value.enabled !== "boolean" ||
      !Number.isInteger(value.delaySeconds) ||
      value.delaySeconds < 1 ||
      value.delaySeconds > 30
    )
      return defaults;
    return { enabled: value.enabled, delaySeconds: value.delaySeconds };
  } catch {
    return defaults;
  }
}
