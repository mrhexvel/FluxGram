import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = (
    ROOT
    / "TMessagesProj/src/main/python/fluxgram_runtime/_risk_analysis.py"
)


def load_module():
    spec = importlib.util.spec_from_file_location("fluxgram_risk_analysis_test", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


RISK = load_module()


def analyze(source, path="main.py"):
    return json.loads(RISK.analyze([[path, source]]))


def ids(result):
    return {item["id"] for item in result["findings"]}


class PluginPythonRiskAnalysisTest(unittest.TestCase):
    def test_imports_aliases_multiline_and_relative(self):
        result = analyze(
            b"import fluxgram_runtime._bridge as bridge\n"
            b"from java import (jclass as load_class, jarray)\n"
            b"from .internal import helper\n"
            b"from org.telegram.messenger.plugins import ExternalPlugins\n"
        )
        self.assertTrue({
            "python.internal_runtime_import",
            "bridge.java_direct",
            "host.plugin_internal_direct",
            "analysis.dynamic_target",
        }.issubset(ids(result)))

    def test_strings_and_comments_do_not_create_api_findings(self):
        result = analyze(
            b"# import subprocess\n"
            b"text = 'eval open threading org.telegram'\n"
        )
        self.assertFalse({
            "python.dynamic_execution",
            "filesystem.unmanaged",
            "thread.unmanaged",
            "host.telegram_internal_direct",
        } & ids(result))

    def test_direct_dynamic_and_nested_calls(self):
        result = analyze(
            b"import importlib as loader\n"
            b"import subprocess as sp\n"
            b"from pathlib import Path as P\n"
            b"def run(name):\n"
            b"    values = [open(item) for item in (name,)]\n"
            b"    callback = lambda: eval(name)\n"
            b"    loader.import_module(name)\n"
            b"    return sp.Popen(values), P(name), callback\n"
        )
        self.assertTrue({
            "python.dynamic_execution",
            "analysis.dynamic_target",
            "analysis.obfuscated_or_dynamic",
            "filesystem.unmanaged",
            "process.shell_execution",
        }.issubset(ids(result)))

    def test_scope_shadowing_aliases_and_dynamic_targets(self):
        result = analyze(
            b"import os.path\n"
            b"import pickle as serializer\n"
            b"runner = eval\n"
            b"def run(eval, callback, factory, payload):\n"
            b"    eval(payload)\n"
            b"    callback()\n"
            b"    factory().execute()\n"
            b"    runner(payload)\n"
            b"    os.system(payload)\n"
            b"    serializer.loads(payload)\n"
        )
        self.assertTrue({
            "python.dynamic_execution",
            "analysis.dynamic_target",
            "analysis.obfuscated_or_dynamic",
            "process.shell_execution",
        }.issubset(ids(result)))

    def test_definition_binding_order_and_branch_ambiguity(self):
        definition = analyze(
            b"from builtins import eval as run\n"
            b"def run(value=run('payload')):\n"
            b"    return value\n"
        )
        self.assertIn("python.dynamic_execution", ids(definition))

        branch = analyze(
            b"if condition:\n"
            b"    runner = eval\n"
            b"else:\n"
            b"    runner = safe\n"
            b"runner(payload)\n"
        )
        self.assertTrue({
            "analysis.dynamic_target",
            "analysis.obfuscated_or_dynamic",
        }.issubset(ids(branch)))

    def test_comprehension_target_shadows_outer_alias(self):
        result = analyze(
            b"import os as item\n"
            b"values = [item.system() for item in safe_items]\n"
        )
        process_details = {
            item["detail"]
            for item in result["findings"]
            if item["id"] == "process.shell_execution"
        }
        self.assertNotIn("os.system", process_details)

    def test_class_scope_walrus_and_dynamic_bindings(self):
        class_scope = analyze(
            b"from builtins import eval as run\n"
            b"class Plugin:\n"
            b"    run = safe\n"
            b"    def invoke(self, payload):\n"
            b"        run(payload)\n"
        )
        self.assertIn("python.dynamic_execution", ids(class_scope))

        class_comprehension = analyze(
            b"from builtins import eval as run\n"
            b"class Plugin:\n"
            b"    run = safe\n"
            b"    values = [run(item) for item in items]\n"
        )
        self.assertIn("python.dynamic_execution", ids(class_comprehension))

        walrus = analyze(
            b"from builtins import eval as runner\n"
            b"def invoke(items, payload):\n"
            b"    [(runner := item) for item in items]\n"
            b"    runner(payload)\n"
        )
        self.assertIn("analysis.dynamic_target", ids(walrus))

        bindings = analyze(
            b"from safe import callback\n"
            b"try:\n"
            b"    pass\n"
            b"except Exception as callback:\n"
            b"    callback()\n"
            b"with provider() as callback:\n"
            b"    callback()\n"
            b"match value:\n"
            b"    case {'callback': callback}:\n"
            b"        callback()\n"
        )
        self.assertTrue({
            "analysis.dynamic_target",
            "analysis.obfuscated_or_dynamic",
        }.issubset(ids(bindings)))

    def test_network_threads_native_android_and_hooks(self):
        result = analyze(
            b"import socket\n"
            b"import threading as workers\n"
            b"import ctypes\n"
            b"from android.view import View\n"
            b"from de.robv.android.xposed import XposedBridge\n"
            b"def run():\n"
            b"    socket.socket()\n"
            b"    workers.Thread()\n"
            b"    ctypes.CDLL('native.so')\n"
            b"    XposedBridge.hookMethod()\n"
        )
        self.assertTrue({
            "network.raw",
            "thread.unmanaged",
            "code.native_loading",
            "ui.direct_android",
            "hook.direct_xposed",
        }.issubset(ids(result)))

    def test_top_level_indicators(self):
        safe = analyze(
            b"'module text'\n"
            b"VALUE = 3\n"
            b"def plain(value):\n"
            b"    return value\n"
        )
        self.assertNotIn("python.top_level_side_effect", ids(safe))

        cases = [
            b"run()\n",
            b"target.value = 1\n",
            b"with open('x'):\n    pass\n",
            b"for item in values:\n    pass\n",
            b"@decorate()\ndef run():\n    pass\n",
            b"@decorate\ndef run():\n    pass\n",
            b"def run(value=build()):\n    pass\n",
            b"class Active:\n    value = build()\n",
            b"class Marker:\n    pass\n",
            b"import helper\n",
            b"value: build() = 1\n",
            b"value: build()\n",
            b"value = left + right\n",
        ]
        for source in cases:
            with self.subTest(source=source):
                self.assertIn("python.top_level_side_effect", ids(analyze(source)))

        delayed = analyze(b"handler = lambda: open('later')\n")
        self.assertNotIn("python.top_level_side_effect", ids(delayed))

        for source in (
            b"left, right = custom_iterable\n",
            b"values = [*custom_iterable]\n",
            b"values = {**custom_mapping}\n",
        ):
            with self.subTest(source=source):
                self.assertIn("python.top_level_side_effect", ids(analyze(source)))

    def test_endpoint_redaction(self):
        result = analyze(
            b"ENDPOINT = 'https://user:secret@example.com:8443/api?q=token#private'\n"
        )
        details = [
            item["detail"]
            for item in result["findings"]
            if item["id"] == "network.endpoint_literal"
        ]
        self.assertEqual(details, ["https://example.com:8443/api"])

    def test_folded_endpoint_constant(self):
        result = analyze(b"ENDPOINT = 'https://' + 'evil' + '.example/path'\n")
        details = [
            item["detail"]
            for item in result["findings"]
            if item["id"] == "network.endpoint_literal"
        ]
        self.assertEqual(details, ["https://evil.example/path"])

    def test_oversized_detail_fails_closed(self):
        result = analyze(("import fluxgram_runtime." + "a" * 300 + "\n").encode())
        self.assertEqual(result["limitations"], ["analysis.limit_exceeded"])

    def test_utf8_bom_and_invalid_sources(self):
        valid = analyze(b"\xef\xbb\xbfVALUE = '\xd1\x82\xd0\xb5\xd1\x81\xd1\x82'\n")
        self.assertEqual(valid["limitations"], [])

        for source in (
            b"# coding: latin-1\nvalue = '\xe9'\n",
            b"value = '\x00'\n",
            b"def broken(:\n",
        ):
            with self.subTest(source=source):
                self.assertEqual(
                    analyze(source)["limitations"],
                    ["analysis.unsupported_python"],
                )

    def test_node_depth_and_instruction_limits(self):
        old_nodes = RISK.MAX_AST_NODES
        old_depth = RISK.MAX_AST_DEPTH
        old_instructions = RISK.MAX_INSTRUCTIONS
        try:
            RISK.MAX_AST_NODES = 4
            self.assertEqual(
                analyze(b"value = 1\n")["limitations"],
                ["analysis.limit_exceeded"],
            )
            RISK.MAX_AST_NODES = old_nodes
            RISK.MAX_AST_DEPTH = 2
            self.assertEqual(
                analyze(b"value = (1 + 2)\n")["limitations"],
                ["analysis.limit_exceeded"],
            )
            RISK.MAX_AST_DEPTH = old_depth
            RISK.MAX_INSTRUCTIONS = 1
            self.assertEqual(
                analyze(b"value = 1\n")["limitations"],
                ["analysis.limit_exceeded"],
            )
        finally:
            RISK.MAX_AST_NODES = old_nodes
            RISK.MAX_AST_DEPTH = old_depth
            RISK.MAX_INSTRUCTIONS = old_instructions

    def test_folded_bytes_and_response_size_limits(self):
        old_string_bytes = RISK.MAX_STRING_BYTES
        old_response_chars = RISK.MAX_RESPONSE_CHARS
        try:
            RISK.MAX_STRING_BYTES = 16
            self.assertEqual(
                analyze(b"VALUE = b'a' * 64\n")["limitations"],
                ["analysis.limit_exceeded"],
            )
            self.assertEqual(
                analyze(b"VALUE = (b'a' * 64,)\n")["limitations"],
                ["analysis.limit_exceeded"],
            )

            RISK.MAX_STRING_BYTES = old_string_bytes
            RISK.MAX_RESPONSE_CHARS = 64
            self.assertEqual(
                analyze(b"import socket\n")["limitations"],
                ["analysis.limit_exceeded"],
            )
        finally:
            RISK.MAX_STRING_BYTES = old_string_bytes
            RISK.MAX_RESPONSE_CHARS = old_response_chars

    def test_deterministic_and_never_executes_or_mutates_import_state(self):
        with tempfile.TemporaryDirectory(prefix="risk-no-exec-") as folder:
            marker = Path(folder) / "marker"
            source = (
                "import sys\n"
                f"open({str(marker)!r}, 'w').write('executed')\n"
                "sys.path.append('/candidate/path')\n"
            ).encode()
            path_before = list(sys.path)
            modules_before = set(sys.modules)
            first = RISK.analyze([["main.py", source]])
            second = RISK.analyze([["main.py", source]])

            self.assertEqual(first, second)
            self.assertFalse(marker.exists())
            self.assertEqual(sys.path, path_before)
            self.assertEqual(set(sys.modules), modules_before)

    def test_wire_rejects_non_bytes_and_non_list_records(self):
        for value in (
            [("main.py", b"pass")],
            [["main.py", "pass"]],
            {"main.py": b"pass"},
        ):
            with self.subTest(value=value):
                with self.assertRaises(TypeError):
                    RISK.analyze(value)


if __name__ == "__main__":
    unittest.main()
