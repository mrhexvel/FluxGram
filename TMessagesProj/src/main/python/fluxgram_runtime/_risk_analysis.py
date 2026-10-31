import ast
import codecs
import dis
import io
import json
import re
import tokenize
from types import CodeType
from urllib.parse import urlsplit, urlunsplit


PROTOCOL_VERSION = 1
MAX_SOURCES = 128
MAX_SOURCE_BYTES = 256 * 1024
MAX_TOTAL_SOURCE_BYTES = 2 * 1024 * 1024
MAX_TOKENS = 50000
MAX_AST_NODES = 20000
MAX_AST_DEPTH = 160
MAX_CONSTANTS = 12000
MAX_STRING_BYTES = 2 * 1024 * 1024
MAX_CODE_OBJECTS = 4096
MAX_INSTRUCTIONS = 120000
MAX_FINDINGS = 64
MAX_DETAIL_UNITS = 256
MAX_RESPONSE_CHARS = 64 * 1024

_ENDPOINT = re.compile(
    r"(?i)(?:https?://[^\s]+|(?:[a-z0-9-]+\.)+[a-z]{2,}(?::[0-9]{1,5})?(?:/[^\s]*)?)"
)
_COOKIE = re.compile(br"^[ \t\f]*#.*?coding[:=][ \t]*([-\w.]+)")
codecs.lookup("utf-8-sig")


class _LimitError(Exception):
    pass


class _UnsupportedError(Exception):
    pass


