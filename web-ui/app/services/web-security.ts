import { useEffect } from "react";
import { logoutWebSession } from "~/services/api";

export const IDLE_LOGOUT_KEY = "rikkahub:web-idle-logout-minutes";
export function useWebIdleLogout() {
  useEffect(() => {
    let lastActivity = Date.now();
    let exiting = false;
    const touch = () => {
      lastActivity = Date.now();
    };
    const events = ["pointerdown", "keydown", "touchstart"];
    events.forEach((event) => window.addEventListener(event, touch, { passive: true }));
    const timer = setInterval(() => {
      const minutes = Number(localStorage.getItem(IDLE_LOGOUT_KEY) || 0);
      if (!exiting && minutes > 0 && Date.now() - lastActivity >= minutes * 60000) {
        exiting = true;
        void logoutWebSession()
          .then(() => window.location.reload())
          .catch(() => {
            exiting = false;
          });
      }
    }, 1000);
    return () => {
      clearInterval(timer);
      events.forEach((event) => window.removeEventListener(event, touch));
    };
  }, []);
}
