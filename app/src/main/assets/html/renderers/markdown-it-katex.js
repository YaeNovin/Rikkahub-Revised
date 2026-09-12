"use strict";
var markdownItKatex = (() => {
  var __getOwnPropNames = Object.getOwnPropertyNames;
  var __commonJS = (cb, mod) => function __require() {
    return mod || (0, cb[__getOwnPropNames(cb)[0]])((mod = { exports: {} }).exports, mod), mod.exports;
  };

  // scripts/katex-browser-global.cjs
  var require_katex_browser_global = __commonJS({
    "scripts/katex-browser-global.cjs"(exports, module) {
      module.exports = window.katex;
    }
  });

  // app/src/main/assets/html/renderers/markdown-it-katex.source.js
  var require_markdown_it_katex_source = __commonJS({
    "app/src/main/assets/html/renderers/markdown-it-katex.source.js"(exports) {
      var __importDefault = exports && exports.__importDefault || function(mod) {
        return mod && mod.__esModule ? mod : { "default": mod };
      };
      Object.defineProperty(exports, "__esModule", { value: true });
      var katex_1 = __importDefault(require_katex_browser_global());
      function isValidInlineDelim(state, pos) {
        const prevChar = state.src[pos - 1];
        const char = state.src[pos];
        const nextChar = state.src[pos + 1];
        if (char !== "$") {
          return { can_open: false, can_close: false };
        }
        let canOpen = false;
        let canClose = false;
        if (prevChar !== "$" && prevChar !== "\\" && (prevChar === void 0 || isWhitespace(prevChar) || !isWordCharacterOrNumber(prevChar))) {
          canOpen = true;
        }
        if (nextChar !== "$" && (nextChar == void 0 || isWhitespace(nextChar) || !isWordCharacterOrNumber(nextChar))) {
          canClose = true;
        }
        return { can_open: canOpen, can_close: canClose };
      }
      function isWhitespace(char) {
        return /^\s$/u.test(char);
      }
      function isWordCharacterOrNumber(char) {
        return /^[\w\d]$/u.test(char);
      }
      function isValidBlockDelim(state, pos) {
        const prevChar = state.src[pos - 1];
        const char = state.src[pos];
        const nextChar = state.src[pos + 1];
        const nextCharPlus1 = state.src[pos + 2];
        if (char === "$" && prevChar !== "$" && prevChar !== "\\" && nextChar === "$" && nextCharPlus1 !== "$") {
          return { can_open: true, can_close: true };
        }
        return { can_open: false, can_close: false };
      }
      function inlineMath(state, silent) {
        if (state.src[state.pos] !== "$") {
          return false;
        }
        const lastToken = state.tokens.at(-1);
        if ((lastToken == null ? void 0 : lastToken.type) === "html_inline") {
          if (/^<\w+.+[^/]>$/.test(lastToken.content)) {
            return false;
          }
        }
        let res = isValidInlineDelim(state, state.pos);
        if (!res.can_open) {
          if (!silent) {
            state.pending += "$";
          }
          state.pos += 1;
          return true;
        }
        let start = state.pos + 1;
        let match = start;
        let pos;
        while ((match = state.src.indexOf("$", match)) !== -1) {
          pos = match - 1;
          while (state.src[pos] === "\\") {
            pos -= 1;
          }
          if ((match - pos) % 2 == 1) {
            break;
          }
          match += 1;
        }
        if (match === -1) {
          if (!silent) {
            state.pending += "$";
          }
          state.pos = start;
          return true;
        }
        if (match - start === 0) {
          if (!silent) {
            state.pending += "$$";
          }
          state.pos = start + 1;
          return true;
        }
        res = isValidInlineDelim(state, match);
        if (!res.can_close) {
          if (!silent) {
            state.pending += "$";
          }
          state.pos = start;
          return true;
        }
        if (!silent) {
          const token = state.push("math_inline", "math", 0);
          token.markup = "$";
          token.content = state.src.slice(start, match);
        }
        state.pos = match + 1;
        return true;
      }
      function blockMath(state, start, end, silent) {
        let found = false;
        let pos = state.bMarks[start] + state.tShift[start];
        let max = state.eMarks[start];
        if (pos + 2 > max) {
          return false;
        }
        if (state.src.slice(pos, pos + 2) !== "$$") {
          return false;
        }
        pos += 2;
        let firstLine = state.src.slice(pos, max);
        const endIndexes = [...firstLine.matchAll(/\$\$/g)];
        if (endIndexes.length === 1 && endIndexes[0].index === firstLine.length - 2) {
          firstLine = firstLine.trim().slice(0, -2);
          found = true;
        } else if (endIndexes.length > 1) {
          return false;
        }
        if (silent) {
          return true;
        }
        let lastLine;
        let next;
        let lastPos;
        for (next = start; !found; ) {
          next++;
          if (next >= end) {
            break;
          }
          pos = state.bMarks[next] + state.tShift[next];
          max = state.eMarks[next];
          if (pos < max && state.tShift[next] < state.blkIndent) {
            break;
          }
          if (state.src.slice(pos, max).trim().slice(-2) === "$$") {
            lastPos = state.src.slice(0, max).lastIndexOf("$$");
            lastLine = state.src.slice(pos, lastPos);
            found = true;
          } else if (state.src.slice(pos, max).trim().includes("$$")) {
            lastPos = state.src.slice(0, max).trim().indexOf("$$");
            lastLine = state.src.slice(pos, lastPos);
            found = true;
          }
        }
        state.line = next + 1;
        const token = state.push("math_block", "math", 0);
        token.block = true;
        token.content = (firstLine && firstLine.trim() ? firstLine + "\n" : "") + state.getLines(start + 1, next, state.tShift[start], true) + (lastLine && lastLine.trim() ? lastLine : "");
        token.map = [start, state.line];
        token.markup = "$$";
        return true;
      }
      function blockBareMath(state, start, end, silent) {
        const startPos = state.bMarks[start] + state.tShift[start];
        const startMax = state.eMarks[start];
        const firstLine = state.src.slice(startPos, startMax);
        const beginMatch = firstLine.match(/^\s*\\begin\s*\{([^{}]+)\}/);
        if (!beginMatch) {
          return false;
        }
        if (start > 0) {
          const previousStart = state.bMarks[start - 1] + state.tShift[start - 1];
          const previousEnd = state.eMarks[start - 1];
          const previousLine = state.src.slice(previousStart, previousEnd);
          if (!/^\s*$/.test(previousLine)) {
            return false;
          }
        }
        if (silent) {
          return true;
        }
        const beginEndStack = [];
        let next = start;
        let lastLine;
        let found = false;
        outer: for (; !found; next++) {
          if (next >= end) {
            break;
          }
          const pos = state.bMarks[next] + state.tShift[next];
          const max = state.eMarks[next];
          if (pos < max && state.tShift[next] < state.blkIndent) {
            break;
          }
          const line = state.src.slice(pos, max);
          for (const match of line.matchAll(/(\\begin|\\end)\s*\{([^{}]+)\}/g)) {
            if (match[1] === "\\begin") {
              beginEndStack.push(match[2].trim());
            } else if (match[1] === "\\end") {
              beginEndStack.pop();
              if (!beginEndStack.length) {
                lastLine = state.src.slice(pos, max);
                found = true;
                break outer;
              }
            }
          }
        }
        state.line = next + 1;
        const token = state.push("math_block", "math", 0);
        token.block = true;
        token.content = (state.getLines(start, next, state.tShift[start], true) + (lastLine != null ? lastLine : "")).trim();
        token.map = [start, state.line];
        token.markup = "$$";
        return true;
      }
      function inlineMathBlock(state, silent) {
        var start, match, token, res, pos;
        if (state.src.slice(state.pos, state.pos + 2) !== "$$") {
          return false;
        }
        res = isValidBlockDelim(state, state.pos);
        if (!res.can_open) {
          if (!silent) {
            state.pending += "$$";
          }
          state.pos += 2;
          return true;
        }
        start = state.pos + 2;
        match = start;
        while ((match = state.src.indexOf("$$", match)) !== -1) {
          pos = match - 1;
          while (state.src[pos] === "\\") {
            pos -= 1;
          }
          if ((match - pos) % 2 == 1) {
            break;
          }
          match += 2;
        }
        if (match === -1) {
          if (!silent) {
            state.pending += "$$";
          }
          state.pos = start;
          return true;
        }
        if (match - start === 0) {
          if (!silent) {
            state.pending += "$$$$";
          }
          state.pos = start + 2;
          return true;
        }
        res = isValidBlockDelim(state, match);
        if (!res.can_close) {
          if (!silent) {
            state.pending += "$$";
          }
          state.pos = start;
          return true;
        }
        if (!silent) {
          token = state.push("math_block", "math", 0);
          token.block = true;
          token.markup = "$$";
          token.content = state.src.slice(start, match);
        }
        state.pos = match + 2;
        return true;
      }
      function inlineBareBlock(state, silent) {
        const text = state.src.slice(state.pos);
        if (!/^\n\\begin/.test(text)) {
          return false;
        }
        state.pos += 1;
        if (silent) {
          return true;
        }
        const lines = text.split(/\n/g).slice(1);
        let foundLine;
        const beginEndStack = [];
        outer: for (var i = 0; i < lines.length; ++i) {
          const line = lines[i];
          for (const match of line.matchAll(/(\\begin|\\end)\s*\{([^{}]+)\}/g)) {
            if (match[1] === "\\begin") {
              beginEndStack.push(match[2].trim());
            } else if (match[1] === "\\end") {
              beginEndStack.pop();
              if (!beginEndStack.length) {
                foundLine = i;
                break outer;
              }
            }
          }
        }
        if (typeof foundLine === "undefined") {
          return false;
        }
        const endIndex = lines.slice(0, foundLine + 1).reduce((p, c) => p + c.length, 0) + foundLine + 1;
        const token = state.push("math_inline_bare_block", "math", 0);
        token.block = true;
        token.markup = "$$";
        token.content = text.slice(1, endIndex);
        state.pos = state.pos + endIndex;
        return true;
      }
      function handleMathInHtml(state, mathType, mathMarkup, mathRegex) {
        const tokens = state.tokens;
        for (let index = tokens.length - 1; index >= 0; index--) {
          const currentToken = tokens[index];
          const newTokens = [];
          if (currentToken.type !== "html_block") {
            continue;
          }
          const content = currentToken.content;
          for (const match of content.matchAll(mathRegex)) {
            if (!match.groups) {
              continue;
            }
            const html_before_math = match.groups.html_before_math;
            const math = match.groups.math;
            const html_after_math = match.groups.html_after_math;
            if (html_before_math) {
              newTokens.push({ ...currentToken, type: "html_block", map: null, content: html_before_math });
            }
            if (math) {
              newTokens.push({
                ...currentToken,
                type: mathType,
                map: null,
                content: math,
                markup: mathMarkup,
                block: true,
                tag: "math"
              });
            }
            if (html_after_math) {
              newTokens.push({ ...currentToken, type: "html_block", map: null, content: html_after_math });
            }
          }
          if (newTokens.length > 0) {
            tokens.splice(index, 1, ...newTokens);
          }
        }
        return true;
      }
      function escapeHtml(unsafe) {
        return unsafe.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#039;");
      }
      function default_1(md, options) {
        var _a;
        const katex = (_a = options == null ? void 0 : options.katex) != null ? _a : katex_1.default;
        const enableBareBlocks = options == null ? void 0 : options.enableBareBlocks;
        const enableMathBlockInHtml = options == null ? void 0 : options.enableMathBlockInHtml;
        const enableMathInlineInHtml = options == null ? void 0 : options.enableMathInlineInHtml;
        const enableFencedBlocks = options == null ? void 0 : options.enableFencedBlocks;
        md.inline.ruler.after("escape", "math_inline", inlineMath);
        md.inline.ruler.after("escape", "math_inline_block", inlineMathBlock);
        if (enableBareBlocks) {
          md.inline.ruler.before("text", "math_inline_bare_block", inlineBareBlock);
        }
        md.block.ruler.after("blockquote", "math_block", (state, start, end, silent) => {
          if (enableBareBlocks && blockBareMath(state, start, end, silent)) {
            return true;
          }
          return blockMath(state, start, end, silent);
        }, {
          alt: ["paragraph", "reference", "blockquote", "list"]
        });
        const math_block_within_html_regex = /(?<html_before_math>[\s\S]*?)\$\$(?<math>[\s\S]+?)\$\$(?<html_after_math>(?:(?!\$\$[\s\S]+?\$\$)[\s\S])*)/gm;
        const math_inline_within_html_regex = /(?<html_before_math>[\s\S]*?)\$(?<math>.*?)\$(?<html_after_math>(?:(?!\$.*?\$)[\s\S])*)/gm;
        if (enableMathBlockInHtml) {
          md.core.ruler.push("math_block_in_html_block", (state) => {
            return handleMathInHtml(state, "math_block", "$$", math_block_within_html_regex);
          });
        }
        if (enableMathInlineInHtml) {
          md.core.ruler.push("math_inline_in_html_block", (state) => {
            return handleMathInHtml(state, "math_inline", "$", math_inline_within_html_regex);
          });
        }
        const katexInline = (latex) => {
          const displayMode = /\\begin\{(align|equation|gather|cd|alignat)\}/ig.test(latex);
          try {
            return katex.renderToString(latex, { ...options, displayMode });
          } catch (error) {
            if (options == null ? void 0 : options.throwOnError) {
              console.log(error);
            }
            return `<span class="katex-error" title="${escapeHtml(latex)}">${escapeHtml(error + "")}</span>`;
          }
        };
        const inlineRenderer = (tokens, idx) => {
          const content = tokens[idx].content;
          const hasBacktick = content.length > 2 && content[0] === "`" && content[content.length - 1] === "`";
          const sanitized = hasBacktick ? content.slice(1, -1) : content;
          return katexInline(sanitized);
        };
        const katexBlockRenderer = (latex) => {
          try {
            return `<p class="katex-block">${katex.renderToString(latex, { ...options, displayMode: true })}</p>`;
          } catch (error) {
            if (options == null ? void 0 : options.throwOnError) {
              console.log(error);
            }
            return `<p class="katex-block katex-error" title="${escapeHtml(latex)}">${escapeHtml(error + "")}</p>`;
          }
        };
        const blockRenderer = (tokens, idx) => {
          return katexBlockRenderer(tokens[idx].content) + "\n";
        };
        md.renderer.rules.math_inline = inlineRenderer;
        md.renderer.rules.math_inline_block = blockRenderer;
        md.renderer.rules.math_inline_bare_block = blockRenderer;
        md.renderer.rules.math_block = blockRenderer;
        if (enableFencedBlocks) {
          const mathLanguageId = "math";
          const originalFenceRenderer = md.renderer.rules.fence;
          md.renderer.rules.fence = function(tokens, idx, options2, env, self) {
            const token = tokens[idx];
            if (token.info.trim().toLowerCase() === mathLanguageId && enableFencedBlocks) {
              return katexBlockRenderer(token.content) + "\n";
            } else {
              return (originalFenceRenderer == null ? void 0 : originalFenceRenderer.call(this, tokens, idx, options2, env, self)) || "";
            }
          };
        }
      }
      exports.default = default_1;
    }
  });
  return require_markdown_it_katex_source();
})();