class _Analyzer(ast.NodeVisitor):
    def __init__(self, path):
        self.path = path
        self.scopes = [{}]
        self.scope_kinds = ["module"]
        self.findings = {}
        self.nodes = 0
        self.depth = 0
        self.constants = 0
        self.string_bytes = 0
        self.control_depth = 0

    def add(self, evidence_id, category, detail=None, confidence="exact"):
        units = (
            sum(2 if ord(value) > 0xFFFF else 1 for value in detail)
            if detail is not None
            else 0
        )
        if units > MAX_DETAIL_UNITS:
            raise _LimitError()
        key = (evidence_id, detail or "")
        if key not in self.findings and len(self.findings) >= MAX_FINDINGS:
            raise _LimitError()
        if key not in self.findings:
            self.findings[key] = {
                "id": evidence_id,
                "category": category,
                "detail": detail,
                "confidence": confidence,
            }

    def generic_visit(self, node):
        super().generic_visit(node)

    def visit_Constant(self, node):
        self.constants += 1
        if self.constants > MAX_CONSTANTS:
            raise _LimitError()
        if isinstance(node.value, (str, bytes)):
            size = (
                len(node.value.encode("utf-8"))
                if isinstance(node.value, str)
                else len(node.value)
            )
            self.string_bytes += size
            if self.string_bytes > MAX_STRING_BYTES:
                raise _LimitError()
            if isinstance(node.value, str):
                for match in _ENDPOINT.finditer(node.value):
                    self.add(
                        "network.endpoint_literal",
                        "network",
                        _redact_endpoint(match.group(0)),
                    )
        self.generic_visit(node)

    def visit_Import(self, node):
        for alias in node.names:
            local = alias.asname or alias.name.split(".", 1)[0]
            target = alias.name if alias.asname else alias.name.split(".", 1)[0]
            self._bind(local, target)
            self._module(alias.name)
        self.generic_visit(node)

    def visit_ImportFrom(self, node):
        module = ("." * node.level) + (node.module or "")
        self._module(module)
        if node.level:
            self.add("analysis.dynamic_target", "dynamic_execution", module)
        for alias in node.names:
            if alias.name != "*":
                local = alias.asname or alias.name
                self._bind(local, (module + "." + alias.name).strip("."))
            else:
                self.add("analysis.obfuscated_or_dynamic", "dynamic_execution", module)
        self.generic_visit(node)

    def visit_Call(self, node):
        target = self._resolve(node.func)
        if target is None:
            self.add("analysis.dynamic_target", "dynamic_execution", self.path)
            self.add("analysis.obfuscated_or_dynamic", "dynamic_execution", self.path)
        self._call(target, node)
        self.generic_visit(node)

    def visit_Assign(self, node):
        self.visit(node.value)
        target = self._alias_target(node.value)
        for item in node.targets:
            self.visit(item)
            self._bind_target(item, target)

    def visit_AnnAssign(self, node):
        self.visit(node.annotation)
        self.visit(node.target)
        target = None
        if node.value is not None:
            self.visit(node.value)
            target = self._alias_target(node.value)
        self._bind_target(node.target, target)

    def visit_AugAssign(self, node):
        self.visit(node.target)
        self.visit(node.value)
        self._bind_target(node.target, None)

    def visit_NamedExpr(self, node):
        self.visit(node.value)
        target = self._alias_target(node.value)
        if self.scope_kinds[-1] == "comprehension" and isinstance(node.target, ast.Name):
            index = len(self.scope_kinds) - 2
            while index > 0 and self.scope_kinds[index] == "comprehension":
                index -= 1
            self._bind_at(index, node.target.id, target)
        else:
            self._bind_target(node.target, target)

    def visit_For(self, node):
        self._visit_control_flow(node)

    visit_AsyncFor = visit_For

    def visit_If(self, node):
        self.visit(node.test)
        self._visit_control_blocks(node.body, node.orelse)

    def visit_While(self, node):
        self.visit(node.test)
        self._visit_control_blocks(node.body, node.orelse)

    def visit_Try(self, node):
        self._visit_control_blocks(node.body)
        for handler in node.handlers:
            if handler.type is not None:
                self.visit(handler.type)
            self.control_depth += 1
            try:
                if handler.name is not None:
                    self._bind(handler.name, None)
                for item in handler.body:
                    self.visit(item)
            finally:
                self.control_depth -= 1
        self._visit_control_blocks(node.orelse, node.finalbody)

    visit_TryStar = visit_Try

    def visit_With(self, node):
        for item in node.items:
            self.visit(item.context_expr)
            if item.optional_vars is not None:
                self._bind_target(item.optional_vars, None)
        for item in node.body:
            self.visit(item)

    visit_AsyncWith = visit_With

    def visit_Match(self, node):
        self.visit(node.subject)
        self.add("analysis.dynamic_target", "dynamic_execution", self.path)
        self.add("analysis.obfuscated_or_dynamic", "dynamic_execution", self.path)
        for case in node.cases:
            self.control_depth += 1
            try:
                for item in ast.walk(case.pattern):
                    name = None
                    if isinstance(item, (ast.MatchAs, ast.MatchStar)):
                        name = item.name
                    elif isinstance(item, ast.MatchMapping):
                        name = item.rest
                    if name is not None:
                        self._bind(name, None)
                if case.guard is not None:
                    self.visit(case.guard)
                for item in case.body:
                    self.visit(item)
            finally:
                self.control_depth -= 1

    def visit_Global(self, node):
        self.add("analysis.dynamic_target", "dynamic_execution", self.path)
        self.add("analysis.obfuscated_or_dynamic", "dynamic_execution", self.path)
        for name in node.names:
            self._bind(name, None)

    visit_Nonlocal = visit_Global

    def visit_FunctionDef(self, node):
        self._visit_function(node)

    visit_AsyncFunctionDef = visit_FunctionDef

    def visit_Lambda(self, node):
        self._visit_arguments(node.args)
        self.scopes.append({})
        self.scope_kinds.append("lambda")
        self._bind_arguments(node.args)
        self.visit(node.body)
        self.scopes.pop()
        self.scope_kinds.pop()

    def visit_ListComp(self, node):
        self._visit_comprehension(node.generators, [node.elt])

    visit_SetComp = visit_ListComp
    visit_GeneratorExp = visit_ListComp

    def visit_DictComp(self, node):
        self._visit_comprehension(node.generators, [node.key, node.value])

    def visit_ClassDef(self, node):
        for item in node.decorator_list:
            self.visit(item)
        for item in node.bases:
            self.visit(item)
        for item in node.keywords:
            self.visit(item.value)
        self.scopes.append({})
        self.scope_kinds.append("class")
        for item in node.body:
            self.visit(item)
        self.scopes.pop()
        self.scope_kinds.pop()
        self._bind(node.name, "local." + node.name)

    def _visit_function(self, node):
        for item in node.decorator_list:
            self.visit(item)
        self._visit_arguments(node.args)
        if node.returns is not None:
            self.visit(node.returns)
        self._bind(node.name, "local." + node.name)
        self.scopes.append({})
        self.scope_kinds.append("function")
        self._bind_arguments(node.args)
        for item in node.body:
            self.visit(item)
        self.scopes.pop()
        self.scope_kinds.pop()

    def _visit_control_flow(self, node):
        self.visit(node.iter)
        self.control_depth += 1
        try:
            self._bind_target(node.target, None)
            for item in node.body:
                self.visit(item)
            for item in node.orelse:
                self.visit(item)
        finally:
            self.control_depth -= 1

    def _visit_control_blocks(self, *blocks):
        self.control_depth += 1
        try:
            for block in blocks:
                for item in block:
                    self.visit(item)
        finally:
            self.control_depth -= 1

    def _visit_comprehension(self, generators, values):
        first = generators[0]
        self.visit(first.iter)
        self.scopes.append({})
        self.scope_kinds.append("comprehension")
        try:
            self._bind_target(first.target, None)
            for item in first.ifs:
                self.visit(item)
            for generator in generators[1:]:
                self.visit(generator.iter)
                self._bind_target(generator.target, None)
                for item in generator.ifs:
                    self.visit(item)
            for value in values:
                self.visit(value)
        finally:
            self.scopes.pop()
            self.scope_kinds.pop()

    def _visit_arguments(self, arguments):
        for item in arguments.defaults:
            self.visit(item)
        for item in arguments.kw_defaults:
            if item is not None:
                self.visit(item)
        for item in (
            list(arguments.posonlyargs)
            + list(arguments.args)
            + list(arguments.kwonlyargs)
        ):
            if item.annotation is not None:
                self.visit(item.annotation)
        for item in (arguments.vararg, arguments.kwarg):
            if item is not None and item.annotation is not None:
                self.visit(item.annotation)

    def _bind_arguments(self, arguments):
        for item in (
            list(arguments.posonlyargs)
            + list(arguments.args)
            + list(arguments.kwonlyargs)
        ):
            self._bind(item.arg, None)
        for item in (arguments.vararg, arguments.kwarg):
            if item is not None:
                self._bind(item.arg, None)

    def _bind_target(self, node, target):
        if isinstance(node, ast.Name):
            self._bind(node.id, target)
        elif isinstance(node, (ast.Tuple, ast.List)):
            for item in node.elts:
                self._bind_target(item, None)

    def _bind(self, name, target):
        self._bind_at(len(self.scopes) - 1, name, target)

    def _bind_at(self, index, name, target):
        scope = self.scopes[index]
        if self.control_depth > 0 and name in scope and scope[name] != target:
            scope[name] = None
        else:
            scope[name] = target

    def _alias_target(self, node):
        if isinstance(node, (ast.Name, ast.Attribute)):
            return self._resolve(node)
        return None

    def _resolve(self, node):
        if isinstance(node, ast.Name):
            function_scope = self.scope_kinds[-1] in {
                "function",
                "lambda",
                "comprehension",
            }
            for index in range(len(self.scopes) - 1, -1, -1):
                if function_scope and self.scope_kinds[index] == "class":
                    continue
                scope = self.scopes[index]
                if node.id in scope:
                    return scope[node.id]
            if node.id in {
                "__import__",
                "compile",
                "eval",
                "exec",
                "getattr",
                "open",
            }:
                return "builtins." + node.id
            return None
        if isinstance(node, ast.Attribute):
            owner = self._resolve(node.value)
            return owner + "." + node.attr if owner else None
        return None

    def _module(self, module):
        normalized = module.lstrip(".")
        if normalized == "fluxgram_runtime" or normalized.startswith("fluxgram_runtime."):
            self.add("python.internal_runtime_import", "host_internals", module)
        if normalized in {"java", "jarray", "jclass"} or normalized.startswith(
            ("java.", "jarray.")
        ):
            self.add("bridge.java_direct", "java_bridge", module)
        if normalized == "org.telegram" or normalized.startswith("org.telegram."):
            evidence = (
                "host.plugin_internal_direct"
                if ".plugins" in normalized
                else "host.telegram_internal_direct"
            )
            self.add(evidence, "host_internals", module)
        if normalized in {"android", "androidx"} or normalized.startswith(
            ("android.", "androidx.", "com.google.android.material.")
        ):
            self.add("ui.direct_android", "android_ui", module)
        if normalized.split(".", 1)[0] in {
            "socket",
            "urllib",
            "http",
            "requests",
            "aiohttp",
            "websockets",
        }:
            self.add("network.raw", "network", module)
        if normalized.split(".", 1)[0] in {"os", "pathlib", "shutil", "sqlite3"}:
            self.add("filesystem.unmanaged", "filesystem", module)
        if normalized.split(".", 1)[0] in {"subprocess", "pty"}:
            self.add("process.shell_execution", "process", module)
        if normalized.split(".", 1)[0] in {
            "threading",
            "concurrent",
            "multiprocessing",
            "_thread",
        }:
            self.add("thread.unmanaged", "unmanaged_threads", module)
        if normalized.split(".", 1)[0] in {"ctypes", "cffi"}:
            self.add("code.native_loading", "native_code", module)
        if normalized.split(".", 1)[0] in {"importlib", "marshal", "pickle"}:
            self.add("python.dynamic_execution", "dynamic_execution", module)
        if "aliuhook" in normalized.lower():
            self.add("hook.direct_aliuhook", "hooks", module)
        elif "xposed" in normalized.lower():
            self.add("hook.direct_xposed", "hooks", module)

    def _call(self, target, node):
        if target is None:
            return
        leaf = target.rsplit(".", 1)[-1]
        if target.startswith("builtins.") and leaf in {
            "eval",
            "exec",
            "compile",
            "__import__",
        }:
            self.add("python.dynamic_execution", "dynamic_execution", target)
            self._dynamic_argument(node, target)
        if target in {
            "importlib.import_module",
            "importlib.__import__",
            "marshal.loads",
            "marshal.load",
            "pickle.loads",
            "pickle.load",
        }:
            self.add("python.dynamic_execution", "dynamic_execution", target)
            self._dynamic_argument(node, target)
        if target == "builtins.getattr" and (
            len(node.args) < 2
            or not isinstance(node.args[1], ast.Constant)
            or not isinstance(node.args[1].value, str)
        ):
            self.add("analysis.dynamic_target", "dynamic_execution", target)
            self.add("analysis.obfuscated_or_dynamic", "dynamic_execution", target)
        if target in {"builtins.open", "io.open"} or target.startswith(
            ("os.", "pathlib.", "shutil.", "sqlite3.")
        ):
            self.add("filesystem.unmanaged", "filesystem", target)
        if target in {"os.system", "os.popen"} or target.startswith(
            ("subprocess.", "pty.", "os.spawn", "os.exec")
        ):
            self.add("process.shell_execution", "process", target)
        if target.startswith(
            ("socket.", "urllib.", "http.", "requests.", "aiohttp.", "websockets.")
        ):
            self.add("network.raw", "network", target)
        if target.startswith(
            ("threading.", "concurrent.", "multiprocessing.", "_thread.")
        ) or (leaf == "Timer" and target.startswith("threading")):
            self.add("thread.unmanaged", "unmanaged_threads", target)
        if target in {"jclass", "java.jclass", "jarray.array", "jarray.zeros"} or target.startswith(
            ("java.", "jarray.")
        ):
            self.add("bridge.java_direct", "java_bridge", target)
            self._dynamic_argument(node, target)
        if target.startswith(("fluxgram_runtime.", "org.telegram.")):
            self._module(target)
        if target.startswith(("ctypes.", "cffi.")):
            self.add("code.native_loading", "native_code", target)
        if "aliuhook" in target.lower():
            self.add("hook.direct_aliuhook", "hooks", target)
        elif "xposed" in target.lower():
            self.add("hook.direct_xposed", "hooks", target)

    def _dynamic_argument(self, node, target):
        if (
            node.args
            and isinstance(node.args[0], ast.Constant)
            and isinstance(node.args[0].value, str)
        ):
            self._module(node.args[0].value)
        else:
            self.add("analysis.dynamic_target", "dynamic_execution", target)
            self.add("analysis.obfuscated_or_dynamic", "dynamic_execution", target)


