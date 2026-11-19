import { useEffect, useRef, useSyncExternalStore } from "react";
import { useBlocker } from "react-router";

const drafts = new Set<symbol>();
const listeners = new Set<() => void>();
const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};
const emit = () => listeners.forEach((listener) => listener());

export function useUnsavedChanges(dirty: boolean) {
  const id = useRef(Symbol("settings-draft"));
  useEffect(() => {
    const key = id.current;
    if (dirty) drafts.add(key);
    else drafts.delete(key);
    emit();
    return () => {
      drafts.delete(key);
      emit();
    };
  }, [dirty]);
}

export function confirmSettingsNavigation() {
  return drafts.size === 0 || window.confirm("有未保存修改，确定放弃并离开？");
}

/** A single router blocker covers all forms, including browser Back/Forward. */
export function UnsavedChangesGuard() {
  const dirty = useSyncExternalStore(
    subscribe,
    () => drafts.size > 0,
    () => false,
  );
  const blocker = useBlocker(dirty);
  useEffect(() => {
    if (blocker.state === "blocked") {
      if (confirmSettingsNavigation()) blocker.proceed();
      else blocker.reset();
    }
  }, [blocker]);
  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);
  return null;
}
