var IndexAfter = (() => {
  var __create = Object.create;
  var __defProp = Object.defineProperty;
  var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
  var __getOwnPropNames = Object.getOwnPropertyNames;
  var __getProtoOf = Object.getPrototypeOf;
  var __hasOwnProp = Object.prototype.hasOwnProperty;
  var __commonJS = (cb, mod) => function __require() {
    return mod || (0, cb[__getOwnPropNames(cb)[0]])((mod = { exports: {} }).exports, mod), mod.exports;
  };
  var __export = (target, all) => {
    for (var name in all)
      __defProp(target, name, { get: all[name], enumerable: true });
  };
  var __copyProps = (to, from, except, desc) => {
    if (from && typeof from === "object" || typeof from === "function") {
      for (let key of __getOwnPropNames(from))
        if (!__hasOwnProp.call(to, key) && key !== except)
          __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
    }
    return to;
  };
  var __toESM = (mod, isNodeMode, target) => (target = mod != null ? __create(__getProtoOf(mod)) : {}, __copyProps(
    // If the importer is in node compatibility mode or this is not an ESM
    // file that has been converted to a CommonJS file using a Babel-
    // compatible transform (i.e. "__esModule" has not been set), then set
    // "default" to the CommonJS "module.exports" for node compatibility.
    isNodeMode || !mod || !mod.__esModule ? __defProp(target, "default", { value: mod, enumerable: true }) : target,
    mod
  ));
  var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);

  // apps/web/node_modules/ignore/index.js
  var require_ignore = __commonJS({
    "apps/web/node_modules/ignore/index.js"(exports, module) {
      function makeArray(subject) {
        return Array.isArray(subject) ? subject : [subject];
      }
      var UNDEFINED = void 0;
      var EMPTY = "";
      var SPACE = " ";
      var ESCAPE = "\\";
      var REGEX_TEST_BLANK_LINE = /^\s+$/;
      var REGEX_INVALID_TRAILING_BACKSLASH = /(?:[^\\]|^)\\$/;
      var REGEX_REPLACE_LEADING_EXCAPED_EXCLAMATION = /^\\!/;
      var REGEX_REPLACE_LEADING_EXCAPED_HASH = /^\\#/;
      var REGEX_SPLITALL_CRLF = /\r?\n/g;
      var REGEX_TEST_INVALID_PATH = /^\.{0,2}\/|^\.{1,2}$/;
      var REGEX_TEST_TRAILING_SLASH = /\/$/;
      var SLASH = "/";
      var TMP_KEY_IGNORE = "node-ignore";
      if (typeof Symbol !== "undefined") {
        TMP_KEY_IGNORE = Symbol.for("node-ignore");
      }
      var KEY_IGNORE = TMP_KEY_IGNORE;
      var define = (object, key, value) => {
        Object.defineProperty(object, key, { value });
        return value;
      };
      var REGEX_REGEXP_RANGE = /([0-z])-([0-z])/g;
      var RETURN_FALSE = () => false;
      var sanitizeRange = (range) => range.replace(
        REGEX_REGEXP_RANGE,
        (match, from, to) => from.charCodeAt(0) <= to.charCodeAt(0) ? match : EMPTY
      );
      var cleanRangeBackSlash = (slashes) => {
        const { length } = slashes;
        return slashes.slice(0, length - length % 2);
      };
      var REPLACERS = [
        [
          // Remove BOM
          // TODO:
          // Other similar zero-width characters?
          /^\uFEFF/,
          () => EMPTY
        ],
        // > Trailing spaces are ignored unless they are quoted with backslash ("\")
        [
          // (a\ ) -> (a )
          // (a  ) -> (a)
          // (a ) -> (a)
          // (a \ ) -> (a  )
          /((?:\\\\)*?)(\\?\s+)$/,
          (_, m1, m2) => m1 + (m2.indexOf("\\") === 0 ? SPACE : EMPTY)
        ],
        // Replace (\ ) with ' '
        // (\ ) -> ' '
        // (\\ ) -> '\\ '
        // (\\\ ) -> '\\ '
        [
          /(\\+?)\s/g,
          (_, m1) => {
            const { length } = m1;
            return m1.slice(0, length - length % 2) + SPACE;
          }
        ],
        // Escape metacharacters
        // which is written down by users but means special for regular expressions.
        // > There are 12 characters with special meanings:
        // > - the backslash \,
        // > - the caret ^,
        // > - the dollar sign $,
        // > - the period or dot .,
        // > - the vertical bar or pipe symbol |,
        // > - the question mark ?,
        // > - the asterisk or star *,
        // > - the plus sign +,
        // > - the opening parenthesis (,
        // > - the closing parenthesis ),
        // > - and the opening square bracket [,
        // > - the opening curly brace {,
        // > These special characters are often called "metacharacters".
        [
          /[\\$.|*+(){^]/g,
          (match) => `\\${match}`
        ],
        [
          // > a question mark (?) matches a single character
          /(?!\\)\?/g,
          () => "[^/]"
        ],
        // leading slash
        [
          // > A leading slash matches the beginning of the pathname.
          // > For example, "/*.c" matches "cat-file.c" but not "mozilla-sha1/sha1.c".
          // A leading slash matches the beginning of the pathname
          /^\//,
          () => "^"
        ],
        // replace special metacharacter slash after the leading slash
        [
          /\//g,
          () => "\\/"
        ],
        [
          // > A leading "**" followed by a slash means match in all directories.
          // > For example, "**/foo" matches file or directory "foo" anywhere,
          // > the same as pattern "foo".
          // > "**/foo/bar" matches file or directory "bar" anywhere that is directly
          // >   under directory "foo".
          // Notice that the '*'s have been replaced as '\\*'
          /^\^*\\\*\\\*\\\//,
          // '**/foo' <-> 'foo'
          () => "^(?:.*\\/)?"
        ],
        // starting
        [
          // there will be no leading '/'
          //   (which has been replaced by section "leading slash")
          // If starts with '**', adding a '^' to the regular expression also works
          /^(?=[^^])/,
          function startingReplacer() {
            return !/\/(?!$)/.test(this) ? "(?:^|\\/)" : "^";
          }
        ],
        // two globstars
        [
          // Use lookahead assertions so that we could match more than one `'/**'`
          /\\\/\\\*\\\*(?=\\\/|$)/g,
          // Zero, one or several directories
          // should not use '*', or it will be replaced by the next replacer
          // Check if it is not the last `'/**'`
          (_, index, str) => index + 6 < str.length ? "(?:\\/[^\\/]+)*" : "\\/.+"
        ],
        // normal intermediate wildcards
        [
          // Never replace escaped '*'
          // ignore rule '\*' will match the path '*'
          // 'abc.*/' -> go
          // 'abc.*'  -> skip this rule,
          //    coz trailing single wildcard will be handed by [trailing wildcard]
          /(^|[^\\]+)(\\\*)+(?=.+)/g,
          // '*.js' matches '.js'
          // '*.js' doesn't match 'abc'
          (_, p1, p2) => {
            const unescaped = p2.replace(/\\\*/g, "[^\\/]*");
            return p1 + unescaped;
          }
        ],
        [
          // unescape, revert step 3 except for back slash
          // For example, if a user escape a '\\*',
          // after step 3, the result will be '\\\\\\*'
          /\\\\\\(?=[$.|*+(){^])/g,
          () => ESCAPE
        ],
        [
          // '\\\\' -> '\\'
          /\\\\/g,
          () => ESCAPE
        ],
        [
          // > The range notation, e.g. [a-zA-Z],
          // > can be used to match one of the characters in a range.
          // `\` is escaped by step 3
          /(\\)?\[([^\]/]*?)(\\*)($|\])/g,
          (match, leadEscape, range, endEscape, close) => leadEscape === ESCAPE ? `\\[${range}${cleanRangeBackSlash(endEscape)}${close}` : close === "]" ? endEscape.length % 2 === 0 ? `[${sanitizeRange(range)}${endEscape}]` : "[]" : "[]"
        ],
        // ending
        [
          // 'js' will not match 'js.'
          // 'ab' will not match 'abc'
          /(?:[^*])$/,
          // WTF!
          // https://git-scm.com/docs/gitignore
          // changes in [2.22.1](https://git-scm.com/docs/gitignore/2.22.1)
          // which re-fixes #24, #38
          // > If there is a separator at the end of the pattern then the pattern
          // > will only match directories, otherwise the pattern can match both
          // > files and directories.
          // 'js*' will not match 'a.js'
          // 'js/' will not match 'a.js'
          // 'js' will match 'a.js' and 'a.js/'
          (match) => /\/$/.test(match) ? `${match}$` : `${match}(?=$|\\/$)`
        ]
      ];
      var REGEX_REPLACE_TRAILING_WILDCARD = /(^|\\\/)?\\\*$/;
      var MODE_IGNORE = "regex";
      var MODE_CHECK_IGNORE = "checkRegex";
      var UNDERSCORE = "_";
      var TRAILING_WILD_CARD_REPLACERS = {
        [MODE_IGNORE](_, p1) {
          const prefix = p1 ? `${p1}[^/]+` : "[^/]*";
          return `${prefix}(?=$|\\/$)`;
        },
        [MODE_CHECK_IGNORE](_, p1) {
          const prefix = p1 ? `${p1}[^/]*` : "[^/]*";
          return `${prefix}(?=$|\\/$)`;
        }
      };
      var makeRegexPrefix = (pattern) => REPLACERS.reduce(
        (prev, [matcher, replacer]) => prev.replace(matcher, replacer.bind(pattern)),
        pattern
      );
      var isString = (subject) => typeof subject === "string";
      var checkPattern = (pattern) => pattern && isString(pattern) && !REGEX_TEST_BLANK_LINE.test(pattern) && !REGEX_INVALID_TRAILING_BACKSLASH.test(pattern) && pattern.indexOf("#") !== 0;
      var splitPattern = (pattern) => pattern.split(REGEX_SPLITALL_CRLF).filter(Boolean);
      var IgnoreRule = class {
        constructor(pattern, mark, body, ignoreCase, negative, prefix) {
          this.pattern = pattern;
          this.mark = mark;
          this.negative = negative;
          define(this, "body", body);
          define(this, "ignoreCase", ignoreCase);
          define(this, "regexPrefix", prefix);
        }
        get regex() {
          const key = UNDERSCORE + MODE_IGNORE;
          if (this[key]) {
            return this[key];
          }
          return this._make(MODE_IGNORE, key);
        }
        get checkRegex() {
          const key = UNDERSCORE + MODE_CHECK_IGNORE;
          if (this[key]) {
            return this[key];
          }
          return this._make(MODE_CHECK_IGNORE, key);
        }
        _make(mode, key) {
          const str = this.regexPrefix.replace(
            REGEX_REPLACE_TRAILING_WILDCARD,
            // It does not need to bind pattern
            TRAILING_WILD_CARD_REPLACERS[mode]
          );
          const regex = this.ignoreCase ? new RegExp(str, "i") : new RegExp(str);
          return define(this, key, regex);
        }
      };
      var createRule = ({
        pattern,
        mark
      }, ignoreCase) => {
        let negative = false;
        let body = pattern;
        if (body.indexOf("!") === 0) {
          negative = true;
          body = body.substr(1);
        }
        body = body.replace(REGEX_REPLACE_LEADING_EXCAPED_EXCLAMATION, "!").replace(REGEX_REPLACE_LEADING_EXCAPED_HASH, "#");
        const regexPrefix = makeRegexPrefix(body);
        return new IgnoreRule(
          pattern,
          mark,
          body,
          ignoreCase,
          negative,
          regexPrefix
        );
      };
      var RuleManager = class {
        constructor(ignoreCase) {
          this._ignoreCase = ignoreCase;
          this._rules = [];
        }
        _add(pattern) {
          if (pattern && pattern[KEY_IGNORE]) {
            this._rules = this._rules.concat(pattern._rules._rules);
            this._added = true;
            return;
          }
          if (isString(pattern)) {
            pattern = {
              pattern
            };
          }
          if (checkPattern(pattern.pattern)) {
            const rule = createRule(pattern, this._ignoreCase);
            this._added = true;
            this._rules.push(rule);
          }
        }
        // @param {Array<string> | string | Ignore} pattern
        add(pattern) {
          this._added = false;
          makeArray(
            isString(pattern) ? splitPattern(pattern) : pattern
          ).forEach(this._add, this);
          return this._added;
        }
        // Test one single path without recursively checking parent directories
        //
        // - checkUnignored `boolean` whether should check if the path is unignored,
        //   setting `checkUnignored` to `false` could reduce additional
        //   path matching.
        // - check `string` either `MODE_IGNORE` or `MODE_CHECK_IGNORE`
        // @returns {TestResult} true if a file is ignored
        test(path, checkUnignored, mode) {
          let ignored = false;
          let unignored = false;
          let matchedRule;
          this._rules.forEach((rule) => {
            const { negative } = rule;
            if (unignored === negative && ignored !== unignored || negative && !ignored && !unignored && !checkUnignored) {
              return;
            }
            const matched = rule[mode].test(path);
            if (!matched) {
              return;
            }
            ignored = !negative;
            unignored = negative;
            matchedRule = negative ? UNDEFINED : rule;
          });
          const ret = {
            ignored,
            unignored
          };
          if (matchedRule) {
            ret.rule = matchedRule;
          }
          return ret;
        }
      };
      var throwError = (message, Ctor) => {
        throw new Ctor(message);
      };
      var checkPath = (path, originalPath, doThrow) => {
        if (!isString(path)) {
          return doThrow(
            `path must be a string, but got \`${originalPath}\``,
            TypeError
          );
        }
        if (!path) {
          return doThrow(`path must not be empty`, TypeError);
        }
        if (checkPath.isNotRelative(path)) {
          const r = "`path.relative()`d";
          return doThrow(
            `path should be a ${r} string, but got "${originalPath}"`,
            RangeError
          );
        }
        return true;
      };
      var isNotRelative = (path) => REGEX_TEST_INVALID_PATH.test(path);
      checkPath.isNotRelative = isNotRelative;
      checkPath.convert = (p) => p;
      var Ignore = class {
        constructor({
          ignorecase = true,
          ignoreCase = ignorecase,
          allowRelativePaths = false
        } = {}) {
          define(this, KEY_IGNORE, true);
          this._rules = new RuleManager(ignoreCase);
          this._strictPathCheck = !allowRelativePaths;
          this._initCache();
        }
        _initCache() {
          this._ignoreCache = /* @__PURE__ */ Object.create(null);
          this._testCache = /* @__PURE__ */ Object.create(null);
        }
        add(pattern) {
          if (this._rules.add(pattern)) {
            this._initCache();
          }
          return this;
        }
        // legacy
        addPattern(pattern) {
          return this.add(pattern);
        }
        // @returns {TestResult}
        _test(originalPath, cache, checkUnignored, slices) {
          const path = originalPath && checkPath.convert(originalPath);
          checkPath(
            path,
            originalPath,
            this._strictPathCheck ? throwError : RETURN_FALSE
          );
          return this._t(path, cache, checkUnignored, slices);
        }
        checkIgnore(path) {
          if (!REGEX_TEST_TRAILING_SLASH.test(path)) {
            return this.test(path);
          }
          const slices = path.split(SLASH).filter(Boolean);
          slices.pop();
          if (slices.length) {
            const parent = this._t(
              slices.join(SLASH) + SLASH,
              this._testCache,
              true,
              slices
            );
            if (parent.ignored) {
              return parent;
            }
          }
          return this._rules.test(path, false, MODE_CHECK_IGNORE);
        }
        _t(path, cache, checkUnignored, slices) {
          if (path in cache) {
            return cache[path];
          }
          if (!slices) {
            slices = path.split(SLASH).filter(Boolean);
          }
          slices.pop();
          if (!slices.length) {
            return cache[path] = this._rules.test(path, checkUnignored, MODE_IGNORE);
          }
          const parent = this._t(
            slices.join(SLASH) + SLASH,
            cache,
            checkUnignored,
            slices
          );
          return cache[path] = parent.ignored ? parent : this._rules.test(path, checkUnignored, MODE_IGNORE);
        }
        ignores(path) {
          return this._test(path, this._ignoreCache, false).ignored;
        }
        createFilter() {
          return (path) => !this.ignores(path);
        }
        filter(paths) {
          return makeArray(paths).filter(this.createFilter());
        }
        // @returns {TestResult}
        test(path) {
          return this._test(path, this._testCache, true);
        }
      };
      var factory = (options) => new Ignore(options);
      var isPathValid = (path) => checkPath(path && checkPath.convert(path), path, RETURN_FALSE);
      var setupWindows = () => {
        const makePosix = (str) => /^\\\\\?\\/.test(str) || /["<>|\u0000-\u001F]+/u.test(str) ? str : str.replace(/\\/g, "/");
        checkPath.convert = makePosix;
        const REGEX_TEST_WINDOWS_PATH_ABSOLUTE = /^[a-z]:\//i;
        checkPath.isNotRelative = (path) => REGEX_TEST_WINDOWS_PATH_ABSOLUTE.test(path) || isNotRelative(path);
      };
      if (
        // Detect `process` so that it can run in browsers.
        typeof process !== "undefined" && process.platform === "win32"
      ) {
        setupWindows();
      }
      module.exports = factory;
      factory.default = factory;
      module.exports.isPathValid = isPathValid;
      define(module.exports, Symbol.for("setupWindows"), setupWindows);
    }
  });

  // tmp/index-overlap-20261117/entry.ts
  var entry_exports = {};
  __export(entry_exports, {
    RoutedProjectIndexRepository: () => RoutedProjectIndexRepository,
    createIsolatedProjectId: () => createIsolatedProjectId,
    indexProject: () => indexProject
  });

  // apps/web/src/features/project-index/projectFileScope.ts
  var import_ignore = __toESM(require_ignore(), 1);

  // apps/web/src/features/project-index/dependencyCacheDirectories.ts
  var DEPENDENCY_CACHE_DIRECTORY_NAMES = [
    ".m2",
    ".npm",
    ".npm-cache",
    "_cacache",
    ".pnpm-store"
  ];

  // apps/web/src/features/project-index/projectFileScope.ts
  var PROJECT_GENERATED_DIRECTORIES = /* @__PURE__ */ new Set([
    ...DEPENDENCY_CACHE_DIRECTORY_NAMES,
    ".git",
    ".hg",
    ".svn",
    ".idea",
    ".vscode",
    "node_modules",
    "vendor",
    "target",
    "build",
    "dist",
    "out",
    "coverage",
    ".next",
    ".nuxt",
    ".output",
    ".cache",
    ".gradle",
    ".mvn",
    "__pycache__",
    ".pytest_cache",
    ".mypy_cache",
    ".ruff_cache",
    ".tox",
    ".venv",
    "venv",
    "env",
    "logs",
    "log",
    ".codegraph",
    "playwright-report",
    "test-results",
    ".turbo",
    ".parcel-cache"
  ]);
  var MAX_PROJECT_IGNORE_BYTES = 128 * 1024;

  // apps/web/src/workers/fileReaderCore.ts
  var MAX_DOCUMENT_FILE_BYTES = 50 * 1024 * 1024;
  var isSafeRelativeFilePath = (path) => {
    const normalized = path.trim().replace(/\\/g, "/");
    return normalized.length > 0 && !normalized.startsWith("/") && !/[\u0000-\u001f:]/.test(normalized) && !normalized.split("/").includes("..");
  };
  var SENSITIVE_FILE_NAMES = /* @__PURE__ */ new Set([
    ".env",
    ".env.local",
    ".env.development",
    ".env.production",
    "id_rsa",
    "id_ed25519",
    "credentials",
    "credentials.json"
  ]);
  var isSensitiveFile = (relativePath) => {
    const fileName = relativePath.slice(relativePath.lastIndexOf("/") + 1).toLowerCase();
    return SENSITIVE_FILE_NAMES.has(fileName) || fileName.startsWith(".env.") && fileName !== ".env.example" || fileName.endsWith(".pem") || fileName.endsWith(".key") || fileName.endsWith(".p12") || fileName.endsWith(".jks") || /(?:^|\/)(?:application[-.](?:prod|production)|config\/(?:prod|production))\.(?:yml|yaml|properties|json|toml)$/i.test(relativePath);
  };

  // apps/web/src/features/project-index/projectPathReferences.ts
  var referencedPathVariants = (reference, rootName) => {
    const path = normalizeReferencedPath(reference);
    if (!path) return [];
    const root = normalizeReferencedPath(rootName ?? "");
    return root && path.startsWith(`${root}/`) ? [path, path.slice(root.length + 1)] : [path];
  };
  var normalizeReferencedPath = (value) => {
    const path = value.trim().replace(/\\/g, "/").replace(/^\.\//, "").replace(/\/+$/, "");
    return !path || path.startsWith("/") || /^[A-Za-z]:/.test(path) || path.split("/").some((segment) => segment === "..") ? "" : path.replace(/\/{2,}/g, "/");
  };

  // apps/web/src/features/project-index/projectIndexFailure.ts
  var indexFailureMessage = (error) => {
    const name = error instanceof Error ? error.name : "";
    if (name === "QuotaExceededError") {
      return "\u6D4F\u89C8\u5668\u5B58\u50A8\u7A7A\u95F4\u4E0D\u8DB3\uFF0C\u7D22\u5F15\u5DF2\u4E2D\u65AD\u3002\u5DF2\u63D0\u4EA4\u7684\u6279\u6B21\u4F1A\u4FDD\u7559\uFF1B\u91CA\u653E\u672C\u7AD9\u4E0D\u518D\u9700\u8981\u7684\u7D22\u5F15\u540E\uFF0C\u53EF\u70B9\u51FB\u201C\u7EE7\u7EED\u7D22\u5F15\u201D\u91CD\u8BD5\u3002";
    }
    if (name === "NotAllowedError") return "\u9879\u76EE\u76EE\u5F55\u8BFB\u53D6\u6743\u9650\u5DF2\u5931\u6548\u3002\u70B9\u51FB\u201C\u7EE7\u7EED\u7D22\u5F15\u201D\u91CD\u65B0\u6388\u6743\uFF0C\u6216\u91CD\u65B0\u9009\u62E9\u76EE\u5F55\u3002";
    if (name === "NotFoundError") return "\u9879\u76EE\u76EE\u5F55\u6216\u6587\u4EF6\u5DF2\u79FB\u52A8\u3001\u5220\u9664\u3002\u8BF7\u6062\u590D\u76EE\u5F55\u540E\u7EE7\u7EED\u7D22\u5F15\uFF0C\u6216\u91CD\u65B0\u9009\u62E9\u76EE\u5F55\u3002";
    if (name === "SecurityError") return "\u6D4F\u89C8\u5668\u7981\u6B62\u8BBF\u95EE\u672C\u5730\u5B58\u50A8\u6216\u76EE\u5F55\u3002\u8BF7\u68C0\u67E5\u672C\u7AD9\u6743\u9650\u540E\u7EE7\u7EED\u7D22\u5F15\u3002";
    if (name === "AbortError") return "\u672C\u5730\u7D22\u5F15\u4E8B\u52A1\u4E2D\u65AD\uFF0C\u672A\u63D0\u4EA4\u7684\u6279\u6B21\u672A\u4FDD\u5B58\u3002\u53EF\u70B9\u51FB\u201C\u7EE7\u7EED\u7D22\u5F15\u201D\u6821\u9A8C\u5E76\u8865\u5EFA\u3002";
    const detail = error instanceof Error ? error.message.trim() : "";
    return detail || "\u672C\u5730\u9879\u76EE\u7D22\u5F15\u610F\u5916\u4E2D\u65AD\u3002\u5DF2\u63D0\u4EA4\u7684\u6279\u6B21\u4F1A\u4FDD\u7559\uFF0C\u53EF\u7EE7\u7EED\u7D22\u5F15\uFF1B\u82E5\u4ECD\u5931\u8D25\uFF0C\u8BF7\u91CD\u65B0\u9009\u62E9\u76EE\u5F55\u3002";
  };
  var ProjectIndexInterruptedError = class extends Error {
    cause;
    summary;
    stage;
    constructor(error, checkpoint, stage = "INDEXING") {
      super(indexFailureMessage(error));
      this.cause = error;
      this.name = "ProjectIndexInterruptedError";
      this.stage = stage;
      this.summary = checkpoint ? { ...checkpoint, status: "FAILED", errorMessage: this.message } : void 0;
    }
  };

  // apps/web/src/features/project-index/projectIndexer.ts
  var INDEX_CHUNK_CHARACTERS = 6e3;
  var MAX_INDEXABLE_FILE_BYTES = 50 * 1024 * 1024;
  var INDEX_FORMAT_VERSION = 4;
  var DEFAULT_INDEX_LIMITS = {
    maxScanFiles: 1e6,
    maxIndexBytes: 2 * 1024 * 1024 * 1024,
    maxIndexableFileBytes: MAX_INDEXABLE_FILE_BYTES
  };
  var FILE_BATCH_SIZE = 200;
  var CHUNK_BATCH_SIZE = 300;
  var MAX_PENDING_TERM_REFERENCES = 64e3;
  var MAX_PENDING_BATCH_BYTES = 4 * 1024 * 1024;
  var TARGET_WRITE_MS = 250;
  var MIN_BATCH_SCALE = 0.25;
  var TEXT_SAMPLE_BYTES = 8192;
  var SMALL_FILE_BYTES = 64 * 1024;
  var FILE_READ_CONCURRENCY = 6;
  var MAX_RECORDED_CHANGED_PATHS = 500;
  var PROGRESS_FILE_INTERVAL = 32;
  var PROGRESS_TIME_INTERVAL_MS = 150;
  var PROGRESS_BYTE_INTERVAL = 2 * 1024 * 1024;
  var SENSITIVE_NPMRC_PATTERN = /^\s*(?:_auth|_authToken|password|username)\s*=|:_authToken\s*=/im;
  var IGNORED_FILE_SUFFIXES = [
    ".log",
    ".tmp",
    ".temp",
    ".cache",
    ".pid",
    ".seed",
    ".trace",
    ".dump",
    ".class",
    ".jar",
    ".war",
    ".ear",
    ".dll",
    ".exe",
    ".so",
    ".dylib",
    ".o",
    ".obj",
    ".png",
    ".jpg",
    ".jpeg",
    ".gif",
    ".bmp",
    ".webp",
    ".ico",
    ".pdf",
    ".zip",
    ".rar",
    ".7z",
    ".gz",
    ".tar",
    ".mp3",
    ".wav",
    ".mp4",
    ".mov",
    ".avi",
    ".woff",
    ".woff2",
    ".ttf",
    ".otf",
    ".map",
    ".doc",
    ".docx",
    ".xls",
    ".xlsx",
    ".ppt",
    ".pptx"
  ];
  var SENSITIVE_FILE_NAMES2 = /* @__PURE__ */ new Set([
    ".env",
    ".env.local",
    ".env.production",
    ".env.development",
    "id_rsa",
    "id_ed25519",
    "credentials",
    "credentials.json"
  ]);
  var PRIORITY_FILE_NAMES = /* @__PURE__ */ new Set([
    "pom.xml",
    "build.gradle",
    "build.gradle.kts",
    "settings.gradle",
    "settings.gradle.kts",
    "package.json",
    "package-lock.json",
    "pnpm-lock.yaml",
    "yarn.lock",
    "requirements.txt",
    "pyproject.toml",
    "pipfile",
    "poetry.lock",
    "go.mod",
    "go.sum",
    "cargo.toml",
    "cargo.lock",
    "composer.json",
    "gemfile",
    "dockerfile",
    "docker-compose.yml",
    "docker-compose.yaml",
    "compose.yml",
    "compose.yaml",
    "makefile",
    "jenkinsfile",
    "readme.md",
    "tsconfig.json",
    "vite.config.ts",
    ".gitignore",
    ".editorconfig",
    ".npmrc",
    ".prettierrc",
    ".eslintrc",
    ".env.example"
  ]);
  var LANGUAGE_BY_EXTENSION = {
    adoc: "asciidoc",
    astro: "astro",
    bash: "shell",
    bat: "batch",
    c: "c",
    cc: "cpp",
    cfg: "ini",
    cjs: "javascript",
    clj: "clojure",
    cljs: "clojure",
    conf: "config",
    cpp: "cpp",
    cs: "csharp",
    cshtml: "razor",
    csproj: "xml",
    css: "css",
    cts: "typescript",
    dart: "dart",
    ejs: "html",
    erb: "html",
    ex: "elixir",
    exs: "elixir",
    fish: "shell",
    fs: "fsharp",
    fsproj: "xml",
    fsx: "fsharp",
    ftl: "freemarker",
    go: "go",
    gql: "graphql",
    gradle: "gradle",
    graphql: "graphql",
    groovy: "groovy",
    h: "c",
    hbs: "handlebars",
    hcl: "hcl",
    hh: "cpp",
    hpp: "cpp",
    hs: "haskell",
    html: "html",
    hxx: "cpp",
    ini: "ini",
    ipynb: "json",
    java: "java",
    js: "javascript",
    json: "json",
    jsonc: "json",
    jsp: "jsp",
    jsx: "javascript",
    kt: "kotlin",
    kts: "kotlin",
    less: "less",
    lua: "lua",
    md: "markdown",
    mdx: "markdown",
    mjs: "javascript",
    ml: "ocaml",
    mli: "ocaml",
    mts: "typescript",
    nix: "nix",
    php: "php",
    phtml: "php",
    pl: "perl",
    pm: "perl",
    prisma: "prisma",
    properties: "properties",
    proto: "protobuf",
    ps1: "powershell",
    psm1: "powershell",
    pug: "pug",
    py: "python",
    pyi: "python",
    pyx: "python",
    r: "r",
    rb: "ruby",
    razor: "razor",
    rmd: "rmarkdown",
    rs: "rust",
    rst: "restructuredtext",
    sass: "sass",
    sc: "scala",
    scala: "scala",
    scss: "scss",
    sh: "shell",
    sln: "text",
    sql: "sql",
    svelte: "svelte",
    svg: "xml",
    swift: "swift",
    tag: "jsp",
    tex: "latex",
    tf: "hcl",
    thrift: "thrift",
    toml: "toml",
    ts: "typescript",
    tsv: "tsv",
    tsx: "typescript",
    txt: "text",
    vb: "vb",
    vbproj: "xml",
    vbs: "vb",
    vue: "vue",
    xaml: "xml",
    xhtml: "html",
    xml: "xml",
    yaml: "yaml",
    yml: "yaml",
    zsh: "shell"
  };
  var LANGUAGE_BY_FILE_NAME = {
    dockerfile: "dockerfile",
    gemfile: "ruby",
    jenkinsfile: "groovy",
    makefile: "makefile",
    pipfile: "toml",
    procfile: "text",
    rakefile: "ruby",
    ".gitignore": "gitignore",
    ".editorconfig": "editorconfig",
    ".npmrc": "ini",
    ".prettierrc": "json",
    ".eslintrc": "json"
  };
  var indexProject = async (options) => {
    const limits = options.limits ?? DEFAULT_INDEX_LIMITS;
    const mode = options.mode ?? "FULL";
    let pauseRequested = false;
    const shouldPause = () => {
      pauseRequested = pauseRequested || Boolean(options.shouldPause?.());
      return pauseRequested;
    };
    const scanId = createScanId();
    const summary = {
      id: options.projectId,
      rootName: options.rootName,
      status: "INDEXING",
      discoveredFiles: 0,
      eligibleFiles: 0,
      indexedFiles: 0,
      ignoredFiles: 0,
      ignoredDirectories: 0,
      scopeExcludedFiles: options.scopeExcludedFiles ?? 0,
      excludedDirectorySamples: options.excludedDirectorySamples ?? [],
      metadataOnlyFiles: 0,
      failedFiles: 0,
      addedFiles: 0,
      updatedFiles: 0,
      unchangedFiles: 0,
      removedFiles: 0,
      changedPaths: [],
      chunkCount: 0,
      indexedCharacters: 0,
      estimatedIndexBytes: 0,
      scanLimitReached: false,
      storageLimitReached: false,
      retention: options.retention ?? "SESSION",
      sessionId: options.sessionId ?? "",
      expiresAt: options.expiresAt ?? "",
      scanId,
      lastCheckpointPath: "",
      startedAt: (/* @__PURE__ */ new Date()).toISOString()
    };
    let committedSummary = structuredClone(summary);
    const pendingFiles = [];
    const pendingChunks = [];
    const replacedPaths = /* @__PURE__ */ new Set();
    let sourceBatch = [];
    let pendingBatchBytes = 0;
    let pendingTermReferences = 0;
    let lastReportedFiles = -1;
    let lastReportedAt = 0;
    let lastReportedFileBytes = 0;
    let lastReportedPath = "";
    const indexingStartedAt = performance.now();
    let writeMs = 0;
    let batchScale = 1;
    const flush = async (checkpoint) => {
      if (!checkpoint && pendingFiles.length === 0 && pendingChunks.length === 0 && replacedPaths.size === 0) {
        return;
      }
      const hasPayload = pendingFiles.length > 0 || pendingChunks.length > 0 || replacedPaths.size > 0;
      const writeStartedAt = performance.now();
      const snapshot = checkpoint ? structuredClone(checkpoint) : void 0;
      try {
        await options.repository.writeBatch(
          pendingFiles.splice(0),
          pendingChunks.splice(0),
          Array.from(replacedPaths),
          snapshot
        );
        if (snapshot) committedSummary = snapshot;
      } catch (error) {
        throw new ProjectIndexInterruptedError(error, committedSummary, "WRITING");
      }
      const elapsed = performance.now() - writeStartedAt;
      writeMs += elapsed;
      if (hasPayload) {
        if (elapsed > TARGET_WRITE_MS * 2) batchScale = Math.max(MIN_BATCH_SCALE, batchScale / 2);
        else if (elapsed < TARGET_WRITE_MS / 2) batchScale = Math.min(1, batchScale + 0.125);
      }
      replacedPaths.clear();
      pendingBatchBytes = 0;
      pendingTermReferences = 0;
    };
    const flushIfNeeded = async () => {
      if (pendingFiles.length >= Math.floor(FILE_BATCH_SIZE * batchScale) || pendingChunks.length >= Math.floor(CHUNK_BATCH_SIZE * batchScale) || pendingTermReferences >= Math.floor(MAX_PENDING_TERM_REFERENCES * batchScale) || pendingBatchBytes >= MAX_PENDING_BATCH_BYTES * batchScale) {
        await flush(summary);
      }
    };
    const report = (phase, currentPath, currentFileBytesRead, currentFileSize) => {
      const now = performance.now();
      const progressUnits = summary.discoveredFiles + summary.ignoredDirectories;
      const visible = phase === "COMPLETED" || phase === "PAUSED" ? summary : committedSummary;
      const processedFiles = visible.indexedFiles + visible.metadataOnlyFiles + visible.ignoredFiles + visible.failedFiles;
      const samePath = currentPath === lastReportedPath;
      const advancedBytes = samePath && currentFileBytesRead !== void 0 ? currentFileBytesRead - lastReportedFileBytes : 0;
      if (phase !== "COMPLETED" && phase !== "PAUSED" && lastReportedFiles >= 0 && progressUnits - lastReportedFiles < PROGRESS_FILE_INTERVAL && now - lastReportedAt < PROGRESS_TIME_INTERVAL_MS && advancedBytes < PROGRESS_BYTE_INTERVAL) {
        return;
      }
      lastReportedFiles = progressUnits;
      lastReportedAt = now;
      lastReportedPath = currentPath;
      lastReportedFileBytes = currentFileBytesRead ?? 0;
      const processingMs = Math.max(0, now - indexingStartedAt);
      const elapsedMs = processingMs + (options.scanDurationMs ?? 0);
      const filesPerSecond = processingMs > 0 ? processedFiles * 1e3 / processingMs : 0;
      const totalFiles = phase === "COMPLETED" && !summary.scanLimitReached ? summary.discoveredFiles : options.totalFiles === void 0 ? void 0 : Math.max(options.totalFiles, summary.discoveredFiles);
      const percent = phase === "COMPLETED" && !summary.scanLimitReached ? 100 : totalFiles !== void 0 && totalFiles > 0 ? Math.min(99.9, Math.floor(processedFiles / totalFiles * 1e3) / 10) : 0;
      const remainingFiles = totalFiles === void 0 ? void 0 : Math.max(0, totalFiles - processedFiles);
      const etaMs = remainingFiles !== void 0 && filesPerSecond > 0 ? remainingFiles / filesPerSecond * 1e3 : void 0;
      options.onProgress?.({
        phase,
        currentPath,
        processedFiles,
        totalFiles,
        percent,
        filesPerSecond,
        elapsedMs,
        etaMs,
        currentFileBytesRead,
        currentFileSize,
        discoveredFiles: summary.discoveredFiles,
        eligibleFiles: visible.eligibleFiles,
        indexedFiles: visible.indexedFiles,
        ignoredFiles: visible.ignoredFiles,
        ignoredDirectories: summary.ignoredDirectories,
        metadataOnlyFiles: visible.metadataOnlyFiles,
        failedFiles: visible.failedFiles,
        addedFiles: visible.addedFiles,
        updatedFiles: visible.updatedFiles,
        unchangedFiles: visible.unchangedFiles,
        chunkCount: visible.chunkCount,
        indexedCharacters: visible.indexedCharacters
      });
    };
    const pauseIfRequested = async () => {
      if (!shouldPause()) {
        return false;
      }
      await flush(summary);
      summary.status = "PAUSED";
      summary.pausedAt = (/* @__PURE__ */ new Date()).toISOString();
      await options.repository.pauseProject(summary);
      report("PAUSED", summary.lastCheckpointPath);
      return true;
    };
    const processSourceBatch = async () => {
      if (sourceBatch.length === 0) {
        return pauseIfRequested();
      }
      const batch = sourceBatch;
      sourceBatch = [];
      const existingFiles = mode === "FULL" ? [] : await options.repository.findFiles(
        options.projectId,
        batch.map((entry) => entry.path)
      );
      const existingByPath = new Map(existingFiles.map((file) => [file.path, file]));
      for await (const prepared of prepareSourceFiles(batch, existingByPath, shouldPause)) {
        const { entry, source } = prepared;
        summary.discoveredFiles += 1;
        summary.lastCheckpointPath = entry.path;
        if (!isSafeRelativeFilePath(entry.path) || isSensitiveFile(entry.path.replace(/\\/g, "/"))) {
          summary.ignoredFiles += 1;
          report("INDEXING", entry.path);
          continue;
        }
        const fingerprint = fingerprintOf(entry.file);
        const existing = existingByPath.get(entry.path);
        if (canReuseIndexedFile(existing, fingerprint)) {
          const restoredCharacters = existing.indexedCharacters ?? 0;
          const restoredBytes = existing.estimatedIndexBytes ?? estimateFileMetadataBytes(entry.path);
          pendingFiles.push({
            ...existing,
            symbols: existing.symbols ?? [],
            imports: existing.imports ?? [],
            lastSeenScanId: scanId
          });
          pendingBatchBytes += estimateFileMetadataBytes(entry.path);
          summary.eligibleFiles += 1;
          summary.unchangedFiles += 1;
          summary.chunkCount += existing.chunkCount;
          summary.indexedCharacters += restoredCharacters;
          summary.estimatedIndexBytes += restoredBytes;
          if (existing.metadataOnly || existing.chunkCount === 0) {
            summary.metadataOnlyFiles += 1;
          } else {
            summary.indexedFiles += 1;
          }
          await flushIfNeeded();
          report("INDEXING", entry.path);
          continue;
        }
        let classification;
        try {
          if (prepared.error) throw prepared.error;
          classification = await classifyFile(entry.path, source, limits.maxIndexableFileBytes, prepared.bytes);
        } catch {
          summary.failedFiles += 1;
          replacedPaths.add(entry.path);
          pendingFiles.push(createFileRecord(
            options.projectId,
            entry,
            { action: "METADATA_ONLY", language: "text", priority: 100 },
            0,
            true,
            `incomplete:${fingerprint}`,
            scanId,
            0,
            estimateFileMetadataBytes(entry.path),
            [],
            []
          ));
          await flushIfNeeded();
          report("INDEXING", entry.path);
          continue;
        }
        if (classification.action === "IGNORE") {
          summary.ignoredFiles += 1;
          report("INDEXING", entry.path);
          continue;
        }
        summary.eligibleFiles += 1;
        if (existing) {
          summary.updatedFiles += 1;
          replacedPaths.add(entry.path);
        } else {
          summary.addedFiles += 1;
        }
        if (mode !== "FULL" && summary.changedPaths.length < MAX_RECORDED_CHANGED_PATHS) {
          summary.changedPaths.push(entry.path);
        }
        const metadataBytes = estimateFileMetadataBytes(entry.path);
        if (classification.action === "METADATA_ONLY" || summary.storageLimitReached) {
          summary.metadataOnlyFiles += 1;
          pendingFiles.push(createFileRecord(
            options.projectId,
            entry,
            classification,
            0,
            true,
            summary.storageLimitReached ? `incomplete:${fingerprint}` : fingerprint,
            scanId,
            0,
            metadataBytes,
            [],
            []
          ));
          pendingBatchBytes += metadataBytes;
          summary.estimatedIndexBytes += metadataBytes;
          await flushIfNeeded();
          report("INDEXING", entry.path);
          continue;
        }
        let persistenceFailed = false;
        try {
          let chunkCount = 0;
          let fileCharacters = 0;
          let fileEstimatedBytes = metadataBytes;
          let limitedByStorage = false;
          const fileSymbols = /* @__PURE__ */ new Set();
          const fileImports = /* @__PURE__ */ new Set();
          for await (const content of streamTextChunks(
            source,
            classification.language,
            (bytesRead) => report("INDEXING", entry.path, bytesRead, entry.file.size),
            prepared.bytes
          )) {
            const codeMetadata = extractCodeMetadata(content, classification.language);
            codeMetadata.symbols.forEach((symbol) => fileSymbols.add(symbol));
            codeMetadata.imports.forEach((reference) => fileImports.add(reference));
            const searchTerms = buildSearchTerms(options.projectId, entry.path, content);
            const estimatedChunkBytes = estimateChunkBytes(
              content,
              searchTerms,
              codeMetadata.symbols,
              codeMetadata.imports
            );
            if (summary.estimatedIndexBytes + fileEstimatedBytes + estimatedChunkBytes > limits.maxIndexBytes) {
              summary.storageLimitReached = true;
              limitedByStorage = true;
              break;
            }
            pendingChunks.push({
              id: `${options.projectId}\0${entry.path}\0${chunkCount}`,
              projectId: options.projectId,
              path: entry.path,
              language: classification.language,
              chunkIndex: chunkCount,
              content,
              priority: classification.priority,
              searchTerms,
              symbols: codeMetadata.symbols,
              imports: codeMetadata.imports
            });
            pendingBatchBytes += estimatedChunkBytes;
            pendingTermReferences += searchTerms.length;
            chunkCount += 1;
            fileCharacters += content.length;
            fileEstimatedBytes += estimatedChunkBytes;
            if (pendingChunks.length >= Math.floor(CHUNK_BATCH_SIZE * batchScale) || pendingTermReferences >= Math.floor(MAX_PENDING_TERM_REFERENCES * batchScale) || pendingBatchBytes >= MAX_PENDING_BATCH_BYTES * batchScale) {
              pendingFiles.push(createFileRecord(
                options.projectId,
                entry,
                classification,
                chunkCount,
                true,
                `incomplete:${fingerprint}`,
                scanId,
                fileCharacters,
                fileEstimatedBytes,
                Array.from(fileSymbols),
                Array.from(fileImports)
              ));
              try {
                await flush();
              } catch (error) {
                persistenceFailed = true;
                throw error;
              }
            }
          }
          pendingFiles.push(createFileRecord(
            options.projectId,
            entry,
            classification,
            chunkCount,
            limitedByStorage || chunkCount === 0,
            limitedByStorage ? `incomplete:${fingerprint}` : fingerprint,
            scanId,
            fileCharacters,
            fileEstimatedBytes,
            Array.from(fileSymbols),
            Array.from(fileImports)
          ));
          pendingBatchBytes += metadataBytes;
          summary.chunkCount += chunkCount;
          summary.indexedCharacters += fileCharacters;
          summary.estimatedIndexBytes += fileEstimatedBytes;
          if (chunkCount > 0 && !limitedByStorage) {
            summary.indexedFiles += 1;
          } else {
            summary.metadataOnlyFiles += 1;
          }
        } catch (error) {
          if (persistenceFailed) throw error;
          for (let index = pendingChunks.length - 1; index >= 0; index -= 1) {
            if (pendingChunks[index]?.path === entry.path) pendingChunks.splice(index, 1);
          }
          pendingFiles.push(createFileRecord(
            options.projectId,
            entry,
            classification,
            0,
            true,
            `incomplete:${fingerprint}`,
            scanId,
            0,
            metadataBytes,
            [],
            []
          ));
          replacedPaths.add(entry.path);
          summary.failedFiles += 1;
          await flush();
        }
        await flushIfNeeded();
        report("INDEXING", entry.path);
      }
      await flush(summary);
      report("INDEXING", summary.lastCheckpointPath);
      return pauseIfRequested();
    };
    await options.repository.beginProject(summary);
    report(options.totalFiles === void 0 ? "SCANNING" : "INDEXING", "");
    try {
      for await (const entry of options.entries) {
        if (entry.kind === "ignored-directory") {
          summary.ignoredDirectories += 1;
          report(options.totalFiles === void 0 ? "SCANNING" : "INDEXING", entry.path);
          if (summary.ignoredDirectories % FILE_BATCH_SIZE === 0 && await pauseIfRequested()) {
            return summary;
          }
          continue;
        }
        if (summary.discoveredFiles + sourceBatch.length >= limits.maxScanFiles) {
          summary.scanLimitReached = true;
          break;
        }
        sourceBatch.push(entry);
        if (sourceBatch.length >= FILE_BATCH_SIZE && await processSourceBatch()) {
          return summary;
        }
      }
      if (await processSourceBatch()) {
        return summary;
      }
      if (!summary.scanLimitReached) {
        summary.removedFiles = await options.repository.removeUnseenFiles(options.projectId, scanId);
      }
      summary.status = "READY";
      summary.completedAt = (/* @__PURE__ */ new Date()).toISOString();
      const processingMs = performance.now() - indexingStartedAt;
      summary.timings = {
        scanMs: options.scanDurationMs ?? 0,
        processingMs: Math.max(0, processingMs - writeMs),
        writeMs,
        totalMs: processingMs + (options.scanDurationMs ?? 0)
      };
      await options.repository.completeProject(summary);
      report("COMPLETED", "");
      return summary;
    } catch (error) {
      const failure = error instanceof ProjectIndexInterruptedError ? error : new ProjectIndexInterruptedError(error, committedSummary);
      try {
        await options.repository.failProject(options.projectId, failure.message);
      } catch {
      }
      throw failure;
    }
  };
  var prepareSourceFiles = async function* (entries, existing, shouldStop) {
    const prepare = (offset) => Promise.all(entries.slice(offset, offset + FILE_READ_CONCURRENCY).map(async (entry) => {
      const path = entry.path.replace(/\\/g, "/");
      if (shouldStop?.() || entry.file.size > SMALL_FILE_BYTES || !isSafeRelativeFilePath(path) || isSensitiveFile(path) || isIgnoredFile(path.toLowerCase()) || canReuseIndexedFile(existing.get(entry.path), fingerprintOf(entry.file))) {
        return { entry, source: entry.file, bytes: void 0, error: void 0 };
      }
      try {
        return { entry, source: entry.file, bytes: new Uint8Array(await entry.file.arrayBuffer()), error: void 0 };
      } catch (error) {
        return { entry, source: entry.file, bytes: void 0, error };
      }
    }));
    let pending = prepare(0);
    for (let offset = 0; offset < entries.length; offset += FILE_READ_CONCURRENCY) {
      const prepared = await pending;
      if (shouldStop?.()) return;
      pending = offset + FILE_READ_CONCURRENCY < entries.length && !shouldStop?.() ? prepare(offset + FILE_READ_CONCURRENCY) : Promise.resolve([]);
      for (const file of prepared) {
        if (shouldStop?.()) return;
        yield file;
      }
    }
  };
  var canReuseIndexedFile = (existing, fingerprint) => existing?.fingerprint === fingerprint && (!existing.metadataOnly || existing.size === 0 && existing.chunkCount === 0);
  var classifyFile = async (path, file, maxIndexableFileBytes, bytes) => {
    const normalizedPath = path.replace(/\\/g, "/").toLowerCase();
    const fileName = normalizedPath.slice(normalizedPath.lastIndexOf("/") + 1);
    if (isSensitiveFile2(fileName) || isIgnoredFile(normalizedPath)) {
      return { action: "IGNORE", language: "binary", priority: 100 };
    }
    if (fileName === ".npmrc" && await containsSensitiveConfiguration(file, bytes)) {
      return { action: "IGNORE", language: "sensitive", priority: 100 };
    }
    const language = languageOf(fileName);
    if (!await looksLikeText(file, bytes)) {
      return { action: "IGNORE", language: "binary", priority: 100 };
    }
    if (file.size > maxIndexableFileBytes && !language) {
      return { action: "METADATA_ONLY", language: "text", priority: priorityOf(fileName) };
    }
    return {
      action: "INDEX",
      language: language ?? "text",
      priority: priorityOf(fileName)
    };
  };
  var estimateFileMetadataBytes = (path) => path.length * 2 + 512;
  var estimateChunkBytes = (content, searchTerms, symbols, imports) => content.length * 2 + searchTerms.reduce((total, term) => total + term.length * 2, 0) + symbols.reduce((total, symbol) => total + symbol.length * 2, 0) + imports.reduce((total, reference) => total + reference.length * 2, 0) + 1024;
  var createFileRecord = (projectId, entry, classification, chunkCount, metadataOnly, fingerprint, scanId, indexedCharacters, estimatedIndexBytes, symbols, imports) => ({
    id: `${projectId}\0${entry.path}`,
    projectId,
    path: entry.path,
    language: classification.language,
    size: entry.file.size,
    lastModified: entry.file.lastModified,
    priority: classification.priority,
    chunkCount,
    metadataOnly,
    fingerprint,
    lastSeenScanId: scanId,
    indexedCharacters,
    estimatedIndexBytes,
    symbols,
    imports
  });
  var fingerprintOf = (file) => `${INDEX_FORMAT_VERSION}:${file.size}:${file.lastModified}`;
  var createScanId = () => {
    if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
      return crypto.randomUUID();
    }
    return `scan-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  };
  var isSensitiveFile2 = (fileName) => SENSITIVE_FILE_NAMES2.has(fileName) || fileName.startsWith(".env.") && fileName !== ".env.example" || fileName.endsWith(".pem") || fileName.endsWith(".key") || fileName.endsWith(".p12") || fileName.endsWith(".jks");
  var isIgnoredFile = (path) => {
    if (path.split("/").some((segment) => segment === "logs" || segment === "log")) {
      return true;
    }
    return IGNORED_FILE_SUFFIXES.some((suffix) => path.endsWith(suffix));
  };
  var languageOf = (fileName) => {
    const byName = LANGUAGE_BY_FILE_NAME[fileName];
    if (byName) {
      return byName;
    }
    if (fileName === ".env.example") {
      return "dotenv";
    }
    const separator = fileName.lastIndexOf(".");
    return separator >= 0 ? LANGUAGE_BY_EXTENSION[fileName.slice(separator + 1)] : void 0;
  };
  var priorityOf = (fileName) => {
    if (PRIORITY_FILE_NAMES.has(fileName)) {
      return 0;
    }
    return languageOf(fileName) ? 10 : 20;
  };
  var looksLikeText = async (file, bytes) => {
    if (file.size === 0) {
      return true;
    }
    const sample = bytes?.subarray(0, TEXT_SAMPLE_BYTES) ?? new Uint8Array(await file.slice(0, TEXT_SAMPLE_BYTES).arrayBuffer());
    let suspiciousBytes = 0;
    for (const byte of sample) {
      if (byte === 0) {
        return false;
      }
      if (byte < 9 || byte > 13 && byte < 32) {
        suspiciousBytes += 1;
      }
    }
    return suspiciousBytes / sample.length < 0.08;
  };
  var containsSensitiveConfiguration = async (file, bytes) => {
    const sample = bytes ? new TextDecoder().decode(bytes.subarray(0, 64 * 1024)) : await file.slice(0, 64 * 1024).text();
    return SENSITIVE_NPMRC_PATTERN.test(sample);
  };
  var STRUCTURED_CHUNK_LANGUAGES = /* @__PURE__ */ new Set([
    "c",
    "cpp",
    "csharp",
    "fsharp",
    "go",
    "java",
    "javascript",
    "kotlin",
    "php",
    "python",
    "ruby",
    "rust",
    "scala",
    "swift",
    "typescript",
    "vue",
    "xml",
    "yaml",
    "json",
    "toml",
    "ini",
    "sql",
    "hcl",
    "razor",
    "jsp",
    "pug",
    "less",
    "sass"
  ]);
  var STRUCTURED_BOUNDARY_PATTERN = /^(?:\s*)(?:class|interface|enum|record|struct|function|async\s+function|def|async\s+def|func|fn|public|private|protected|export|import|package|module|\b[A-Za-z_$][\w$]*\s*[:=]|[-#]{2,}|<\/?[A-Za-z])/;
  var streamTextChunks = async function* (file, language, onBytesRead, bytes) {
    const decoder = new TextDecoder("utf-8", { fatal: false });
    let buffer = "";
    let chunk = "";
    const structured = STRUCTURED_CHUNK_LANGUAGES.has(language);
    let bytesRead = 0;
    const flushAtLineBoundary = function* () {
      const lines = buffer.split(/(?<=\n)/);
      buffer = lines.pop() ?? "";
      for (const line of lines) {
        const atBoundary = structured && STRUCTURED_BOUNDARY_PATTERN.test(line);
        const reachedWindow = chunk.length >= INDEX_CHUNK_CHARACTERS;
        if (reachedWindow && (atBoundary || !structured)) {
          yield chunk;
          chunk = "";
        }
        chunk += line;
        while (chunk.length >= INDEX_CHUNK_CHARACTERS * 2) {
          const end = safeChunkEnd(chunk);
          yield chunk.slice(0, end);
          chunk = chunk.slice(end);
        }
      }
      while (chunk.length + buffer.length >= INDEX_CHUNK_CHARACTERS * 2) {
        const combined = chunk + buffer;
        const end = safeChunkEnd(combined);
        yield combined.slice(0, end);
        buffer = combined.slice(end);
        chunk = "";
      }
    };
    for await (const part of readSourceBytes(file, bytes)) {
      bytesRead += part.byteLength;
      onBytesRead?.(bytesRead);
      buffer += decoder.decode(part, { stream: true });
      yield* flushAtLineBoundary();
    }
    buffer += decoder.decode();
    chunk += buffer;
    if (chunk.length > 0) {
      yield chunk;
    }
  };
  var readSourceBytes = async function* (file, bytes) {
    if (bytes) {
      yield bytes;
      return;
    }
    const reader = file.stream().getReader();
    try {
      while (true) {
        const result = await reader.read();
        if (result.done) return;
        yield result.value;
      }
    } finally {
      reader.releaseLock();
    }
  };
  var safeChunkEnd = (content) => {
    const end = INDEX_CHUNK_CHARACTERS * 2;
    const last = content.charCodeAt(end - 1);
    return last >= 55296 && last <= 56319 ? end - 1 : end;
  };
  var buildSearchTerms = (projectId, path, content) => extractTerms(`${path}
${content}`).map((term) => `${projectId}:${term}`);
  var extractCodeMetadata = (content, language) => {
    const symbols = /* @__PURE__ */ new Set();
    const imports = /* @__PURE__ */ new Set();
    const addSymbolMatches = (pattern) => {
      for (const match of content.matchAll(pattern)) {
        const symbol = match[1]?.toLowerCase();
        if (symbol && !CONTROL_FLOW_NAMES.has(symbol)) {
          symbols.add(symbol);
        }
      }
    };
    addSymbolMatches(/\b(?:class|interface|enum|record|trait|struct|type)\s+([A-Za-z_$][\w$]*)/g);
    addSymbolMatches(/\b(?:function|def|fn)\s+([A-Za-z_$][\w$]*)/g);
    addSymbolMatches(/\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)/g);
    if (SOURCE_CODE_LANGUAGES.has(language)) {
      addSymbolMatches(/^\s*(?:async\s+)?([A-Za-z_$][\w$]*)\s*\([^)]*\)\s*\{/gm);
      addSymbolMatches(/[;{}]\s*(?:async\s+)?([A-Za-z_$][\w$]*)\s*\([^)]*\)\s*\{/g);
      addSymbolMatches(
        /^\s*(?:(?:public|protected|private|static|final|abstract|synchronized|native|default|suspend|override)\s+)*(?:[\w$<>\[\],.?]+\s+)+([A-Za-z_$][\w$]*)\s*\([^;{}]*\)\s*(?:throws\s+[^\{]+)?\{/gm
      );
    }
    const addImportMatches = (pattern) => {
      for (const match of content.matchAll(pattern)) {
        for (const term of extractTerms(match[1] ?? "")) {
          imports.add(term);
        }
      }
    };
    addImportMatches(/\bfrom\s+["']([^"']+)["']/g);
    addImportMatches(/\b(?:import|require)\s*\(?\s*["']([^"']+)["']/g);
    addImportMatches(/^\s*import\s+([A-Za-z0-9_.$/:-]+)\s*;?/gm);
    addImportMatches(/^\s*from\s+([A-Za-z0-9_.$/:-]+)\s+import\s+/gm);
    return {
      symbols: Array.from(symbols).slice(0, 128),
      imports: Array.from(imports).slice(0, 128)
    };
  };
  var extractTerms = (value) => {
    const terms = /* @__PURE__ */ new Set();
    const normalized = value.toLowerCase();
    for (const match of normalized.matchAll(/[a-z_$][a-z0-9_$.-]{1,63}/g)) {
      terms.add(match[0]);
      for (const part of match[0].split(/[._$-]+/)) {
        if (part.length >= 2) {
          terms.add(part);
        }
      }
    }
    for (const match of normalized.matchAll(/[\u3400-\u9fff]{2,16}/g)) {
      const text = match[0];
      terms.add(text);
      for (let index = 0; index < text.length - 1; index += 1) {
        terms.add(text.slice(index, index + 2));
      }
    }
    return Array.from(terms);
  };
  var CONTROL_FLOW_NAMES = /* @__PURE__ */ new Set(["if", "for", "while", "switch", "catch"]);
  var SOURCE_CODE_LANGUAGES = /* @__PURE__ */ new Set([
    "c",
    "cpp",
    "csharp",
    "dart",
    "elixir",
    "fsharp",
    "go",
    "groovy",
    "java",
    "javascript",
    "kotlin",
    "ocaml",
    "perl",
    "php",
    "python",
    "ruby",
    "rust",
    "scala",
    "swift",
    "typescript",
    "vue"
  ]);

  // apps/web/src/features/project-index/projectIndexStorage.ts
  var ISOLATED_ID = /^pi-([bd])1-[a-z0-9-]+$/;
  var DATABASE_PREFIX = "prompt-optimizer-project-";
  var storageBuckets = () => globalThis.navigator?.storageBuckets;
  var createIsolatedProjectId = () => {
    const key = globalThis.crypto?.randomUUID?.() ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
    return `pi-${storageBuckets() ? "b" : "d"}1-${key}`;
  };
  var isIsolatedProject = (projectId) => ISOLATED_ID.test(projectId);
  var projectDatabaseLocation = (projectId) => {
    const match = ISOLATED_ID.exec(projectId);
    if (!match) return void 0;
    return {
      name: `${DATABASE_PREFIX}${projectId}`,
      factory: async () => {
        if (match[1] === "d") return indexedDB;
        const manager = storageBuckets();
        if (!manager) throw new Error("\u5F53\u524D\u6D4F\u89C8\u5668\u4E0D\u652F\u6301\u6B64\u9879\u76EE\u7684\u72EC\u7ACB\u5B58\u50A8\uFF0C\u8BF7\u5728\u539F\u6D4F\u89C8\u5668\u7EE7\u7EED\u7D22\u5F15");
        return (await waitForIndexStorage(manager.open(projectId))).indexedDB;
      }
    };
  };
  var prepareIsolatedStorage = async (projectId, expiresAt, retention) => {
    if (!projectId.startsWith("pi-b1-") || !isIsolatedProject(projectId)) return;
    const manager = storageBuckets();
    if (!manager) throw new Error("\u5F53\u524D\u6D4F\u89C8\u5668\u4E0D\u652F\u6301\u6B64\u9879\u76EE\u7684\u72EC\u7ACB\u5B58\u50A8\uFF0C\u8BF7\u5728\u539F\u6D4F\u89C8\u5668\u7EE7\u7EED\u7D22\u5F15");
    const expires = Date.parse(expiresAt);
    if (!Number.isFinite(expires) || expires <= Date.now()) throw new Error("\u9879\u76EE\u7D22\u5F15\u4FDD\u7559\u671F\u9650\u5DF2\u5931\u6548\uFF0C\u8BF7\u91CD\u65B0\u9009\u62E9\u76EE\u5F55");
    const bucket = await waitForIndexStorage(manager.open(projectId, { expires, durability: "strict" }));
    await waitForIndexStorage(bucket.setExpires(expires));
    if (retention === "PERSISTENT") await waitForIndexStorage(bucket.persist());
  };
  var deleteIsolatedStorage = async (projectId) => {
    const location = projectDatabaseLocation(projectId);
    if (!location) return;
    if (projectId.startsWith("pi-b1-")) {
      const manager = storageBuckets();
      if (!manager) throw new Error("\u5F53\u524D\u6D4F\u89C8\u5668\u65E0\u6CD5\u6E05\u7406\u6B64\u9879\u76EE\u7684\u72EC\u7ACB\u5B58\u50A8");
      await waitForIndexStorage(manager.delete(projectId));
    } else {
      await waitForIndexStorage(new Promise((resolve, reject) => {
        const request = indexedDB.deleteDatabase(location.name);
        request.onsuccess = () => resolve();
        request.onerror = () => reject(request.error);
        request.onblocked = () => reject(new Error("\u5176\u4ED6\u9875\u9762\u6B63\u5728\u4F7F\u7528\u8BE5\u9879\u76EE\u7D22\u5F15\uFF0C\u8BF7\u5173\u95ED\u5BF9\u5E94\u9875\u9762\u540E\u91CD\u8BD5"));
      }));
    }
  };
  var listIsolatedProjects = async (kind) => {
    if (kind === "bucket") {
      const manager = storageBuckets();
      return manager ? (await waitForIndexStorage(manager.keys())).filter((id) => /^pi-b1-/.test(id) && isIsolatedProject(id)) : [];
    }
    const databases = typeof indexedDB.databases === "function" ? await waitForIndexStorage(indexedDB.databases()) : [];
    return databases.map((db) => db.name?.startsWith(DATABASE_PREFIX) ? db.name.slice(DATABASE_PREFIX.length) : "").filter((id) => /^pi-d1-/.test(id) && isIsolatedProject(id));
  };
  var waitForIndexStorage = (pending, timeoutMs = 2e4) => new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("\u6D4F\u89C8\u5668\u672C\u5730\u5B58\u50A8\u957F\u65F6\u95F4\u672A\u54CD\u5E94\uFF0C\u8BF7\u5237\u65B0\u672C\u9875\u9762\u540E\u7EE7\u7EED\u7D22\u5F15\uFF1B\u5DF2\u6709\u9879\u76EE\u6570\u636E\u4ECD\u4FDD\u7559")), timeoutMs);
    pending.then((value) => {
      clearTimeout(timer);
      resolve(value);
    }, (error) => {
      clearTimeout(timer);
      reject(error);
    });
  });

  // apps/web/src/features/project-index/projectIndexSearchIndex.ts
  var SEARCH_STORE = "chunkSearch";
  var SEARCH_KEY_STORE = "chunkSearchKeys";
  var KEY_BLOCK_SIZE = 256;
  var MIGRATION_TERM_BATCH = 64e3;
  var createSearchStores = (database) => {
    const search = database.createObjectStore(SEARCH_STORE, { keyPath: "key", autoIncrement: true });
    search.createIndex("chunkId", "id", { unique: true });
    search.createIndex("searchTerms", "searchTerms", { multiEntry: true });
    database.createObjectStore(SEARCH_KEY_STORE, { keyPath: "block" });
  };
  var chunkBodyRecord = (chunk) => {
    const { searchTerms: _searchTerms, ...body } = chunk;
    return body;
  };
  var upsertSearchIndex = async (transaction, chunks, removed = [], clearedPaths = /* @__PURE__ */ new Set()) => {
    const store = transaction.objectStore(SEARCH_STORE);
    const unique = new Map(chunks.map((chunk) => [chunk.id, chunk]));
    const ranges = /* @__PURE__ */ new Map();
    for (const chunk of unique.values()) {
      if (clearedPaths.has(`${chunk.projectId}\0${chunk.path}`)) continue;
      const path = `${chunk.projectId}\0${chunk.path}`;
      const ids = ranges.get(path) ?? [];
      ids.push(chunk.id);
      ranges.set(path, ids);
    }
    const existing = /* @__PURE__ */ new Map();
    await Promise.all(Array.from(ranges.values(), (ids) => new Promise((resolve, reject) => {
      ids.sort();
      let offset = 0;
      const request = store.index("chunkId").openKeyCursor(IDBKeyRange.bound(ids[0], ids[ids.length - 1]));
      request.onsuccess = () => {
        const cursor = request.result;
        if (!cursor) {
          resolve();
          return;
        }
        const id = cursor.key;
        while (offset < ids.length && ids[offset] < id) offset++;
        if (ids[offset] === id) {
          existing.set(id, cursor.primaryKey);
          offset++;
        }
        if (offset < ids.length) cursor.continue(ids[offset]);
        else resolve();
      };
      request.onerror = () => reject(request.error);
    })));
    const pairs = await Promise.all(Array.from(unique.values(), async (chunk) => {
      const key = existing.get(chunk.id);
      const value = { id: chunk.id, searchTerms: chunk.searchTerms };
      const saved = await readRequest(key === void 0 ? store.add(value) : store.put({ ...value, key }));
      return [saved, chunk.id];
    }));
    await updateKeyBlocks(transaction, pairs, removed);
  };
  var replaceSearchIndex = async (transaction, projectId, paths, chunks) => {
    const removed = [];
    const uniquePaths = new Set(paths);
    for (const path of uniquePaths) {
      const prefix = `${projectId}\0${path}\0`;
      await removeSearchRange(transaction, IDBKeyRange.bound(prefix, `${prefix}\uFFFF`), removed);
    }
    await upsertSearchIndex(
      transaction,
      chunks,
      removed,
      new Set(Array.from(uniquePaths, (path) => `${projectId}\0${path}`))
    );
  };
  var removeSearchFiles = async (transaction, projectId, paths) => replaceSearchIndex(transaction, projectId, paths, []);
  var removeProjectSearch = async (transaction, projectId) => {
    await removeSearchRange(transaction, IDBKeyRange.bound(`${projectId}\0`, `${projectId}\0\uFFFF`));
  };
  var lookupSearchChunkIds = async (transaction, term, limit) => {
    const keys = await readRequest(transaction.objectStore(SEARCH_STORE).index("searchTerms").getAllKeys(term));
    const blocks = transaction.objectStore(SEARCH_KEY_STORE);
    let selected = [];
    for (let offset = 0; offset < keys.length; offset += KEY_BLOCK_SIZE) {
      const batch = keys.slice(offset, offset + KEY_BLOCK_SIZE);
      const blockNumbers = new Set(batch.map(blockOf));
      const rows = await Promise.all(Array.from(blockNumbers, (block) => readRequest(blocks.get(block))));
      const byBlock = new Map(rows.filter((row) => row !== void 0).map((row) => [row.block, row.ids]));
      const ids = batch.map((key) => {
        const id = byBlock.get(blockOf(key))?.[key % KEY_BLOCK_SIZE];
        if (id === void 0) throw new Error("\u672C\u5730\u7D22\u5F15\u6620\u5C04\u4E0D\u5B8C\u6574\uFF0C\u8BF7\u91CD\u65B0\u5EFA\u7ACB\u9879\u76EE\u7D22\u5F15");
        return id;
      });
      selected = Array.from(/* @__PURE__ */ new Set([...selected, ...ids])).sort().slice(0, limit);
    }
    return selected;
  };
  var migrateChunkSearch = (transaction, chunks, onProgress) => {
    let pending = [];
    let termReferences = 0;
    let completed = 0, total = 0;
    const count = chunks.count();
    count.onsuccess = () => {
      total = count.result;
      onProgress?.(0, total);
    };
    count.onerror = () => transaction.abort();
    const request = chunks.openCursor();
    request.onsuccess = () => {
      const cursor = request.result;
      if (cursor) {
        const chunk = cursor.value;
        pending.push(chunk);
        termReferences += chunk.searchTerms.length;
        cursor.update(chunkBodyRecord(chunk));
      }
      if (!cursor || pending.length >= KEY_BLOCK_SIZE || termReferences >= MIGRATION_TERM_BATCH) {
        const batch = pending;
        pending = [];
        termReferences = 0;
        void upsertSearchIndex(transaction, batch).then(() => {
          completed += batch.length;
          onProgress?.(completed, total);
          cursor?.continue();
        }).catch(() => transaction.abort());
      } else cursor.continue();
    };
    request.onerror = () => transaction.abort();
  };
  var removeSearchRange = async (transaction, range, deferred) => {
    const store = transaction.objectStore(SEARCH_STORE);
    for (; ; ) {
      const keys = await readRequest(store.index("chunkId").getAllKeys(range, KEY_BLOCK_SIZE));
      if (keys.length === 0) return;
      for (const key of keys) store.delete(key);
      if (deferred) {
        deferred.push(...keys);
        if (deferred.length >= 4096) await updateKeyBlocks(transaction, [], deferred.splice(0));
      } else await updateKeyBlocks(transaction, [], keys);
    }
  };
  var updateKeyBlocks = async (transaction, pairs, removed) => {
    const changes = /* @__PURE__ */ new Map();
    const group = (key) => {
      const block = blockOf(key);
      const change = changes.get(block) ?? { puts: [], removes: [] };
      changes.set(block, change);
      return change;
    };
    for (const [key, id] of pairs) group(key).puts.push([key % KEY_BLOCK_SIZE, id]);
    for (const key of removed) group(key).removes.push(key % KEY_BLOCK_SIZE);
    const store = transaction.objectStore(SEARCH_KEY_STORE);
    await Promise.all(Array.from(changes, async ([block, change]) => {
      const row = await readRequest(store.get(block)) ?? { block, ids: {} };
      for (const key of change.removes) delete row.ids[key];
      for (const [key, id] of change.puts) row.ids[key] = id;
      if (Object.keys(row.ids).length) store.put(row);
      else store.delete(block);
    }));
  };
  var blockOf = (key) => Math.floor(key / KEY_BLOCK_SIZE);
  var readRequest = (request) => new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error("\u672C\u5730\u5168\u6587\u68C0\u7D22\u7D22\u5F15\u64CD\u4F5C\u5931\u8D25"));
  });

  // apps/web/src/features/project-index/indexedDbProjectIndexRepository.ts
  var DATABASE_NAME = "prompt-optimizer-project-index";
  var DATABASE_VERSION = 4;
  var PROJECT_STORE = "projects";
  var FILE_STORE = "files";
  var CHUNK_STORE = "chunks";
  var SOURCE_STORE = "sources";
  var IndexedDbProjectIndexRepository = class {
    constructor(location) {
      this.location = location;
    }
    databasePromise;
    activeDatabase;
    preparation;
    preparationListeners = /* @__PURE__ */ new Set();
    /** 观察共享连接的准备进度；订阅不启动迁移，离开页面只解除订阅，不删除或中断其他调用的数据。 */
    observePreparation(listener) {
      this.preparationListeners.add(listener);
      if (this.preparation) listener(this.preparation);
      return () => {
        this.preparationListeners.delete(listener);
      };
    }
    reportPreparation(progress) {
      this.preparation = progress;
      for (const listener of this.preparationListeners) listener(progress);
    }
    async beginProject(summary) {
      await this.putProject(summary);
    }
    async findFiles(projectId, paths) {
      if (paths.length === 0) {
        return [];
      }
      const transaction = await this.createTransaction(FILE_STORE, "readonly");
      const completion = transactionComplete(transaction);
      const index = transaction.objectStore(FILE_STORE).index("projectPath");
      const requests = paths.map((path) => requestResult(index.get([projectId, path])));
      const files = await Promise.all(requests);
      await completion;
      return files.filter((file) => file !== void 0);
    }
    async writeBatch(files, chunks, replacedPaths = [], checkpoint) {
      if (!checkpoint && files.length === 0 && chunks.length === 0 && replacedPaths.length === 0) {
        return;
      }
      const writesContent = chunks.length > 0 || replacedPaths.length > 0;
      const stores = writesContent ? [FILE_STORE, CHUNK_STORE, SEARCH_STORE, SEARCH_KEY_STORE] : [FILE_STORE];
      if (checkpoint) stores.push(PROJECT_STORE);
      const transaction = await this.createTransaction(stores, "readwrite");
      const completion = transactionComplete(transaction);
      void completion.catch(() => void 0);
      const fileStore = transaction.objectStore(FILE_STORE);
      const projectId = checkpoint?.id ?? files[0]?.projectId ?? chunks[0]?.projectId;
      try {
        if (writesContent && projectId) {
          const chunkStore = transaction.objectStore(CHUNK_STORE);
          for (const path of replacedPaths) chunkStore.delete(chunkIdRange(projectId, path));
          for (const chunk of chunks) chunkStore.put(chunkBodyRecord(chunk));
          await replaceSearchIndex(transaction, projectId, replacedPaths, chunks);
        }
        for (const file of files) fileStore.put(file);
        if (checkpoint) transaction.objectStore(PROJECT_STORE).put(checkpoint);
        await completion;
      } catch (error) {
        try {
          transaction.abort();
        } catch {
        }
        throw transaction.error ?? error;
      }
    }
    async pauseProject(summary) {
      await this.putProject(summary);
    }
    async completeProject(summary) {
      await this.putProject(summary);
    }
    async removeUnseenFiles(projectId, scanId) {
      const transaction = await this.createTransaction(
        [FILE_STORE, CHUNK_STORE, SEARCH_STORE, SEARCH_KEY_STORE],
        "readwrite"
      );
      const completion = transactionComplete(transaction);
      const fileStore = transaction.objectStore(FILE_STORE);
      const chunkStore = transaction.objectStore(CHUNK_STORE);
      const index = fileStore.index("projectScan");
      let removedFiles = 0;
      const removeFromCursor = (request) => new Promise((resolve, reject) => {
        request.onsuccess = () => {
          const cursor = request.result;
          if (!cursor) {
            resolve();
            return;
          }
          const file = cursor.value;
          void removeSearchFiles(transaction, projectId, [file.path]).then(() => {
            cursor.delete();
            chunkStore.delete(chunkIdRange(projectId, file.path));
            removedFiles += 1;
            cursor.continue();
          }).catch(reject);
        };
        request.onerror = () => reject(request.error);
      });
      void completion.catch(() => void 0);
      try {
        await removeFromCursor(index.openCursor(IDBKeyRange.bound(
          [projectId, ""],
          [projectId, scanId],
          false,
          true
        )));
        await removeFromCursor(index.openCursor(IDBKeyRange.bound(
          [projectId, scanId],
          [projectId, "\uFFFF"],
          true,
          false
        )));
        await completion;
        return removedFiles;
      } catch (error) {
        try {
          transaction.abort();
        } catch {
        }
        throw transaction.error ?? error;
      }
    }
    async failProject(projectId, message) {
      const summary = await this.findProject(projectId);
      if (!summary) {
        return;
      }
      await this.putProject({ ...summary, status: "FAILED", errorMessage: message });
    }
    async findCandidateChunks(projectId, searchTerms, limit) {
      if (limit <= 0) return [];
      const unique = /* @__PURE__ */ new Map();
      const priorityChunks = await this.getChunksByPriority(projectId, Math.min(limit, 40));
      for (const chunk of priorityChunks) {
        unique.set(chunk.id, chunk);
      }
      const distinctTerms = Array.from(new Set(searchTerms));
      const terms = distinctTerms.length <= 64 ? distinctTerms : [...distinctTerms.slice(0, 32), ...distinctTerms.slice(-32)];
      const hits = /* @__PURE__ */ new Map();
      const perTermLimit = Math.min(100, Math.max(8, Math.ceil(limit * 2 / Math.max(1, terms.length))));
      for (let start = 0; start < terms.length; start += 8) {
        const groups = await Promise.all(terms.slice(start, start + 8).map((term) => this.getChunksBySearchTerm(`${projectId}:${term}`, perTermLimit)));
        for (const matches of groups) {
          for (const chunk of matches) {
            unique.set(chunk.id, chunk);
            hits.set(chunk.id, (hits.get(chunk.id) ?? 0) + 1);
          }
        }
      }
      if (unique.size === 0) {
        const fallback = await this.getChunksByProject(projectId, limit);
        for (const chunk of fallback) {
          unique.set(chunk.id, chunk);
        }
      }
      return Array.from(unique.values()).sort((left, right) => (hits.get(right.id) ?? 0) - (hits.get(left.id) ?? 0) || left.priority - right.priority || left.id.localeCompare(right.id)).slice(0, limit);
    }
    async findChunksByPaths(projectId, paths, limit) {
      if (paths.length === 0 || limit <= 0) {
        return [];
      }
      const project = await this.findProject(projectId);
      const variants = Array.from(new Set(paths.flatMap((path) => referencedPathVariants(path, project?.rootName))));
      if (variants.length === 0) return [];
      const fileTransaction = await this.createTransaction(FILE_STORE, "readonly");
      const fileCompletion = transactionComplete(fileTransaction);
      const fileIndex = fileTransaction.objectStore(FILE_STORE).index("projectPath");
      const fileRequests = variants.flatMap((path) => [
        requestResult(fileIndex.getAll(IDBKeyRange.only([projectId, path]), 1)),
        requestResult(fileIndex.getAll(
          IDBKeyRange.bound([projectId, `${path}/`], [projectId, `${path}/\uFFFF`]),
          limit
        ))
      ]);
      const fileGroups = await Promise.all(fileRequests);
      await fileCompletion;
      const resolvedPaths = Array.from(new Set(fileGroups.flat().map((file) => file.path))).slice(0, limit);
      if (resolvedPaths.length === 0) return [];
      const transaction = await this.createTransaction([CHUNK_STORE, SEARCH_STORE], "readonly");
      const completion = transactionComplete(transaction);
      const index = transaction.objectStore(CHUNK_STORE).index("projectPath");
      const perPathLimit = Math.max(1, Math.ceil(limit / resolvedPaths.length));
      const requests = resolvedPaths.map((path) => requestResult(
        index.getAll(IDBKeyRange.only([projectId, path]), perPathLimit)
      ));
      const groups = await Promise.all((await Promise.all(requests)).map((chunks) => hydrateChunkTerms(transaction, chunks)));
      await completion;
      const result = [];
      for (let offset = 0; offset < perPathLimit && result.length < limit; offset += 1) {
        for (const group of groups) {
          const chunk = group[offset];
          if (chunk && result.length < limit) result.push(normalizeChunk(chunk));
        }
      }
      return result;
    }
    async deleteProject(projectId) {
      const transaction = await this.createTransaction(
        [PROJECT_STORE, FILE_STORE, CHUNK_STORE, SOURCE_STORE, SEARCH_STORE, SEARCH_KEY_STORE],
        "readwrite"
      );
      const completion = transactionComplete(transaction);
      void completion.catch(() => void 0);
      try {
        await removeProjectSearch(transaction, projectId);
        transaction.objectStore(FILE_STORE).delete(projectRecordRange(projectId));
        transaction.objectStore(CHUNK_STORE).delete(projectRecordRange(projectId));
        transaction.objectStore(PROJECT_STORE).delete(projectId);
        transaction.objectStore(SOURCE_STORE).delete(projectId);
        await completion;
      } catch (error) {
        try {
          transaction.abort();
        } catch {
        }
        throw transaction.error ?? error;
      }
    }
    async saveProjectSource(projectId, rootHandle) {
      const transaction = await this.createTransaction(SOURCE_STORE, "readwrite");
      const completion = transactionComplete(transaction);
      transaction.objectStore(SOURCE_STORE).put({ projectId, rootHandle });
      await completion;
    }
    async findProjectSource(projectId) {
      const transaction = await this.createTransaction(SOURCE_STORE, "readonly");
      const completion = transactionComplete(transaction);
      const source = await requestResult(
        transaction.objectStore(SOURCE_STORE).get(projectId)
      );
      await completion;
      return source?.rootHandle;
    }
    async findProject(projectId) {
      const transaction = await this.createTransaction(PROJECT_STORE, "readonly");
      const completion = transactionComplete(transaction);
      const result = await requestResult(
        transaction.objectStore(PROJECT_STORE).get(projectId)
      );
      await completion;
      return result ? normalizeProjectSummary(result) : void 0;
    }
    /**
     * 只清理到期索引；失败任务的已提交批次仍可恢复，不能在重试前自动删除。
     */
    async cleanupProjects(now = /* @__PURE__ */ new Date()) {
      const projects = await this.listProjects();
      const expired = projects.filter((project) => {
        const expiresAt = Date.parse(project.expiresAt);
        const reachedExpiry = Number.isFinite(expiresAt) && expiresAt <= now.getTime();
        return reachedExpiry;
      });
      this.reportPreparation({ stage: "CLEANING_STORAGE", completed: 0, total: expired.length });
      let cleaned = 0;
      for (const project of expired) {
        await this.deleteProject(project.id);
        this.reportPreparation({ stage: "CLEANING_STORAGE", completed: ++cleaned, total: expired.length });
      }
      this.preparation = void 0;
      return expired.length;
    }
    async deleteAllProjects() {
      const transaction = await this.createTransaction(
        [PROJECT_STORE, FILE_STORE, CHUNK_STORE, SOURCE_STORE, SEARCH_STORE, SEARCH_KEY_STORE],
        "readwrite"
      );
      const completion = transactionComplete(transaction);
      transaction.objectStore(PROJECT_STORE).clear();
      transaction.objectStore(FILE_STORE).clear();
      transaction.objectStore(CHUNK_STORE).clear();
      transaction.objectStore(SOURCE_STORE).clear();
      transaction.objectStore(SEARCH_STORE).clear();
      transaction.objectStore(SEARCH_KEY_STORE).clear();
      await completion;
    }
    async putProject(summary) {
      const transaction = await this.createTransaction(PROJECT_STORE, "readwrite");
      const completion = transactionComplete(transaction);
      transaction.objectStore(PROJECT_STORE).put(summary);
      await completion;
    }
    async listProjects() {
      const transaction = await this.createTransaction(PROJECT_STORE, "readonly");
      const completion = transactionComplete(transaction);
      const result = await requestResult(
        transaction.objectStore(PROJECT_STORE).getAll()
      );
      await completion;
      return result.map(normalizeProjectSummary);
    }
    async getChunksByPriority(projectId, limit) {
      const range = IDBKeyRange.bound([projectId, 0], [projectId, 9]);
      return this.getChunksFromIndex("projectPriority", range, limit);
    }
    async getChunksBySearchTerm(searchTerm, limit) {
      const transaction = await this.createTransaction([CHUNK_STORE, SEARCH_STORE, SEARCH_KEY_STORE], "readonly");
      const completion = transactionComplete(transaction);
      void completion.catch(() => void 0);
      const ids = await lookupSearchChunkIds(transaction, searchTerm, limit);
      const chunks = await Promise.all(ids.map((id) => requestResult(
        transaction.objectStore(CHUNK_STORE).get(id)
      )));
      const result = await hydrateChunkTerms(
        transaction,
        chunks.filter((chunk) => chunk !== void 0)
      );
      await completion;
      return result;
    }
    async getChunksByProject(projectId, limit) {
      return this.getChunksFromIndex("projectId", projectId, limit);
    }
    async getChunksFromIndex(indexName, query, limit) {
      const transaction = await this.createTransaction([CHUNK_STORE, SEARCH_STORE], "readonly");
      const completion = transactionComplete(transaction);
      const result = await requestResult(
        transaction.objectStore(CHUNK_STORE).index(indexName).getAll(query, limit)
      );
      const chunks = await hydrateChunkTerms(transaction, result);
      await completion;
      return chunks;
    }
    /**
     * 创建事务时如果浏览器正在关闭旧连接，则清除缓存并自动重连一次。
     */
    async createTransaction(storeNames, mode) {
      for (let attempt = 0; attempt < 2; attempt += 1) {
        const database = await this.openDatabase();
        try {
          return database.transaction(storeNames, mode);
        } catch (error) {
          if (attempt > 0 || !isClosingConnectionError(error)) {
            throw error;
          }
          this.invalidateDatabase(database);
        }
      }
      throw new Error("\u65E0\u6CD5\u521B\u5EFA\u672C\u5730\u9879\u76EE\u7D22\u5F15\u4E8B\u52A1");
    }
    openDatabase() {
      if (!this.databasePromise) {
        this.reportPreparation({ stage: "OPENING_STORAGE" });
        let openingPromise;
        openingPromise = (this.location?.factory() ?? Promise.resolve(indexedDB)).then((factory) => new Promise((resolve, reject) => {
          let abandoned = false;
          const request = factory.open(this.location?.name ?? DATABASE_NAME, DATABASE_VERSION);
          const timer = setTimeout(() => {
            abandoned = true;
            this.clearDatabaseCache(void 0, openingPromise);
            reject(new Error("\u6D4F\u89C8\u5668\u672C\u5730\u7D22\u5F15\u88AB\u957F\u65F6\u95F4\u5360\u7528\uFF0C\u8BF7\u5237\u65B0\u672C\u9875\u9762\u540E\u7EE7\u7EED\u7D22\u5F15\uFF1B\u5DF2\u6709\u6570\u636E\u4ECD\u4FDD\u7559"));
          }, 2e4);
          request.onupgradeneeded = (event) => {
            clearTimeout(timer);
            if (abandoned) {
              request.transaction?.abort();
              return;
            }
            this.reportPreparation({ stage: "UPGRADING_STORAGE" });
            const database = request.result;
            if (!database.objectStoreNames.contains(PROJECT_STORE)) {
              database.createObjectStore(PROJECT_STORE, { keyPath: "id" });
            }
            let fileStore;
            if (!database.objectStoreNames.contains(FILE_STORE)) {
              fileStore = database.createObjectStore(FILE_STORE, { keyPath: "id" });
              fileStore.createIndex("projectId", "projectId");
              fileStore.createIndex("projectPath", ["projectId", "path"], { unique: true });
            } else {
              fileStore = request.transaction.objectStore(FILE_STORE);
            }
            if (!fileStore.indexNames.contains("projectScan")) {
              fileStore.createIndex("projectScan", ["projectId", "lastSeenScanId"]);
            }
            if (event.oldVersion < 3) {
              migrateLegacyFileScanIds(fileStore);
            }
            let chunkStore;
            if (!database.objectStoreNames.contains(CHUNK_STORE)) {
              chunkStore = database.createObjectStore(CHUNK_STORE, { keyPath: "id" });
              chunkStore.createIndex("projectId", "projectId");
              chunkStore.createIndex("projectPath", ["projectId", "path"]);
              chunkStore.createIndex("projectPriority", ["projectId", "priority"]);
            } else {
              chunkStore = request.transaction.objectStore(CHUNK_STORE);
            }
            if (event.oldVersion < 4) {
              createSearchStores(database);
              if (chunkStore.indexNames.contains("searchTerms")) chunkStore.deleteIndex("searchTerms");
              migrateChunkSearch(request.transaction, chunkStore, (completed, total) => this.reportPreparation({ stage: "UPGRADING_STORAGE", completed, total }));
            }
            if (!database.objectStoreNames.contains(SOURCE_STORE)) {
              database.createObjectStore(SOURCE_STORE, { keyPath: "projectId" });
            }
          };
          request.onsuccess = () => {
            clearTimeout(timer);
            const database = request.result;
            if (abandoned) {
              database.close();
              return;
            }
            this.activeDatabase = database;
            this.preparation = void 0;
            database.onversionchange = () => this.invalidateDatabase(database);
            database.onclose = () => this.clearDatabaseCache(database, openingPromise);
            resolve(database);
          };
          request.onerror = () => {
            clearTimeout(timer);
            this.clearDatabaseCache(void 0, openingPromise);
            reject(request.error ?? new Error("\u65E0\u6CD5\u6253\u5F00\u672C\u5730\u9879\u76EE\u7D22\u5F15"));
          };
          request.onblocked = () => {
            clearTimeout(timer);
            abandoned = true;
            this.clearDatabaseCache(void 0, openingPromise);
            reject(new Error("\u5176\u4ED6\u9875\u9762\u4ECD\u5360\u7528\u65E7\u7248\u9879\u76EE\u7D22\u5F15\u3002\u8BF7\u5173\u95ED\u6216\u5237\u65B0\u540C\u4E00\u7AD9\u70B9\u7684\u5176\u4ED6\u6807\u7B7E\u9875\uFF0C\u518D\u70B9\u51FB\u201C\u7EE7\u7EED\u7D22\u5F15\u201D\uFF1B\u65E0\u9700\u5220\u9664\u5DF2\u6709\u6570\u636E\u3002"));
          };
        }));
        this.databasePromise = openingPromise;
        void openingPromise.catch(() => this.clearDatabaseCache(void 0, openingPromise));
      }
      return this.databasePromise;
    }
    /** 项目独立存储被清理时释放当前连接；不删除其他项目或用户的源文件。 */
    close() {
      if (this.activeDatabase) this.invalidateDatabase(this.activeDatabase);
    }
    invalidateDatabase(database) {
      this.clearDatabaseCache(database);
      try {
        database.close();
      } catch {
      }
    }
    clearDatabaseCache(database, openingPromise) {
      if (database && this.activeDatabase !== database) {
        return;
      }
      if (openingPromise && this.databasePromise !== openingPromise) {
        return;
      }
      this.activeDatabase = void 0;
      this.databasePromise = void 0;
      this.preparation = void 0;
    }
  };
  var chunkIdRange = (projectId, path) => {
    const prefix = `${projectId}\0${path}\0`;
    return IDBKeyRange.bound(prefix, `${prefix}\uFFFF`);
  };
  var projectRecordRange = (projectId) => {
    const prefix = `${projectId}\0`;
    return IDBKeyRange.bound(prefix, `${prefix}\uFFFF`);
  };
  var migrateLegacyFileScanIds = (fileStore) => {
    const request = fileStore.openCursor();
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) {
        return;
      }
      const file = cursor.value;
      if (typeof file.lastSeenScanId !== "string") {
        cursor.update({ ...file, lastSeenScanId: "" });
      }
      cursor.continue();
    };
    request.onerror = () => fileStore.transaction.abort();
  };
  var normalizeProjectSummary = (summary) => ({
    ...summary,
    addedFiles: summary.addedFiles ?? 0,
    updatedFiles: summary.updatedFiles ?? 0,
    unchangedFiles: summary.unchangedFiles ?? 0,
    removedFiles: summary.removedFiles ?? 0,
    changedPaths: summary.changedPaths ?? [],
    scanId: summary.scanId ?? "",
    lastCheckpointPath: summary.lastCheckpointPath ?? ""
  });
  var normalizeChunk = (chunk) => ({
    ...chunk,
    symbols: chunk.symbols ?? [],
    imports: chunk.imports ?? []
  });
  var hydrateChunkTerms = async (transaction, chunks) => {
    const index = transaction.objectStore(SEARCH_STORE).index("chunkId");
    return Promise.all(chunks.map(async (chunk) => {
      const search = await requestResult(index.get(chunk.id));
      if (!search) throw new Error("\u672C\u5730\u5168\u6587\u8BCD\u8868\u4E0D\u5B8C\u6574\uFF0C\u8BF7\u91CD\u65B0\u5EFA\u7ACB\u9879\u76EE\u7D22\u5F15");
      return normalizeChunk({ ...chunk, searchTerms: search.searchTerms });
    }));
  };
  var requestResult = (request) => new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error("\u672C\u5730\u7D22\u5F15\u64CD\u4F5C\u5931\u8D25"));
  });
  var transactionComplete = (transaction) => new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error ?? new Error("\u672C\u5730\u7D22\u5F15\u4E8B\u52A1\u5931\u8D25"));
    transaction.onabort = () => reject(transaction.error ?? new Error("\u672C\u5730\u7D22\u5F15\u4E8B\u52A1\u5DF2\u53D6\u6D88"));
  });
  var isClosingConnectionError = (error) => error instanceof DOMException && error.name === "InvalidStateError";
  var RoutedProjectIndexRepository = class {
    legacy = new IndexedDbProjectIndexRepository();
    projects = /* @__PURE__ */ new Map();
    cleanupPending = false;
    repository(projectId) {
      const location = projectDatabaseLocation(projectId);
      if (!location) return this.legacy;
      let repository = this.projects.get(projectId);
      if (!repository) {
        repository = new IndexedDbProjectIndexRepository(location);
        this.projects.set(projectId, repository);
      }
      return repository;
    }
    async prepareProject(projectId, expiresAt, retention) {
      if (isIsolatedProject(projectId)) await prepareIsolatedStorage(projectId, expiresAt, retention);
      else await this.legacy.cleanupProjects();
    }
    observePreparation(listener, projectId) {
      return this.repository(projectId).observePreparation(listener);
    }
    beginProject(summary) {
      return this.repository(summary.id).beginProject(summary);
    }
    findFiles(id, paths) {
      return this.repository(id).findFiles(id, paths);
    }
    writeBatch(files, chunks, paths = [], checkpoint) {
      const id = checkpoint?.id ?? files[0]?.projectId ?? chunks[0]?.projectId;
      if (!id) return Promise.resolve();
      if (files.some((file) => file.projectId !== id) || chunks.some((chunk) => chunk.projectId !== id)) {
        return Promise.reject(new Error("\u7D22\u5F15\u6279\u6B21\u4E0D\u80FD\u6DF7\u5165\u5176\u4ED6\u9879\u76EE\u7684\u6570\u636E"));
      }
      return this.repository(id).writeBatch(files, chunks, paths, checkpoint);
    }
    pauseProject(summary) {
      return this.repository(summary.id).pauseProject(summary);
    }
    completeProject(summary) {
      return this.repository(summary.id).completeProject(summary);
    }
    removeUnseenFiles(id, scanId) {
      return this.repository(id).removeUnseenFiles(id, scanId);
    }
    failProject(id, message) {
      return this.repository(id).failProject(id, message);
    }
    findCandidateChunks(id, terms, limit) {
      return this.repository(id).findCandidateChunks(id, terms, limit);
    }
    findChunksByPaths(id, paths, limit) {
      return this.repository(id).findChunksByPaths(id, paths, limit);
    }
    saveProjectSource(id, handle) {
      return this.repository(id).saveProjectSource(id, handle);
    }
    findProjectSource(id) {
      return this.repository(id).findProjectSource(id);
    }
    findProject(id) {
      return this.repository(id).findProject(id);
    }
    async deleteProject(id) {
      if (!isIsolatedProject(id)) return this.legacy.deleteProject(id);
      this.projects.get(id)?.close();
      await deleteIsolatedStorage(id);
      this.projects.delete(id);
    }
    /** 后台保留期维护与导入解耦；失败不把新项目误标为失败，下一次维护仍可重试。 */
    async cleanupProjects(now = /* @__PURE__ */ new Date()) {
      let count = 0;
      await listIsolatedProjects("bucket");
      for (const id of await listIsolatedProjects("database")) {
        const project = await this.repository(id).findProject(id);
        if (project && Date.parse(project.expiresAt) <= now.getTime()) {
          await this.deleteProject(id);
          count++;
        }
      }
      return count + await this.legacy.cleanupProjects(now);
    }
    scheduleCleanup() {
      if (this.cleanupPending) return;
      this.cleanupPending = true;
      setTimeout(() => {
        void this.cleanupProjects().catch(() => void 0).finally(() => {
          this.cleanupPending = false;
        });
      }, 1e3);
    }
    async deleteAllProjects() {
      const failures = [];
      for (const kind of ["bucket", "database"]) {
        try {
          for (const id of await listIsolatedProjects(kind)) await this.deleteProject(id);
        } catch (error) {
          failures.push(error);
        }
      }
      try {
        await this.legacy.deleteAllProjects();
      } catch (error) {
        failures.push(error);
      }
      if (failures.length) throw Object.assign(new Error("\u90E8\u5206\u672C\u5730\u7D22\u5F15\u5C1A\u672A\u6E05\u7406\u5B8C\u6210\uFF0C\u8BF7\u7A0D\u540E\u91CD\u8BD5"), { cause: failures[0] });
    }
  };
  var projectIndexRepository = new RoutedProjectIndexRepository();
  return __toCommonJS(entry_exports);
})();