def _redact_endpoint(value):
    candidate = value.rstrip(".,;:)]}'\"")
    if "://" not in candidate:
        candidate = "//" + candidate
    try:
        parts = urlsplit(candidate)
        host = parts.hostname or ""
        if not host:
            return None
        port = ""
        try:
            if parts.port is not None:
                port = ":" + str(parts.port)
        except ValueError:
            port = ""
        path = parts.path or ""
        value = urlunsplit((parts.scheme, host + port, path, "", ""))
        return value[2:] if candidate.startswith("//") else value
    except (TypeError, ValueError):
        return None


def _decode_source(data):
    if b"\x00" in data:
        raise _UnsupportedError()
    first = data.splitlines()[:2]
    for line in first:
        match = _COOKIE.match(line)
        if match and match.group(1).lower().replace(b"_", b"-") not in {
            b"utf-8",
            b"utf8",
            b"utf-8-sig",
        }:
            raise _UnsupportedError()
    try:
        return data.decode("utf-8-sig")
    except UnicodeDecodeError as error:
        raise _UnsupportedError() from error


def _has_top_level_effect(tree):
    def expression_effect(node):
        class EffectVisitor(ast.NodeVisitor):
            effect = False

            def visit(self, item):
                if self.effect:
                    return None
                return super().visit(item)

            def mark(self, item):
                self.effect = True

            visit_Call = mark
            visit_Await = mark
            visit_Yield = mark
            visit_YieldFrom = mark
            visit_NamedExpr = mark
            visit_ListComp = mark
            visit_SetComp = mark
            visit_DictComp = mark
            visit_GeneratorExp = mark
            visit_Attribute = mark
            visit_Subscript = mark
            visit_BinOp = mark
            visit_BoolOp = mark
            visit_Compare = mark
            visit_UnaryOp = mark
            visit_IfExp = mark
            visit_FormattedValue = mark
            visit_JoinedStr = mark
            visit_Starred = mark

            def visit_Dict(self, item):
                if any(key is None for key in item.keys):
                    self.effect = True
                else:
                    self.generic_visit(item)

            def visit_Lambda(self, item):
                for value in item.args.defaults:
                    self.visit(value)
                for value in item.args.kw_defaults:
                    if value is not None:
                        self.visit(value)

        visitor = EffectVisitor()
        visitor.visit(node)
        return visitor.effect

    def definition_effect(node):
        if node.decorator_list:
            return True
        values = []
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            values.extend(node.args.defaults)
            values.extend(value for value in node.args.kw_defaults if value is not None)
            if node.returns is not None:
                values.append(node.returns)
            values.extend(
                argument.annotation
                for argument in node.args.args
                if argument.annotation is not None
            )
            values.extend(
                argument.annotation
                for argument in node.args.posonlyargs
                if argument.annotation is not None
            )
            values.extend(
                argument.annotation
                for argument in node.args.kwonlyargs
                if argument.annotation is not None
            )
            if node.args.vararg is not None and node.args.vararg.annotation is not None:
                values.append(node.args.vararg.annotation)
            if node.args.kwarg is not None and node.args.kwarg.annotation is not None:
                values.append(node.args.kwarg.annotation)
        return any(expression_effect(value) for value in values)

    for index, node in enumerate(tree.body):
        if isinstance(node, ast.Pass):
            continue
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            return True
        if isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant):
            continue
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            if definition_effect(node):
                return True
            continue
        if isinstance(node, ast.ClassDef):
            return True
        if isinstance(node, (ast.Assign, ast.AnnAssign)):
            targets = node.targets if isinstance(node, ast.Assign) else [node.target]
            if any(
                isinstance(target, (ast.Attribute, ast.Subscript, ast.Tuple, ast.List))
                for target in targets
            ):
                return True
            value = getattr(node, "value", None)
            if isinstance(node, ast.AnnAssign) and expression_effect(node.annotation):
                return True
            if value is not None and expression_effect(value):
                return True
            continue
        return True
    return False


