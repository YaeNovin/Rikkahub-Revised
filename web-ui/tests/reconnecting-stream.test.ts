import assert from "node:assert/strict";
import { test } from "node:test";
import { setImmediate } from "node:timers/promises";
import { reconnectingStream, type StreamCallbacks } from "../app/services/reconnecting-stream";

for (const enabled of [false, true]) {
  test(`saved reconnect preference enabled=${enabled} controls retry and interval`, async (t) => {
    t.mock.timers.enable({ apis: ["setTimeout"] });
    const previous = Object.getOwnPropertyDescriptor(globalThis, "window");
    Object.defineProperty(globalThis, "window", {
      configurable: true,
      value: { localStorage: { getItem: () => JSON.stringify({ enabled, delaySeconds: 3 }) } },
    });
    t.after(() => {
      if (previous) Object.defineProperty(globalThis, "window", previous);
      else Reflect.deleteProperty(globalThis, "window");
    });
    let calls = 0;
    const errors: Error[] = [];
    const stop = reconnectingStream(async () => { calls++; }, {
      onMessage() {}, onError: error => errors.push(error),
    });
    t.after(stop);
    await setImmediate();
    t.mock.timers.tick(2999);
    assert.equal(calls, 1);
    t.mock.timers.tick(1);
    assert.equal(calls, enabled ? 2 : 1);
    assert.equal(errors.length, enabled ? 0 : 1);
  });
}

test("reconnects after EOF, backs off, and resets delay only on data", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const attempts: { callbacks: StreamCallbacks<string>; close: () => void }[] = [];
  const messages: string[] = [];
  const stop = reconnectingStream<string>(
    (callbacks) =>
      new Promise<void>((close) => {
        attempts.push({ callbacks, close });
        callbacks.onOpen?.();
      }),
    { onMessage: (value) => messages.push(value) },
  );
  t.after(stop);
  attempts[0].close();
  await setImmediate();
  t.mock.timers.tick(1000);
  assert.equal(attempts.length, 2);
  attempts[1].close();
  await setImmediate();
  t.mock.timers.tick(1000);
  assert.equal(attempts.length, 2);
  t.mock.timers.tick(1000);
  assert.equal(attempts.length, 3);
  attempts[2].callbacks.onMessage("fresh snapshot");
  attempts[2].close();
  await setImmediate();
  t.mock.timers.tick(1000);
  assert.equal(attempts.length, 4);
  assert.deepEqual(messages, ["fresh snapshot"]);
});

test("unmount cancels retries and ignores callbacks from the old conversation", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  let calls = 0;
  let saved!: StreamCallbacks<string>;
  let signal!: AbortSignal;
  const messages: string[] = [];
  const stop = reconnectingStream<string>(
    async (callbacks, abortSignal) => {
      calls++;
      saved = callbacks;
      signal = abortSignal;
    },
    { onMessage: (value) => messages.push(value) },
  );
  await setImmediate();
  stop();
  saved.onMessage("stale snapshot");
  t.mock.timers.tick(60_000);
  assert.equal(signal.aborted, true);
  assert.equal(calls, 1);
  assert.deepEqual(messages, []);
});

for (const code of [401, 403, 404]) {
  test(`does not retry terminal HTTP ${code}`, async (t) => {
    t.mock.timers.enable({ apis: ["setTimeout"] });
    let calls = 0;
    const errors: Error[] = [];
    const stop = reconnectingStream(
      async (callbacks) => {
        calls++;
        callbacks.onError?.(Object.assign(new Error("request failed"), { code }));
      },
      { onMessage() {}, onError: (error) => errors.push(error) },
    );
    t.after(stop);
    await setImmediate();
    t.mock.timers.tick(60_000);
    assert.equal(calls, 1);
    assert.equal(errors.length, 1);
  });
}

test("retries rejected network requests", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  let calls = 0;
  const stop = reconnectingStream(
    async () => {
      calls++;
      throw new TypeError("offline");
    },
    { onMessage() {} },
  );
  t.after(stop);
  await setImmediate();
  t.mock.timers.tick(1000);
  assert.equal(calls, 2);
});
