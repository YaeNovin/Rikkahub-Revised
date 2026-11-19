import { reactRouter } from "@react-router/dev/vite";
import tailwindcss from "@tailwindcss/vite";
import { defineConfig, loadEnv } from "vite";
import tsconfigPaths from "vite-tsconfig-paths";
import svgr from "vite-plugin-svgr";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const target = env.WEB_API_TARGET || "http://127.0.0.1:8080";
  const parsed = new URL(target);
  if (
    !["http:", "https:"].includes(parsed.protocol) ||
    parsed.username ||
    parsed.password ||
    parsed.pathname !== "/" ||
    parsed.search ||
    parsed.hash
  ) {
    throw new Error("WEB_API_TARGET must be an HTTP(S) origin without credentials or a path");
  }
  const proxy = { "/api": { target, changeOrigin: true } };
  return {
    plugins: [tailwindcss(), reactRouter(), tsconfigPaths(), svgr()],
    server: {
      host: "127.0.0.1",
      proxy,
    },
    preview: { host: "127.0.0.1", proxy },
  };
});