def _inspect_code(code, scanner):
    pending = [code]
    code_objects = 0
    instructions = 0
    constants = 0
    string_bytes = 0
    while pending:
        current = pending.pop()
        code_objects += 1
        if code_objects > MAX_CODE_OBJECTS:
            raise _LimitError()
        for instruction in dis.get_instructions(current):
            instructions += 1
            if instructions > MAX_INSTRUCTIONS:
                raise _LimitError()
        constant_counts = _inspect_constants(current.co_consts, scanner)
        constants += constant_counts[0]
        if constants > MAX_CONSTANTS:
            raise _LimitError()
        string_bytes += constant_counts[1]
        if string_bytes > MAX_STRING_BYTES:
            raise _LimitError()
        pending.extend(value for value in current.co_consts if isinstance(value, CodeType))
    return code_objects, instructions, constants, string_bytes


def _inspect_constants(values, scanner):
    pending = list(values)
    count = 0
    string_bytes = 0
    while pending:
        value = pending.pop()
        count += 1
        if count > MAX_CONSTANTS:
            raise _LimitError()
        if isinstance(value, str):
            string_bytes += len(value.encode("utf-8"))
            for match in _ENDPOINT.finditer(value):
                scanner.add(
                    "network.endpoint_literal",
                    "network",
                    _redact_endpoint(match.group(0)),
                )
        elif isinstance(value, bytes):
            string_bytes += len(value)
        elif isinstance(value, (tuple, frozenset)):
            pending.extend(value)
        if string_bytes > MAX_STRING_BYTES:
            raise _LimitError()
    return count, string_bytes


