import * as React from "react";
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";
import {
  AudioLines,
  BookHeart,
  BookX,
  Check,
  Clipboard,
  ClipboardPaste,
  Clock3,
  Globe,
  Loader2,
  MessageCircleQuestion,
  Search,
  Send,
  Video,
  Wrench,
  X,
} from "lucide-react";

import Markdown from "~/components/markdown/markdown";
import { Button } from "~/components/ui/button";
import { Input } from "~/components/ui/input";
import {
  Drawer,
  DrawerContent,
  DrawerDescription,
  DrawerHeader,
  DrawerTitle,
} from "~/components/ui/drawer";
import { useIsMobile } from "~/hooks/use-mobile";
import { resolveFileUrl } from "~/lib/files";
import type {
  TextPart as UITextPart,
  ToolPart as UIToolPart,
} from "~/types";

import { ControlledChainOfThoughtStep } from "../chain-of-thought";
import { AudioPart as AudioPartRenderer } from "./audio-part";
import { ImagePart as ImagePartRenderer } from "./image-part";
import { VideoPart as VideoPartRenderer } from "./video-part";

interface ToolPartProps {
  tool: UIToolPart;
  loading?: boolean;
  onToolApproval?: (
    toolCallId: string,
    approved: boolean,
    reason: string,
    answer?: string,
    cancelled?: boolean,
  ) => void | Promise<void>;
  isFirst?: boolean;
  isLast?: boolean;
}

const TOOL_NAMES = {
  MEMORY: "memory_tool",
  SEARCH_WEB: "search_web",
  SCRAPE_WEB: "scrape_web",
  GET_TIME_INFO: "get_time_info",
  CLIPBOARD: "clipboard_tool",
  ASK_USER: "ask_user",
} as const;

const MEMORY_ACTIONS = {
  CREATE: "create",
  EDIT: "edit",
  DELETE: "delete",
} as const;

const CLIPBOARD_ACTIONS = {
  READ: "read",
  WRITE: "write",
} as const;

function safeJsonParse(input: string): unknown {
  if (!input.trim()) return {};
  try {
    return JSON.parse(input);
  } catch {
    return {};
  }
}

function toJsonString(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2);
}

function getStringField(data: unknown, key: string): string | undefined {
  if (!data || typeof data !== "object" || Array.isArray(data)) return undefined;
  const value = (data as Record<string, unknown>)[key];
  return typeof value === "string" ? value : undefined;
}

function getArrayField(data: unknown, key: string): unknown[] {
  if (!data || typeof data !== "object" || Array.isArray(data)) return [];
  const value = (data as Record<string, unknown>)[key];
  return Array.isArray(value) ? value : [];
}

function getToolIcon(toolName: string, action?: string) {
  if (toolName === TOOL_NAMES.MEMORY) {
    if (action === MEMORY_ACTIONS.CREATE || action === MEMORY_ACTIONS.EDIT) {
      return BookHeart;
    }
    if (action === MEMORY_ACTIONS.DELETE) {
      return BookX;
    }
    return Wrench;
  }

  if (toolName === TOOL_NAMES.SEARCH_WEB) return Search;
  if (toolName === TOOL_NAMES.SCRAPE_WEB) return Globe;
  if (toolName === TOOL_NAMES.GET_TIME_INFO) return Clock3;

  if (toolName === TOOL_NAMES.CLIPBOARD) {
    if (action === CLIPBOARD_ACTIONS.WRITE) return ClipboardPaste;
    return Clipboard;
  }

  if (toolName === TOOL_NAMES.ASK_USER) return MessageCircleQuestion;

  return Wrench;
}

function getToolTitle(toolName: string, args: unknown, t: TFunction): string {
  const action = getStringField(args, "action");

  if (toolName === TOOL_NAMES.MEMORY) {
    if (action === MEMORY_ACTIONS.CREATE) return t("tool_part.memory_create");
    if (action === MEMORY_ACTIONS.EDIT) return t("tool_part.memory_edit");
    if (action === MEMORY_ACTIONS.DELETE) return t("tool_part.memory_delete");
  }

  if (toolName === TOOL_NAMES.SEARCH_WEB) {
    const query = getStringField(args, "query") ?? "";
    return query ? t("tool_part.search_web_with_query", { query }) : t("tool_part.search_web");
  }

  if (toolName === TOOL_NAMES.SCRAPE_WEB) return t("tool_part.scrape_web");
  if (toolName === TOOL_NAMES.GET_TIME_INFO) return t("tool_part.get_time_info");

  if (toolName === TOOL_NAMES.CLIPBOARD) {
    if (action === CLIPBOARD_ACTIONS.READ) return t("tool_part.clipboard_read");
    if (action === CLIPBOARD_ACTIONS.WRITE) return t("tool_part.clipboard_write");
  }

  if (toolName === TOOL_NAMES.ASK_USER) return t("tool_part.ask_user_title");

  return t("tool_part.tool_call_with_name", { toolName });
}

function JsonBlock({ value }: { value: unknown }) {
  return (
    <pre className="max-h-64 overflow-auto rounded-md border bg-muted/30 p-3 text-xs">
      {toJsonString(value)}
    </pre>
  );
}

function SearchWebPreview({ args, content }: { args: unknown; content: unknown }) {
  const { t } = useTranslation("message");
  const query = getStringField(args, "query") ?? "";
  const answer = getStringField(content, "answer");
  const items = getArrayField(content, "items");

  return (
    <div className="space-y-3">
      <div className="text-sm">
        {t("tool_part.search_query_label", { query: query || t("tool_part.empty") })}
      </div>
      {answer && (
        <div className="rounded-lg border bg-primary/5 p-3">
          <Markdown content={answer} className="text-sm" />
        </div>
      )}

      {items.length > 0 ? (
        <div className="space-y-2">
          {items.map((item, index) => {
            if (!item || typeof item !== "object" || Array.isArray(item)) {
              return null;
            }

            const record = item as Record<string, unknown>;
            const url = typeof record.url === "string" ? record.url : "";
            const title = typeof record.title === "string" ? record.title : "";
            const text = typeof record.text === "string" ? record.text : "";

            if (!url) return null;

            return (
              <a
                key={`${url}-${index}`}
                className="block rounded-lg border border-muted bg-card p-3 hover:bg-muted/40"
                href={url}
                rel="noreferrer"
                target="_blank"
              >
                <div className="line-clamp-1 font-medium text-sm">{title || url}</div>
                {text && (
                  <div className="mt-1 line-clamp-3 text-muted-foreground text-xs">{text}</div>
                )}
                <div className="mt-2 line-clamp-1 text-primary text-xs">{url}</div>
              </a>
            );
          })}
        </div>
      ) : (
        <JsonBlock value={content} />
      )}
    </div>
  );
}