def _check_tokens(data):
    count = 0
    try:
        for _ in tokenize.tokenize(io.BytesIO(data).readline):
            count += 1
            if count > MAX_TOKENS:
                raise _LimitError()
    except (IndentationError, tokenize.TokenError) as error:
        raise _UnsupportedError() from error


def _tree_nodes_and_depth(tree):
    count = 0
    pending = [(tree, 1)]
    while pending:
        node, depth = pending.pop()
        count += 1
        if count > MAX_AST_NODES or depth > MAX_AST_DEPTH:
            raise _LimitError()
        pending.extend((child, depth + 1) for child in ast.iter_child_nodes(node))
    return count


def analyze(sources):
    if not isinstance(sources, list):
        raise TypeError("Invalid Python risk source list")
    findings = {}
    limitations = set()
    ast_nodes = 0
    constants = 0
    string_bytes = 0
    code_objects = 0
    instructions = 0
    code_constants = 0
    code_string_bytes = 0
    try:
        if len(sources) > MAX_SOURCES:
            raise _LimitError()
        total = 0
        normalized = []
        for record in sources:
            if not isinstance(record, list) or len(record) != 2:
                raise TypeError("Invalid Python risk source record")
            path, data = record
            if not isinstance(path, str) or not isinstance(data, bytes):
                raise TypeError("Invalid Python risk source record")
            if not path or len(path.encode("utf-8")) > 240 or len(data) > MAX_SOURCE_BYTES:
                raise _LimitError()
            total += len(data)
            if total > MAX_TOTAL_SOURCE_BYTES:
                raise _LimitError()
            normalized.append((path, data))
        for path, data in sorted(normalized):
            _check_tokens(data)
            text = _decode_source(data)
            tree = ast.parse(text, filename=path, mode="exec", feature_version=(3, 11))
            scanner = _Analyzer(path)
            scanner.nodes = _tree_nodes_and_depth(tree)
            scanner.visit(tree)
            ast_nodes += scanner.nodes
            constants += scanner.constants
            string_bytes += scanner.string_bytes
            if (
                ast_nodes > MAX_AST_NODES
                or constants > MAX_CONSTANTS
                or string_bytes > MAX_STRING_BYTES
            ):
                raise _LimitError()
            if _has_top_level_effect(tree):
                scanner.add("python.top_level_side_effect", None, path)
            code_counts = _inspect_code(
                compile(tree, path, "exec", dont_inherit=True, optimize=0),
                scanner,
            )
            code_objects += code_counts[0]
            instructions += code_counts[1]
            code_constants += code_counts[2]
            code_string_bytes += code_counts[3]
            if (
                code_objects > MAX_CODE_OBJECTS
                or instructions > MAX_INSTRUCTIONS
                or code_constants > MAX_CONSTANTS
                or code_string_bytes > MAX_STRING_BYTES
            ):
                raise _LimitError()
            findings.update(scanner.findings)
            if len(findings) > MAX_FINDINGS:
                raise _LimitError()
    except (_UnsupportedError, SyntaxError, UnicodeError, ValueError):
        limitations.add("analysis.unsupported_python")
    except (_LimitError, RecursionError):
        limitations.add("analysis.limit_exceeded")
    except MemoryError:
        limitations.add("analysis.incomplete")
    result = {
        "version": PROTOCOL_VERSION,
        "findings": sorted(
            findings.values(),
            key=lambda item: (item["id"], item["detail"] or ""),
        ),
        "limitations": sorted(limitations),
    }
    encoded = json.dumps(result, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
    if len(encoded) > MAX_RESPONSE_CHARS:
        result["findings"] = []
        result["limitations"] = ["analysis.limit_exceeded"]
        encoded = json.dumps(result, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
    return encoded