function ScrapeWebPreview({ content }: { content: unknown }) {
  const urls = getArrayField(content, "urls");

  if (urls.length === 0) {
    return <JsonBlock value={content} />;
  }

  return (
    <div className="space-y-3">
      {urls.map((item, index) => {
        if (!item || typeof item !== "object" || Array.isArray(item)) {
          return null;
        }

        const record = item as Record<string, unknown>;
        const url = typeof record.url === "string" ? record.url : "";
        const text = typeof record.content === "string" ? record.content : "";

        return (
          <div key={`${url}-${index}`} className="space-y-2 rounded-lg border p-3">
            <div className="line-clamp-1 text-muted-foreground text-xs">{url}</div>
            <div className="rounded-md border bg-muted/20 p-2">
              <Markdown content={text} className="text-sm" />
            </div>
          </div>
        );
      })}
    </div>
  );
}

type AskUserSelectionType = "text" | "single" | "multi" | "slider" | "rating" | "confirm" | "date" | "time";

interface AskUserOption {
  value: string;
  label: string;
  badge: string;
  icon: string;
  image: string;
}

interface AskUserCondition {
  kind: "predicate" | "all" | "any" | "not";
  questionId?: string;
  operator?: "exists" | "equals" | "not_equals" | "contains" | "in" | "gt" | "gte" | "lt" | "lte";
  value?: unknown;
  expressions?: AskUserCondition[];
  expression?: AskUserCondition;
}

type AskUserValue = string | string[];

interface AskUserQuestion {
  id: string;
  question: string;
  options: AskUserOption[];
  selectionType: AskUserSelectionType;
  required: boolean;
  description: string;
  placeholder: string;
  defaultValue: string | undefined;
  defaultValues: string[];
  minLength: number | undefined;
  maxLength: number | undefined;
  minItems: number | undefined;
  maxItems: number | undefined;
  minValue: number | undefined;
  maxValue: number | undefined;
  step: number | undefined;
  unit: string;
  danger: boolean;
  timeoutSeconds: number | undefined;
  visibleIf: AskUserCondition | undefined;
}

interface ParsedAskUserRequest {
  questions: AskUserQuestion[];
  error?: string;
}

const ASK_USER_MAX_QUESTIONS = 32;
const ASK_USER_MAX_OPTIONS = 64;
const ASK_USER_MAX_ID_LENGTH = 128;
const ASK_USER_MAX_QUESTION_LENGTH = 4096;
const ASK_USER_MAX_OPTION_LENGTH = 512;
const ASK_USER_MAX_DESCRIPTION_LENGTH = 4096;
const ASK_USER_MAX_PLACEHOLDER_LENGTH = 512;
const ASK_USER_MAX_TEXT_ANSWER_LENGTH = 16 * 1024;
const ASK_USER_MAX_RICH_IMAGE_LENGTH = 2048;
const ASK_USER_MAX_RATING_STEPS = 10;

function parseFiniteNumber(value: unknown): number | undefined | null {
  if (value === undefined) return undefined;
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  if (typeof value === "string" && value.trim()) {
    const parsed = Number(value.trim());
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

function parseInteger(value: unknown, max: number): number | undefined | null {
  if (value === undefined) return undefined;
  const parsed = typeof value === "number"
    ? value
    : typeof value === "string" && /^\s*\d+\s*$/.test(value)
      ? Number(value.trim())
      : Number.NaN;
  if (!Number.isInteger(parsed) || parsed < 0 || parsed > max) return null;
  return parsed;
}

function isValidDateValue(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  try {
    return new Date(`${value}T00:00:00.000Z`).toISOString().slice(0, 10) === value;
  } catch {
    return false;
  }
}

function isValidTimeValue(value: string): boolean {
  const match = /^(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?$/.exec(value);
  if (!match) return false;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  const second = match[3] === undefined ? 0 : Number(match[3]);
  return hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59 && second >= 0 && second <= 59;
}

function isStepAligned(value: number, minimum: number, step: number): boolean {
  const steps = (value - minimum) / step;
  return Number.isFinite(steps) && Math.abs(steps - Math.round(steps)) <= 1e-7;
}

function parseAskUserCondition(value: unknown, depth = 0): AskUserCondition | null {
  if (!value || typeof value !== "object" || Array.isArray(value) || depth > 8) return null;
  const record = value as Record<string, unknown>;
  for (const kind of ["all", "any"] as const) {
    if (record[kind] !== undefined) {
      if (!Array.isArray(record[kind]) || record[kind].length === 0) return null;
      const expressions = record[kind]
        .map((item) => parseAskUserCondition(item, depth + 1))
        .filter((item): item is AskUserCondition => item !== null);
      if (expressions.length !== record[kind].length) return null;
      return { kind, expressions };
    }
  }
  if (record.not !== undefined) {
    const expression = parseAskUserCondition(record.not, depth + 1);
    return expression ? { kind: "not", expression } : null;
  }
  if (typeof record.question_id !== "string" || !record.question_id.trim()) return null;
  const operatorAliases: Record<string, AskUserCondition["operator"]> = {
    eq: "equals",
    equals: "equals",
    neq: "not_equals",
    not_equals: "not_equals",
    contains: "contains",
    in: "in",
    exists: "exists",
    gt: "gt",
    greater_than: "gt",
    gte: "gte",
    greater_or_equal: "gte",
    lt: "lt",
    less_than: "lt",
    lte: "lte",
    less_or_equal: "lte",
  };
  const rawOperator = record.operator === undefined
    ? "equals"
    : typeof record.operator === "string"
      ? record.operator.toLowerCase()
      : "";
  const operator = operatorAliases[rawOperator];
  if (!operator) return null;
  if (operator !== "exists" && record.value === undefined) return null;
  return {
    kind: "predicate",
    questionId: record.question_id.trim(),
    operator: operator as AskUserCondition["operator"],
    value: record.value,
  };
}

function conditionReferences(condition: AskUserCondition): string[] {
  if (condition.kind === "predicate") return condition.questionId ? [condition.questionId] : [];
  if (condition.kind === "not") return condition.expression ? conditionReferences(condition.expression) : [];
  return condition.expressions?.flatMap(conditionReferences) ?? [];
}

function askUserValueEquals(actual: AskUserValue | undefined, expected: unknown): boolean {
  if (actual === undefined || Array.isArray(actual) || typeof expected === "object" || expected === null) return false;
  const actualNumber = Number(actual);
  const expectedNumber = typeof expected === "number" ? expected : Number(expected);
  if (Number.isFinite(actualNumber) && Number.isFinite(expectedNumber) && actual.trim() !== "") {
    return actualNumber === expectedNumber;
  }
  return actual === String(expected);
}

function askUserValueIn(actual: AskUserValue | undefined, expected: unknown): boolean {
  if (!Array.isArray(expected)) return false;
  if (Array.isArray(actual)) {
    return actual.some((item) => expected.some((candidate) => askUserValueEquals(item, candidate)));
  }
  return expected.some((candidate) => askUserValueEquals(actual, candidate));
}

function evaluateAskUserCondition(condition: AskUserCondition, answers: Record<string, AskUserValue>): boolean {
  if (condition.kind === "all") return (condition.expressions ?? []).every((item) => evaluateAskUserCondition(item, answers));
  if (condition.kind === "any") return (condition.expressions ?? []).some((item) => evaluateAskUserCondition(item, answers));
  if (condition.kind === "not") return condition.expression ? !evaluateAskUserCondition(condition.expression, answers) : true;
  const actual = condition.questionId ? answers[condition.questionId] : undefined;
  switch (condition.operator) {
    case "exists": return actual !== undefined && (Array.isArray(actual) ? actual.length > 0 : actual.trim().length > 0);
    case "equals": return askUserValueEquals(actual, condition.value);
    case "not_equals": return !askUserValueEquals(actual, condition.value);
    case "contains":
      return Array.isArray(actual)
        ? actual.some((item) => askUserValueEquals(item, condition.value))
        : typeof actual === "string" && actual.includes(String(condition.value ?? ""));
    case "in":
      return askUserValueIn(actual, condition.value);
    case "gt":
    case "gte":
    case "lt":
    case "lte": {
      const actualNumber = Array.isArray(actual) ? Number.NaN : Number(actual);
      const expectedNumber = Number(condition.value);
      if (!Number.isFinite(actualNumber) || !Number.isFinite(expectedNumber)) return false;
      if (condition.operator === "gt") return actualNumber > expectedNumber;
      if (condition.operator === "gte") return actualNumber >= expectedNumber;
      if (condition.operator === "lt") return actualNumber < expectedNumber;
      return actualNumber <= expectedNumber;
    }
    default: return false;
  }
}

function parseAskUserQuestions(args: unknown): ParsedAskUserRequest {
  try {
    const questions = getArrayField(args, "questions");
    if (questions.length === 0 || questions.length > ASK_USER_MAX_QUESTIONS) {
      return { questions: [], error: "invalid" };
    }
    const parsed = questions
      .map((q, index) => {
        if (!q || typeof q !== "object" || Array.isArray(q)) return null;
        const record = q as Record<string, unknown>;
        const id = typeof record.id === "string" ? record.id.trim() : "";
        const question = typeof record.question === "string" ? record.question.trim() : "";
        if (!id || !question || id.length > ASK_USER_MAX_ID_LENGTH || question.length > ASK_USER_MAX_QUESTION_LENGTH) {
          return null;
        }
        if (record.options !== undefined && !Array.isArray(record.options)) return null;
        const rawOptions = Array.isArray(record.options) ? record.options : [];
        if (rawOptions.length > ASK_USER_MAX_OPTIONS) return null;
        const hadBaseOptions = rawOptions.length > 0;
        const options: AskUserOption[] = [];
        for (const option of rawOptions) {
          const parsedOption = typeof option === "string"
            ? { value: option.trim(), label: option.trim(), badge: "", icon: "", image: "" }
            : option && typeof option === "object" && !Array.isArray(option)
              ? (() => {
                  const item = option as Record<string, unknown>;
                  return {
                    value: typeof item.value === "string" ? item.value.trim() : "",
                    label: typeof item.label === "string" && item.label.trim() ? item.label : (typeof item.value === "string" ? item.value : ""),
                    badge: typeof item.badge === "string" ? item.badge : "",
                    icon: typeof item.icon === "string" ? item.icon : "",
                    image: typeof item.image === "string" ? item.image : "",
                  };
                })()
              : null;
          if (!parsedOption || !parsedOption.value || parsedOption.value.length > ASK_USER_MAX_OPTION_LENGTH ||
            parsedOption.label.length > ASK_USER_MAX_OPTION_LENGTH || parsedOption.badge.length > ASK_USER_MAX_OPTION_LENGTH ||
            parsedOption.icon.length > ASK_USER_MAX_OPTION_LENGTH || parsedOption.image.length > ASK_USER_MAX_RICH_IMAGE_LENGTH) return null;
          options.push(parsedOption);
        }
        const optionDetails = Array.isArray(record.option_details) ? record.option_details : [];
        for (const detail of optionDetails) {
          if (!detail || typeof detail !== "object" || Array.isArray(detail)) return null;
          const item = detail as Record<string, unknown>;
          const value = typeof item.value === "string" ? item.value.trim() : "";
          if (!value) return null;
          const index = options.findIndex((option) => option.value === value);
          const rich: AskUserOption = {
            value,
            label: typeof item.label === "string" && item.label.trim() ? item.label : value,
            badge: typeof item.badge === "string" ? item.badge : "",
            icon: typeof item.icon === "string" ? item.icon : "",
            image: typeof item.image === "string" ? item.image : "",
          };
          if (rich.value.length > ASK_USER_MAX_OPTION_LENGTH ||
            rich.label.length > ASK_USER_MAX_OPTION_LENGTH ||
            rich.badge.length > ASK_USER_MAX_OPTION_LENGTH ||
            rich.icon.length > ASK_USER_MAX_OPTION_LENGTH ||
            rich.image.length > ASK_USER_MAX_RICH_IMAGE_LENGTH) return null;
          if (index < 0) {
            if (hadBaseOptions) return null;
            options.push(rich);
          }
          else options[index] = { ...options[index], ...rich };
        }
        if (new Set(options.map((option) => option.value)).size !== options.length) return null;
        if (options.length > ASK_USER_MAX_OPTIONS) return null;
        const rawSelection = record.selection_type;
        const selectionType = rawSelection === undefined
          ? "text"
          : typeof rawSelection === "string" &&
              (rawSelection.toLowerCase() === "text" ||
                rawSelection.toLowerCase() === "single" ||
                rawSelection.toLowerCase() === "multi" ||
                rawSelection.toLowerCase() === "slider" ||
                rawSelection.toLowerCase() === "rating" ||
                rawSelection.toLowerCase() === "confirm" ||
                rawSelection.toLowerCase() === "date" ||
                rawSelection.toLowerCase() === "time")
            ? rawSelection.toLowerCase() as AskUserSelectionType
            : null;
        if (selectionType == null) {
          return null;
        }
        if ((selectionType === "single" || selectionType === "multi") && options.length === 0) {
          return null;
        }
        if (record.required !== undefined && typeof record.required !== "boolean") return null;
        const required = record.required === undefined ? true : record.required;
        if (record.description !== undefined && typeof record.description !== "string") return null;
        if (record.placeholder !== undefined && typeof record.placeholder !== "string") return null;
        const description = typeof record.description === "string" ? record.description : "";
        const placeholder = typeof record.placeholder === "string" ? record.placeholder : "";
        if (description.length > ASK_USER_MAX_DESCRIPTION_LENGTH || placeholder.length > ASK_USER_MAX_PLACEHOLDER_LENGTH) {
          return null;
        }
        const minLength = parseInteger(record.min_length, ASK_USER_MAX_TEXT_ANSWER_LENGTH);
        const maxLength = parseInteger(record.max_length, ASK_USER_MAX_TEXT_ANSWER_LENGTH);
        const minItems = parseInteger(record.min_items, ASK_USER_MAX_OPTIONS);
        const maxItems = parseInteger(record.max_items, ASK_USER_MAX_OPTIONS);
        if (minLength === null || maxLength === null || minItems === null || maxItems === null) return null;
        if (selectionType === "text" && (record.min_items != null || record.max_items != null)) return null;
        if (selectionType !== "text" && (record.min_length != null || record.max_length != null)) return null;
        if (minLength != null && maxLength != null && minLength > maxLength) return null;
        if (minItems != null && maxItems != null && minItems > maxItems) return null;
        if (selectionType !== "multi" && (record.min_items != null || record.max_items != null)) return null;
        if (minItems != null && minItems > options.length) return null;
        if (maxItems != null && maxItems > options.length) return null;
        const numericSelection = selectionType === "slider" || selectionType === "rating";
        const parsedMinValue = parseFiniteNumber(record.min_value);
        const parsedMaxValue = parseFiniteNumber(record.max_value);
        const parsedStep = parseFiniteNumber(record.step);
        if (parsedMinValue === null || parsedMaxValue === null || parsedStep === null) return null;
        const minValue = numericSelection ? (parsedMinValue !== undefined ? parsedMinValue : selectionType === "rating" ? 1 : undefined) : undefined;
        const maxValue = numericSelection ? (parsedMaxValue !== undefined ? parsedMaxValue : selectionType === "rating" ? 5 : undefined) : undefined;
        const step = numericSelection ? (parsedStep !== undefined ? parsedStep : selectionType === "rating" ? 1 : undefined) : undefined;
        if (numericSelection && (minValue == null || maxValue == null || step == null || !Number.isFinite(minValue) || !Number.isFinite(maxValue) || !Number.isFinite(step) || minValue >= maxValue || step <= 0 || step > maxValue - minValue)) return null;
        if (selectionType === "rating" && minValue != null && maxValue != null && step != null) {
          const ratingSteps = (maxValue - minValue) / step;
          if (!Number.isFinite(ratingSteps) || ratingSteps < 1 || ratingSteps > ASK_USER_MAX_RATING_STEPS - 1 ||
            Math.abs(ratingSteps - Math.round(ratingSteps)) > 1e-7) return null;
        }
        if (!numericSelection && (record.min_value != null || record.max_value != null || record.step != null || record.unit != null)) return null;
        const unit = typeof record.unit === "string" ? record.unit : "";
        if (unit.length > 64) return null;
        const danger = record.danger === undefined ? false : record.danger;
        if (typeof danger !== "boolean") return null;
        const timeoutSecondsValue = parseInteger(record.timeout_seconds, 3600);
        if (selectionType === "confirm") {
          if (timeoutSecondsValue != null && (!Number.isInteger(timeoutSecondsValue) || timeoutSecondsValue < 1 || timeoutSecondsValue > 3600)) return null;
        } else if (danger || timeoutSecondsValue != null) return null;
        if (timeoutSecondsValue === null) return null;
        const parsedVisibleIf = record.visible_if === undefined ? undefined : parseAskUserCondition(record.visible_if);
        if (record.visible_if !== undefined && !parsedVisibleIf) return null;
        const timeoutSeconds = timeoutSecondsValue;
        const visibleIf: AskUserCondition | undefined = parsedVisibleIf ?? undefined;
        let defaultValue: string | undefined;
        let defaultValues: string[] = [];
        if (record.default !== undefined) {
          if (selectionType === "multi" && typeof record.default === "string") {
            defaultValues = record.default.split(",").map((item) => item.trim()).filter(Boolean);
          } else if (selectionType === "multi" && Array.isArray(record.default) && record.default.every((item) => typeof item === "string")) {
            defaultValues = (record.default as string[]).map((item) => item.trim()).filter(Boolean);
          } else if ((selectionType === "text" || selectionType === "single" || selectionType === "date" || selectionType === "time") && typeof record.default === "string") {
            defaultValue = selectionType === "single" ? record.default.trim() : record.default;
          } else if ((selectionType === "slider" || selectionType === "rating") &&
            ((typeof record.default === "number" && Number.isFinite(record.default)) ||
              (typeof record.default === "string" && Number.isFinite(Number(record.default))))) {
            defaultValue = String(record.default);
          } else if (selectionType === "confirm" &&
            (typeof record.default === "boolean" ||
              (typeof record.default === "string" &&
                (record.default.trim() === "true" || record.default.trim() === "false")))) {
            defaultValue = String(record.default).trim().toLowerCase();
          } else {
            return null;
          }
        }
        if ((selectionType === "date" && defaultValue != null && !isValidDateValue(defaultValue)) ||
          (selectionType === "time" && defaultValue != null && !isValidTimeValue(defaultValue))) return null;
        if (selectionType === "single" && defaultValue && !options.some((option) => option.value === defaultValue.trim())) return null;
        if (selectionType === "multi" && (
          new Set(defaultValues).size !== defaultValues.length ||
          (minItems != null && defaultValues.length < minItems) ||
          (maxItems != null && defaultValues.length > maxItems) ||
          defaultValues.some((item) => !options.some((option) => option.value === item))
        )) return null;
        if (selectionType === "text" && defaultValue != null && (
          (defaultValue.trim().length > 0 && minLength != null && defaultValue.length < minLength) ||
          (maxLength != null && defaultValue.length > maxLength)
        )) return null;
        if ((selectionType === "slider" || selectionType === "rating") && defaultValue != null) {
          const numericDefault = Number(defaultValue);
          if (!Number.isFinite(numericDefault) || numericDefault < minValue! || numericDefault > maxValue!) return null;
          if (!isStepAligned(numericDefault, minValue!, step!)) return null;
        }
        return {
          id,
          question,
          options,
          selectionType,
          required,
          description,
          placeholder,
          defaultValue,
          defaultValues,
          minLength,
          maxLength,
          minItems,
          maxItems,
          minValue,
          maxValue,
          step,
          unit,
          danger,
          timeoutSeconds,
          visibleIf,
        } satisfies AskUserQuestion;
      })
      .filter((q): q is AskUserQuestion => q !== null);
    if (parsed.length !== questions.length) {
      return { questions: [], error: "invalid" };
    }
    if (new Set(parsed.map((q) => q.id)).size !== parsed.length) {
      return { questions: [], error: "invalid" };
    }
    const ids = new Set(parsed.map((q) => q.id));
    for (const question of parsed) {
      if (question.visibleIf && conditionReferences(question.visibleIf).some((id) => !ids.has(id) || id === question.id)) {
        return { questions: [], error: "invalid" };
      }
    }
    return { questions: parsed };
  } catch {
    return { questions: [], error: "invalid" };
  }
}

function AskUserToolStep({
  tool,
  loading,
  onToolApproval,
  isFirst,
  isLast,
}: ToolPartProps) {
  const { t } = useTranslation("message");
  const [expanded, setExpanded] = React.useState(true);

  const args = React.useMemo(() => safeJsonParse(tool.input), [tool.input]);
  const parsedRequest = React.useMemo(() => parseAskUserQuestions(args), [args]);
  const questions = parsedRequest.questions;
  const initialAnswers = React.useMemo(() => Object.fromEntries(
    questions
      .map((question) => {
        const value = question.selectionType === "multi"
          ? question.defaultValues
          : question.defaultValue ?? ((question.selectionType === "slider" || question.selectionType === "rating") && question.minValue != null
            ? String(question.minValue)
            : undefined);
        return [question.id, value] as const;
      })
      .filter((entry): entry is [string, AskUserValue] => entry[1] != null),
  ), [questions]);
  const [answers, setAnswers] = React.useState<Record<string, AskUserValue>>(initialAnswers);

  React.useEffect(() => {
    setAnswers(initialAnswers);
  }, [initialAnswers, tool.toolCallId, tool.input]);

  const isPending = tool.approvalState.type === "pending";
  const isAnswered = tool.approvalState.type === "answered";
  const isDenied = tool.approvalState.type === "denied";
  const isCancelled = tool.approvalState.type === "cancelled";
  const isExpired = tool.approvalState.type === "expired";

  const firstQuestion = questions[0]?.question ?? "...";
  const title =
    questions.length <= 1
      ? firstQuestion
      : t("tool_part.ask_user_questions_count", { count: questions.length });

  const visibleQuestions = React.useMemo(
    () => questions.filter((question) => !question.visibleIf || evaluateAskUserCondition(question.visibleIf, answers)),
    [answers, questions],
  );
  const visibleQuestionIds = React.useMemo(() => new Set(visibleQuestions.map((question) => question.id)), [visibleQuestions]);
  React.useEffect(() => {
    setAnswers((previous) => {
      const next = Object.fromEntries(
        Object.entries(previous).filter(([id]) => visibleQuestionIds.has(id)),
      ) as Record<string, AskUserValue>;
      return Object.keys(next).length === Object.keys(previous).length ? previous : next;
    });
  }, [visibleQuestionIds]);

  const confirmQuestion = visibleQuestions.find((question) =>
    question.selectionType === "confirm" && (question.danger || question.timeoutSeconds != null),
  );
  const metadataDeadline = typeof tool.metadata?.ask_user_confirm_deadline_at === "number"
    ? tool.metadata.ask_user_confirm_deadline_at
    : undefined;
  const [confirmSecondsRemaining, setConfirmSecondsRemaining] = React.useState<number | undefined>();
  const timeoutHandled = React.useRef(false);
  React.useEffect(() => {
    timeoutHandled.current = false;
    if (!isPending || !confirmQuestion) {
      setConfirmSecondsRemaining(undefined);
      return;
    }
    const timeoutSeconds = confirmQuestion.timeoutSeconds ?? 30;
    const deadline = metadataDeadline ?? Date.now() + timeoutSeconds * 1000;
    const update = () => {
      const remaining = Math.max(0, Math.ceil((deadline - Date.now()) / 1000));
      setConfirmSecondsRemaining(remaining);
      if (remaining === 0 && !timeoutHandled.current) {
        timeoutHandled.current = true;
        void onToolApproval?.(
          tool.toolCallId,
          false,
          "Confirmation timed out and was rejected",
          undefined,
          true,
        );
      }
    };
    update();
    const interval = window.setInterval(update, 1000);
    return () => window.clearInterval(interval);
  }, [confirmQuestion, isPending, metadataDeadline, onToolApproval, tool.toolCallId]);

  const allAnswered =
    questions.length > 0 &&
    visibleQuestions.every((q) => {
      const value = answers[q.id];
      if (Array.isArray(value)) {
        return (value.length > 0 || !q.required) &&
          (q.minItems == null || value.length >= q.minItems) &&
          (q.maxItems == null || value.length <= q.maxItems);
      }
      if (q.selectionType === "confirm") {
        return q.required
          ? value === "true" || value === "false"
          : value === undefined || value === "true" || value === "false";
      }
      if (q.selectionType === "slider" || q.selectionType === "rating") {
        const numericValue = Number(value);
        return Number.isFinite(numericValue) &&
          q.minValue != null && q.maxValue != null && q.step != null &&
          numericValue >= q.minValue && numericValue <= q.maxValue &&
          isStepAligned(numericValue, q.minValue, q.step);
      }
      if (!value?.trim()) return !q.required;
      if (q.selectionType === "single" && !q.options.some((option) => option.value === value.trim())) return false;
      if (q.selectionType === "date" && !isValidDateValue(value)) return false;
      if (q.selectionType === "time" && !isValidTimeValue(value)) return false;
      return (q.minLength == null || value.length >= q.minLength) &&
        (q.maxLength == null || value.length <= q.maxLength);
    });

  const handleSubmit = () => {
    if (!onToolApproval || !allAnswered) return;
    const payload = JSON.stringify({
      answers: Object.fromEntries(
        visibleQuestions
          .map((q) => {
            const value = answers[q.id];
            if (value == null) return [q.id, undefined] as const;
            if (q.selectionType === "slider" || q.selectionType === "rating") return [q.id, Number(value)] as const;
            if (q.selectionType === "confirm") return [q.id, value === "true"] as const;
            return [q.id, value] as const;
          })
          .filter((entry): entry is [string, AskUserValue | number | boolean] => entry[1] != null),
      ),
    });
    void onToolApproval(tool.toolCallId, true, "", payload);
  };

  const setAnswer = (questionId: string, value: AskUserValue) => {
    setAnswers((prev) => ({ ...prev, [questionId]: value }));
  };

  // Parse answered state for display
  const answeredValues = React.useMemo(() => {
    if (tool.approvalState.type !== "answered") return {};
    try {
      const parsed = JSON.parse(tool.approvalState.answer) as { answers?: Record<string, unknown> };
      return parsed.answers ?? {};
    } catch {
      return {};
    }
  }, [tool.approvalState]);

  return (
    <ControlledChainOfThoughtStep
      expanded={expanded}
      onExpandedChange={setExpanded}
      isFirst={isFirst}
      isLast={isLast}
      icon={
        loading ? (
          <Loader2 className="h-4 w-4 animate-spin text-primary" />
        ) : (
          <MessageCircleQuestion className="h-4 w-4 text-primary" />
        )
      }
      label={<span className="text-foreground line-clamp-2 text-sm font-medium">{title}</span>}
    >
      <div className="space-y-3 w-full">
        {parsedRequest.error && (
          <div className="text-destructive text-sm">{t("tool_part.ask_user_invalid_request")}</div>
        )}
        {isCancelled && (
          <div className="text-muted-foreground text-sm">
            {tool.approvalState.type === "cancelled" && tool.approvalState.reason
              ? tool.approvalState.reason
              : t("tool_part.ask_user_default_cancel_reason")}
          </div>
        )}
        {isDenied && (
          <div className="text-destructive text-sm">
            {tool.approvalState.type === "denied" && tool.approvalState.reason
              ? tool.approvalState.reason
              : t("tool_part.ask_user_denied")}
          </div>
        )}
        {isExpired && (
          <div className="text-muted-foreground text-sm">
            {tool.approvalState.type === "expired" && tool.approvalState.reason
              ? tool.approvalState.reason
              : t("tool_part.ask_user_default_expired_reason")}
          </div>
        )}
        {visibleQuestions.map((q) => (
          <div key={q.id} className="space-y-1.5">
            {questions.length > 1 && (
              <div className="text-sm text-foreground">{q.question}</div>
            )}
            {q.description && (
              <div className="text-muted-foreground text-xs">{q.description}</div>
            )}

            {isPending && onToolApproval ? (
              <>
                {(q.selectionType === "text" || q.selectionType === "single") && q.options.length > 0 && (
                  <div className="flex flex-wrap gap-1.5">
                    {q.options.map((option, index) => (
                      <button
                        key={`${q.id}-${index}`}
                        type="button"
                        onClick={() => setAnswer(q.id, option.value)}
                        className={`rounded-full border px-3 py-1 text-xs transition-colors ${
                          answers[q.id] === option.value
                            ? "border-primary bg-primary/10 text-primary"
                            : "border-muted-foreground/30 text-muted-foreground hover:border-primary/50"
                        }`}
                      >
                        <span className="flex items-center gap-1.5">
                          {option.image && <img alt="" className="h-5 w-5 rounded object-cover" src={option.image} />}
                          {option.icon && <span aria-hidden="true">{option.icon}</span>}
                          <span>{option.label || option.value}</span>
                          {option.badge && <span className="rounded bg-primary/10 px-1 text-[10px] text-primary">{option.badge}</span>}
                        </span>
                      </button>
                    ))}
                  </div>
                )}
                {q.selectionType === "multi" && (
                  <div className="flex flex-wrap gap-1.5">
                    {q.options.map((option, index) => {
                      const current = answers[q.id];
                      const selected = Array.isArray(current) && current.includes(option.value);
                      return (
                        <button
                          key={`${q.id}-${index}`}
                          type="button"
                          onClick={() => {
                            const current = answers[q.id];
                            const selectedValues: string[] = Array.isArray(current) ? current : [];
                            setAnswer(q.id, selectedValues.includes(option.value)
                              ? selectedValues.filter((value: string) => value !== option.value)
                              : [...selectedValues, option.value]);
                          }}
                          className={`rounded-full border px-3 py-1 text-xs transition-colors ${
                            selected
                              ? "border-primary bg-primary/10 text-primary"
                              : "border-muted-foreground/30 text-muted-foreground hover:border-primary/50"
                          }`}
                        >
                        <span className="flex items-center gap-1.5">
                          {option.image && <img alt="" className="h-5 w-5 rounded object-cover" src={option.image} />}
                          {option.icon && <span aria-hidden="true">{option.icon}</span>}
                          <span>{option.label || option.value}</span>
                          {option.badge && <span className="rounded bg-primary/10 px-1 text-[10px] text-primary">{option.badge}</span>}
                        </span>
                        </button>
                      );
                    })}
                  </div>
                )}
                {q.selectionType === "text" && (
                  <Input
                    value={typeof answers[q.id] === "string" ? answers[q.id] : ""}
                    onChange={(e) => setAnswer(
                      q.id,
                      e.target.value.slice(0, q.maxLength ?? ASK_USER_MAX_TEXT_ANSWER_LENGTH),
                    )}
                    placeholder={q.placeholder || q.question}
                    className="text-sm"
                    onKeyDown={(e) => {
                      if (e.key === "Enter" && !e.shiftKey && allAnswered) {
                        e.preventDefault();
                        handleSubmit();
                      }
                    }}
                  />
                )}
                {q.selectionType === "slider" && q.minValue != null && q.maxValue != null && q.step != null && (
                  <div className="space-y-1">
                    <input
                      aria-label={q.question}
                      className="w-full accent-primary"
                      max={q.maxValue}
                      min={q.minValue}
                      onChange={(event) => setAnswer(q.id, event.target.value)}
                      step={q.step}
                      type="range"
                      value={typeof answers[q.id] === "string" ? answers[q.id] : String(q.minValue)}
                    />
                    <div className="text-muted-foreground text-xs">
                      {answers[q.id] ?? q.minValue}{q.unit ? ` ${q.unit}` : ""}
                    </div>
                  </div>
                )}
                {q.selectionType === "rating" && q.minValue != null && q.maxValue != null && q.step != null && (
                  <div className="flex flex-wrap items-center gap-0.5">
                    {Array.from({ length: Math.min(10, Math.floor((q.maxValue - q.minValue) / q.step) + 1) }).map((_, index) => {
                      const value = Math.min(q.maxValue!, q.minValue! + index * q.step!);
                      const current = Number(answers[q.id] ?? q.minValue);
                      return (
                        <button
                          aria-label={String(value)}
                          className={`px-1 text-xl ${value <= current ? "text-primary" : "text-muted-foreground/50"}`}
                          key={`${q.id}-rating-${index}`}
                          onClick={() => setAnswer(q.id, String(value))}
                          type="button"
                        >
                          {value <= current ? "★" : "☆"}
                        </button>
                      );
                    })}
                    {q.unit && <span className="ml-1 text-muted-foreground text-xs">{q.unit}</span>}
                  </div>
                )}
                {q.selectionType === "confirm" && (
                  <div className={`flex flex-wrap items-center gap-2 rounded-md border p-2 ${q.danger ? "border-destructive/60 bg-destructive/5" : "border-muted"}`}>
                    <Button
                      onClick={() => setAnswer(q.id, "true")}
                      size="sm"
                      type="button"
                      variant={answers[q.id] === "true" ? "destructive" : "outline"}
                    >
                      {t("tool_part.ask_user_confirm_yes")}
                    </Button>
                    <Button
                      onClick={() => setAnswer(q.id, "false")}
                      size="sm"
                      type="button"
                      variant={answers[q.id] === "false" ? "secondary" : "outline"}
                    >
                      {t("tool_part.ask_user_confirm_no")}
                    </Button>
                    {confirmQuestion?.id === q.id && confirmSecondsRemaining != null && (
                      <span className={`text-xs ${confirmSecondsRemaining <= 5 ? "text-destructive" : "text-muted-foreground"}`}>
                        {confirmSecondsRemaining}s
                      </span>
                    )}
                  </div>
                )}
                {(q.selectionType === "date" || q.selectionType === "time") && (
                  <Input
                    aria-label={q.question}
                    onChange={(event) => setAnswer(q.id, event.target.value)}
                    type={q.selectionType}
                    value={typeof answers[q.id] === "string" ? answers[q.id] : ""}
                  />
                )}
              </>
            ) : isAnswered ? (
              <div className="text-sm text-primary">
                {(() => {
                  const value = answeredValues[q.id];
                  return Array.isArray(value)
                    ? value.map(String).join(", ")
                    : typeof value === "string"
                      ? value
                      : "";
                })()}
              </div>
            ) : null}
          </div>
        ))}

        {isPending && onToolApproval && (
          <div className="flex justify-end gap-2">
            <Button
              size="sm"
              variant="ghost"
              onClick={() => void onToolApproval(tool.toolCallId, false, "", undefined, true)}
            >
              {t("tool_part.cancel")}
            </Button>
            <Button
              size="sm"
              variant="secondary"
              disabled={!allAnswered}
              onClick={handleSubmit}
            >
              <Send className="mr-1.5 h-3.5 w-3.5" />
              {t("tool_part.ask_user_submit")}
            </Button>
          </div>
        )}
      </div>
    </ControlledChainOfThoughtStep>
  );
}

export function ToolPart({
  tool,
  loading = false,
  onToolApproval,
  isFirst,
  isLast,
}: ToolPartProps) {
  if (tool.toolName === TOOL_NAMES.ASK_USER) {
    return (
      <AskUserToolStep
        tool={tool}
        loading={loading}
        onToolApproval={onToolApproval}
        isFirst={isFirst}
        isLast={isLast}
      />
    );
  }

  const { t } = useTranslation("message");
  const isMobile = useIsMobile();
  const [expanded, setExpanded] = React.useState(true);
  const [drawerOpen, setDrawerOpen] = React.useState(false);

  const args = React.useMemo(() => safeJsonParse(tool.input), [tool.input]);

  const outputText = React.useMemo(
    () =>
      tool.output
        .filter((part): part is UITextPart => part.type === "text")
        .map((part) => part.text)
        .join("\n"),
    [tool.output],
  );

  const outputContent = React.useMemo(() => safeJsonParse(outputText), [outputText]);

  const hasMediaOutput = React.useMemo(
    () => tool.output.some((p) => p.type === "image" || p.type === "video" || p.type === "audio"),
    [tool.output],
  );

  const memoryAction = getStringField(args, "action");
  const title = getToolTitle(tool.toolName, args, t);
  const isPending = tool.approvalState.type === "pending";
  const isDenied = tool.approvalState.type === "denied";
  const deniedReason =
    tool.approvalState.type === "denied" ? (tool.approvalState.reason ?? "") : "";
  const isExecuted = tool.output.length > 0;

  const hasExtraContent =
    (tool.toolName === TOOL_NAMES.MEMORY &&
      (memoryAction === MEMORY_ACTIONS.CREATE || memoryAction === MEMORY_ACTIONS.EDIT) &&
      Boolean(getStringField(outputContent, "content"))) ||
    (tool.toolName === TOOL_NAMES.SEARCH_WEB &&
      (Boolean(getStringField(outputContent, "answer")) ||
        getArrayField(outputContent, "items").length > 0)) ||
    (tool.toolName === TOOL_NAMES.SCRAPE_WEB && Boolean(getStringField(args, "url"))) ||
    isDenied ||
    hasMediaOutput;

  const canOpenDrawer = isPending || isExecuted;
  const Icon = getToolIcon(tool.toolName, memoryAction);

  const handleApprove = async (event: React.MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    if (!onToolApproval) return;
    await onToolApproval(tool.toolCallId, true, "");
  };

  const handleDeny = async (event: React.MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation();
    if (!onToolApproval) return;
    const reason = window.prompt(t("tool_part.deny_reason_prompt"), "");
    if (reason === null) return;
    await onToolApproval(tool.toolCallId, false, reason);
  };

  return (
    <>
      <ControlledChainOfThoughtStep
        expanded={expanded}
        onExpandedChange={setExpanded}
        isFirst={isFirst}
        isLast={isLast}
        icon={
          loading ? (
            <Loader2 className="h-4 w-4 animate-spin text-primary" />
          ) : (
            <Icon className="h-4 w-4 text-primary" />
          )
        }
        label={<span className="text-foreground line-clamp-2 text-sm font-medium">{title}</span>}
        extra={
          isPending && onToolApproval ? (
            <div className="flex items-center gap-1">
              <Button onClick={handleDeny} size="icon-xs" type="button" variant="secondary">
                <X className="h-3.5 w-3.5" />
              </Button>
              <Button onClick={handleApprove} size="icon-xs" type="button" variant="secondary">
                <Check className="h-3.5 w-3.5" />
              </Button>
            </div>
          ) : undefined
        }
        onClick={canOpenDrawer ? () => setDrawerOpen(true) : undefined}
      >
        {hasExtraContent && (
          <div className="space-y-1">
            {tool.toolName === TOOL_NAMES.MEMORY &&
              (memoryAction === MEMORY_ACTIONS.CREATE || memoryAction === MEMORY_ACTIONS.EDIT) && (
                <div className="line-clamp-3 text-muted-foreground text-xs">
                  {getStringField(outputContent, "content")}
                </div>
              )}

            {tool.toolName === TOOL_NAMES.SEARCH_WEB && getStringField(outputContent, "answer") && (
              <div className="line-clamp-3 text-muted-foreground text-xs">
                {getStringField(outputContent, "answer")}
              </div>
            )}

            {tool.toolName === TOOL_NAMES.SEARCH_WEB &&
              getArrayField(outputContent, "items").length > 0 && (
                <div className="text-muted-foreground text-xs">
                  {t("tool_part.search_results_count", {
                    count: getArrayField(outputContent, "items").length,
                  })}
                </div>
              )}

            {tool.toolName === TOOL_NAMES.SCRAPE_WEB && getStringField(args, "url") && (
              <div className="line-clamp-2 text-muted-foreground text-xs">
                {getStringField(args, "url")}
              </div>
            )}

            {isDenied && (
              <div className="text-destructive text-xs">
                {deniedReason
                  ? t("tool_part.denied_with_reason", { reason: deniedReason })
                  : t("tool_part.denied")}
              </div>
            )}

            {hasMediaOutput && (
              <div className="flex flex-wrap gap-1">
                {tool.output.map((part, i) => {
                  if (part.type === "image") {
                    return (
                      <img
                        key={i}
                        alt=""
                        className="h-16 w-auto rounded border border-muted object-contain"
                        src={resolveFileUrl(part.url)}
                      />
                    );
                  }
                  if (part.type === "video") {
                    return (
                      <span
                        key={i}
                        className="inline-flex items-center gap-1 rounded border border-muted bg-muted/30 px-2 py-1 text-muted-foreground text-xs"
                      >
                        <Video className="h-3 w-3" />
                        video
                      </span>
                    );
                  }
                  if (part.type === "audio") {
                    return (
                      <span
                        key={i}
                        className="inline-flex items-center gap-1 rounded border border-muted bg-muted/30 px-2 py-1 text-muted-foreground text-xs"
                      >
                        <AudioLines className="h-3 w-3" />
                        audio
                      </span>
                    );
                  }
                  return null;
                })}
              </div>
            )}
          </div>
        )}
      </ControlledChainOfThoughtStep>

      <Drawer
        direction={isMobile ? "bottom" : "right"}
        open={drawerOpen}
        onOpenChange={setDrawerOpen}
      >
        <DrawerContent>
          <DrawerHeader>
            <DrawerTitle>{title}</DrawerTitle>
            <DrawerDescription>
              {t("tool_part.tool_name_label", { toolName: tool.toolName })}
            </DrawerDescription>
          </DrawerHeader>

          <div className="flex-1 min-h-0 space-y-4 overflow-y-auto px-4 pb-6">
            {tool.toolName === TOOL_NAMES.SEARCH_WEB && isExecuted ? (
              <SearchWebPreview args={args} content={outputContent} />
            ) : tool.toolName === TOOL_NAMES.SCRAPE_WEB && isExecuted ? (
              <ScrapeWebPreview content={outputContent} />
            ) : (
              <div className="space-y-3">
                <div>
                  <div className="mb-1 text-muted-foreground text-xs">
                    {t("tool_part.parameters")}
                  </div>
                  <JsonBlock value={args} />
                </div>
                {isExecuted && (
                  <div className="space-y-2">
                    <div className="mb-1 text-muted-foreground text-xs">
                      {t("tool_part.result")}
                    </div>
                    {tool.output.map((part, i) => {
                      if (part.type === "text") {
                        let parsed: unknown;
                        try {
                          parsed = JSON.parse(part.text);
                        } catch {
                          parsed = part.text;
                        }
                        return <JsonBlock key={i} value={parsed} />;
                      }
                      if (part.type === "image") return <ImagePartRenderer key={i} url={part.url} />;
                      if (part.type === "video") return <VideoPartRenderer key={i} url={part.url} />;
                      if (part.type === "audio") return <AudioPartRenderer key={i} url={part.url} />;
                      return null;
                    })}
                  </div>
                )}
                {!isExecuted && (
                  <div className="text-muted-foreground text-sm">{t("tool_part.not_executed")}</div>
                )}
              </div>
            )}
          </div>
        </DrawerContent>
      </Drawer>
    </>
  );
}
