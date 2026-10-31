import hashlib
import ast
import base64
import io
import json
import os
import re
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest
import warnings
import zipfile
import zlib


ROOT = Path(__file__).resolve().parents[2]
MANIFEST = {
    "schema_version": 2,
    "id": "test.package.fixture",
    "name": "Тестовый плагин",
    "version": "1.0.0",
    "description": "Пакет для проверки установщика",
    "author": "FluxGram",
    "sdk_version": 2,
    "min_sdk_minor": 12,
    "entrypoint": "main:PluginEntry",
    "access": "full",
    "declared_capabilities": [],
}


class PluginPackagesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        candidates = list(
            (
                Path.home()
                / ".gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.11.0"
            ).glob("*/gson-2.11.0.jar")
        )
        gson = os.environ.get("GSON_JAR") or (
            str(candidates[0]) if candidates else None
        )
        if not gson or not shutil.which("javac") or not shutil.which("java"):
            raise unittest.SkipTest("Requires JDK and Gson 2.11.0 (or GSON_JAR)")
        cls.compiled = tempfile.TemporaryDirectory(prefix="fgplugin-classes-")
        cls.addClassCleanup(cls.compiled.cleanup)
        folder = (
            ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/plugins/packages"
        )
        risk = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/plugins/risk"
        signing = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/plugins/signing"
        subprocess.run(
            [
                "javac",
                "-cp",
                gson,
                "-d",
                cls.compiled.name,
                str(folder / "PluginManifest.java"),
                str(folder / "PluginPackageValidator.java"),
                str(folder / "PluginDexValidator.java"),
                str(folder / "PluginPackageStore.java"),
                str(risk / "PluginRiskEvidence.java"),
                str(risk / "PluginPythonRiskScanner.java"),
                str(risk / "PluginPythonRiskResponseDecoder.java"),
                str(risk / "PluginRiskCatalog.java"),
                str(risk / "PluginRiskReport.java"),
                str(risk / "PluginRiskDiff.java"),
                str(risk / "PluginRiskPolicy.java"),
                str(risk / "PluginRiskAnalyzer.java"),
                str(signing / "PluginSignedPayload.java"),
                str(signing / "PluginSignatureEnvelope.java"),
                str(signing / "PluginSignatureVerification.java"),
                str(signing / "PluginSignatureVerifier.java"),
                str(signing / "PluginSignerIdentity.java"),
                str(signing / "PluginKeyRotationProof.java"),
                str(signing / "PluginSignerTransition.java"),
                str(signing / "PluginSignerState.java"),
                str(signing / "PluginSignerJournal.java"),
                str(signing / "PluginSignerReview.java"),
                str(ROOT / "tests/java/FakePluginPythonRiskScanner.java"),
                str(ROOT / "tests/java/PluginStoreCheck.java"),
                str(ROOT / "tests/java/PluginPackageCheck.java"),
                str(ROOT / "tests/java/PluginPythonRiskResponseCheck.java"),
                str(ROOT / "tests/java/PluginArchiveSignerCheck.java"),
                str(ROOT / "tests/java/PluginInstalledPackageCheck.java"),
            ],
            check=True,
            capture_output=True,
        )
        cls.classpath = cls.compiled.name + os.pathsep + gson

    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="fgplugin-fixture-")
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.cache = self.root / "cache"
        self.cache.mkdir()

    def archive(
        self,
        fields=None,
        entries=None,
        raw_manifest=None,
        include_python=True,
        python_source=b'raise RuntimeError("Validation must not execute this")\n',
    ):
        data = io.BytesIO()
        manifest = MANIFEST.copy()
        if fields:
            manifest.update(fields)
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(data, "w", compression=zipfile.ZIP_DEFLATED) as z:
                z.writestr(
                    "manifest.json",
                    raw_manifest if raw_manifest is not None else json.dumps(manifest),
                )
                if include_python:
                    z.writestr(
                        "main.py",
                        python_source,
                    )
                for name, content in entries or []:
                    z.writestr(name, content)
        return data.getvalue()

    def signed_archive(self, fields=None, entries=None, python_source=None):
        signed_fields = {"schema_version": 6}

        if fields:
            signed_fields.update(fields)

        unsigned = self.archive(
            fields=signed_fields,
            entries=entries,
            python_source=(
                python_source
                if python_source is not None
                else b'raise RuntimeError("Validation must not execute this")\n'
            ),
        )
        source = self.root / "unsigned.fgplugin"
        source.write_bytes(unsigned)
        result = subprocess.run(
            ["java", "-cp", self.classpath, "PluginArchiveSignerCheck", str(source)],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        envelope = base64.b64decode(result.stdout.strip(), validate=True)
        output = io.BytesIO()

        with zipfile.ZipFile(io.BytesIO(unsigned)) as archive:
            with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as signed:
                for info in archive.infolist():
                    signed.writestr(info, archive.read(info))

                signed.writestr(".fluxgram-signature", envelope)

        return output.getvalue()

    def mutate_archive(self, data, replacements=None, additions=None, removals=()):
        replacements = replacements or {}
        removals = set(removals)
        output = io.BytesIO()

        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as changed:
                for info in archive.infolist():
                    if info.filename in removals:
                        continue

                    changed.writestr(
                        info,
                        replacements.get(info.filename, archive.read(info)),
                    )

                for name, content in additions or []:
                    changed.writestr(name, content)

        return output.getvalue()

    def repack_archive(self, data):
        output = io.BytesIO()

        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            entries = [(info.filename, archive.read(info)) for info in archive.infolist()]

        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as changed:
            for name, content in reversed(entries):
                changed.writestr(name, content)

        return output.getvalue()

    def validate(self, data, accepted):
        archive = self.root / "example.fgplugin"
        archive.write_bytes(data)
        result = subprocess.run(
            [
                "java",
                "-cp",
                self.classpath,
                "PluginPackageCheck",
                str(archive),
                str(self.cache),
            ],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0 if accepted else 2, result.stderr)
        self.assertEqual(list(self.cache.iterdir()), [], "Temporary archive leaked")
        return result.stdout.splitlines()

    def validate_error(self, data):
        archive = self.root / "invalid.fgplugin"
        archive.write_bytes(data)
        result = subprocess.run(
            [
                "java",
                "-cp",
                self.classpath,
                "PluginPackageCheck",
                str(archive),
                str(self.cache),
            ],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertEqual(list(self.cache.iterdir()), [], "Temporary archive leaked")
        return result.stderr.strip()

    def validate_installed(self, data, accepted=True):
        archive = self.root / "installed.fgplugin"
        archive.write_bytes(data)
        result = subprocess.run(
            [
                "java",
                "-cp",
                self.classpath,
                "org.telegram.messenger.plugins.packages.PluginInstalledPackageCheck",
                str(archive),
                str(self.cache),
            ],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0 if accepted else 2, result.stderr)
        self.assertEqual(list(self.cache.iterdir()), [], "Temporary archive leaked")
        return result.stdout.splitlines() if accepted else result.stderr.strip()

    def decode_python_risk_response(self, value, accepted):
        result = subprocess.run(
            [
                "java",
                "-cp",
                self.classpath,
                "PluginPythonRiskResponseCheck",
                value,
            ],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0 if accepted else 2, result.stderr)
        return result.stdout.splitlines()

    def test_python_risk_response_decoder_is_strict_and_bounded(self):
        valid = json.dumps({
            "version": 1,
            "findings": [{
                "id": "network.raw",
                "category": "network",
                "detail": "socket",
                "confidence": "exact",
            }],
            "limitations": [],
        }, separators=(",", ":"))
        self.assertEqual(self.decode_python_risk_response(valid, True), ["1:0"])

        invalid = [
            '{"version":1,"version":1,"findings":[],"limitations":[]}',
            '{"version":1,"findings":[],"limitations":[],"extra":null}',
            '{"version":1,"findings":[{"id":"network.raw","category":"filesystem","detail":null,"confidence":"exact"}],"limitations":[]}',
            '{"version":1,"findings":[],"limitations":["unknown"]}',
            '{"version":1,"findings":[],"limitations":[]} trailing',
            json.dumps({
                "version": 1,
                "findings": [{
                    "id": "network.raw",
                    "category": "network",
                    "detail": None,
                    "confidence": "exact",
                }] * 65,
                "limitations": [],
            }, separators=(",", ":")),
            json.dumps({
                "version": 1,
                "findings": [],
                "limitations": ["analysis.incomplete"] * 9,
            }, separators=(",", ":")),
            json.dumps({
                "version": 1,
                "findings": [{
                    "id": "network.endpoint_literal",
                    "category": "network",
                    "detail": "a" * 257,
                    "confidence": "exact",
                }],
                "limitations": [],
            }, separators=(",", ":")),
            " " * (64 * 1024 + 1),
        ]
        for value in invalid:
            with self.subTest(value=value):
                self.decode_python_risk_response(value, False)

    def test_source_archive_path_is_reserved(self):
        self.validate(self.archive(entries=[(".fluxgram-source.fgplugin", "fake")]), False)
        self.validate(self.archive(entries=[(".FLUXGRAM-SOURCE.fgplugin/payload", "fake")]), False)
        self.validate(self.archive(entries=[(".fluxgram-risk-report", "fake")]), False)
        self.validate(self.archive(entries=[(".FLUXGRAM-RISK-REPORT/payload", "fake")]), False)
        self.validate(self.archive(entries=[(".fluxgram-signer-state", "fake")]), False)
        self.validate(self.archive(entries=[(".FLUXGRAM-SIGNER-STATE/payload", "fake")]), False)
        self.validate(self.archive(entries=[(".fluxgram-signer-journal", "fake")]), False)

    @staticmethod
    def finalize_dex(data, data_offset, map_entries):
        while len(data) % 4:
            data.append(0)
        map_offset = len(data)
        entries = [(0x0000, 1, 0), *map_entries, (0x1000, 1, map_offset)]
        entries.sort(key=lambda item: item[2])
        data += struct.pack("<I", len(entries))
        for kind, count, offset in entries:
            data += struct.pack("<HHII", kind, 0, count, offset)
        struct.pack_into("<II", data, 104, len(data) - data_offset, data_offset)
        struct.pack_into("<I", data, 52, map_offset)
        struct.pack_into("<I", data, 32, len(data))
        data[12:32] = hashlib.sha1(data[32:]).digest()
        struct.pack_into("<I", data, 8, zlib.adler32(data[12:]) & 0xFFFFFFFF)
        return bytes(data)

    @staticmethod
    def reseal_dex(data):
        data = bytearray(data)
        data[12:32] = hashlib.sha1(data[32:]).digest()
        struct.pack_into("<I", data, 8, zlib.adler32(data[12:]) & 0xFFFFFFFF)
        return bytes(data)

    @classmethod
    def modern_dex(cls, opcode):
        original = cls.risk_dex("Lexample/Module;", "target")
        map_offset = struct.unpack_from("<I", original, 52)[0]
        map_count = struct.unpack_from("<I", original, map_offset)[0]
        maps = []
        for index in range(map_count):
            position = map_offset + 4 + index * 12
            kind, _, count, offset = struct.unpack_from("<HHII", original, position)
            if kind not in {0x0000, 0x1000}:
                maps.append((kind, count, offset))
        data = bytearray(original[:map_offset])
        code = 512
        extra = []

        if opcode == 0xFA:
            units = [0x00FA, 0, 0, 0, 0x000E]
        elif opcode == 0xFC:
            units = [0x00FC, 0, 0, 0x000E]
            while len(data) <= 448:
                data.append(0)
            struct.pack_into("<I", data, 440, 448)
            data[448] = 0
            extra = [(0x0007, 1, 440), (0x2005, 1, 448)]
        elif opcode == 0xFE:
            units = [0x00FE, 0, 0x000E]
            while len(data) < 448:
                data.append(0)
            struct.pack_into("<HHHH", data, 440, 4, 0, 0, 0)
            extra = [(0x0008, 1, 440)]
        elif opcode == 0xFF:
            units = [0x00FF, 0, 0x000E]
        else:
            raise AssertionError(opcode)

        instructions = struct.pack("<" + "H" * len(units), *units)
        required = code + 16 + len(instructions)
        if len(data) < required:
            data.extend(b"\x00" * (required - len(data)))
        struct.pack_into("<I", data, code + 12, len(units))
        data[code + 16:required] = instructions
        maps.extend(extra)
        return cls.finalize_dex(data, struct.unpack_from("<I", data, 108)[0], maps)

    @classmethod
    def dex_fixture(cls, descriptor="Lexample/Module;"):
        encoded = descriptor.encode()
        data = bytearray(152) + bytes([len(encoded)]) + encoded + b"\x00"
        data[:8] = b"dex\n035\x00"
        struct.pack_into("<III", data, 32, 0, 112, 0x12345678)
        struct.pack_into("<II", data, 56, 1, 112)
        struct.pack_into("<II", data, 64, 1, 116)
        struct.pack_into("<II", data, 96, 1, 120)
        struct.pack_into("<I", data, 112, 152)
        return cls.finalize_dex(data, 152, [
            (0x0001, 1, 112),
            (0x0002, 1, 116),
            (0x0006, 1, 120),
            (0x2002, 1, 152),
        ])

    @staticmethod
    def java_fields(data):
        return {
            "schema_version": 3,
            "java_module": {
                "path": "java/module.dex",
                "entrypoint": "example.Module",
                "sha256": hashlib.sha256(data).hexdigest(),
                "api_version": 2,
                "min_api_minor": 0,
            },
        }

    @classmethod
    def risk_dex(
        cls,
        owner,
        method,
        const_string=None,
        native=False,
        oversized_code=False,
        shared_code=False,
    ):
        def uleb(value):
            result = bytearray()
            while True:
                current = value & 0x7F
                value >>= 7
                result.append(current | (0x80 if value else 0))
                if not value:
                    return bytes(result)

        values = ["Lexample/Module;", owner, method, "V"]
        if shared_code:
            values.append(method + "Again")
        if const_string is not None:
            values.append(const_string)
        string_ids = 112
        type_ids = string_ids + len(values) * 4
        proto_ids = type_ids + 12
        method_count = 2 if shared_code else 1
        method_ids = proto_ids + 12
        class_defs = method_ids + method_count * 8
        cursor = class_defs + 32
        offsets = []
        encoded_strings = bytearray()
        for value in values:
            offsets.append(cursor + len(encoded_strings))
            raw = value.encode()
            encoded_strings += uleb(len(value)) + raw + b"\x00"
        class_data = cursor + len(encoded_strings)
        code = 512

        if native:
            encoded_class = b"\x00\x00\x01\x00\x00" + uleb(0x101) + b"\x00"
            instructions = b""
        else:
            encoded_class = b"\x00\x00" + bytes([method_count]) + b"\x00\x00\x01" + uleb(code)
            if shared_code:
                encoded_class += b"\x01\x01" + uleb(code)
            units = []
            if const_string is not None:
                units.extend((0x001A, len(values) - 1))
            units.extend((0x0071, 0, 0, 0x000E))
            instructions = struct.pack("<" + "H" * len(units), *units)

        instruction_count = 262145 if oversized_code else len(instructions) // 2
        length = code + 16 + instruction_count * 2 if not native else class_data + len(encoded_class)
        data = bytearray(length)
        data[:8] = b"dex\n035\x00"
        struct.pack_into("<III", data, 32, 0, 112, 0x12345678)
        struct.pack_into("<II", data, 56, len(values), string_ids)
        struct.pack_into("<II", data, 64, 3, type_ids)
        struct.pack_into("<II", data, 72, 1, proto_ids)
        struct.pack_into("<II", data, 88, method_count, method_ids)
        struct.pack_into("<II", data, 96, 1, class_defs)
        for index, offset in enumerate(offsets):
            struct.pack_into("<I", data, string_ids + index * 4, offset)
        struct.pack_into("<III", data, type_ids, 0, 1, 3)
        struct.pack_into("<III", data, proto_ids, 3, 2, 0)
        struct.pack_into("<HHI", data, method_ids, 1, 0, 2)
        if shared_code:
            struct.pack_into("<HHI", data, method_ids + 8, 1, 0, 4)
        struct.pack_into("<IIIIIIII", data, class_defs, 0, 1, 0xFFFFFFFF, 0, 0, 0, class_data, 0)
        data[cursor:cursor + len(encoded_strings)] = encoded_strings
        data[class_data:class_data + len(encoded_class)] = encoded_class
        if not native:
            struct.pack_into("<HHHHII", data, code, 1, 0, 0, 0, 0, instruction_count)
            if not oversized_code:
                data[code + 16:code + 16 + len(instructions)] = instructions
        maps = [
            (0x0001, len(values), string_ids),
            (0x0002, 3, type_ids),
            (0x0003, 1, proto_ids),
            (0x0005, method_count, method_ids),
            (0x0006, 1, class_defs),
            (0x2002, len(values), cursor),
            (0x2000, 1, class_data),
        ]
        if not native:
            maps.append((0x2001, 1, code))
        return cls.finalize_dex(data, cursor, maps)

    @classmethod
    def count_limit_dex(cls):
        string_count = 65537
        string_ids = 112
        type_ids = string_ids + string_count * 4
        class_defs = type_ids + 4
        descriptor = b"Lexample/Module;"
        string_data = class_defs + 32
        data = bytearray(string_data + 1 + len(descriptor) + 1)
        data[:8] = b"dex\n035\x00"
        struct.pack_into("<III", data, 32, 0, 112, 0x12345678)
        struct.pack_into("<II", data, 56, string_count, string_ids)
        struct.pack_into("<II", data, 64, 1, type_ids)
        struct.pack_into("<II", data, 96, 1, class_defs)
        struct.pack_into("<I", data, string_ids, string_data)
        struct.pack_into("<I", data, type_ids, 0)
        struct.pack_into("<I", data, class_defs, 0)
        data[string_data] = len(descriptor)
        data[string_data + 1:string_data + 1 + len(descriptor)] = descriptor
        return cls.finalize_dex(data, string_data, [
            (0x0001, string_count, string_ids),
            (0x0002, 1, type_ids),
            (0x0006, 1, class_defs),
            (0x2002, 1, string_data),
        ])

    @staticmethod
    def output_values(lines, prefix):
        return [line[len(prefix):] for line in lines if line.startswith(prefix)]

    def test_risk_report_manifest_facts_and_python_digest(self):
        first = self.archive(entries=[
            ("helpers/z.py", b"last"),
            ("helpers/a.py", b"first"),
            ("assets/data", b"ignored"),
        ])
        reordered = self.archive(entries=[
            ("helpers/a.py", b"first"),
            ("helpers/z.py", b"last"),
            ("assets/data", b"ignored"),
        ])
        second = self.archive(entries=[("helpers/b.py", b"first"), ("assets/data", b"ignored")])
        changed = self.archive(entries=[("helpers/a.py", b"second"), ("assets/data", b"ignored")])

        first_output = self.validate(first, True)
        reordered_output = self.validate(reordered, True)
        second_output = self.validate(second, True)
        changed_output = self.validate(changed, True)
        first_modules = self.output_values(first_output, "module:python:")
        reordered_modules = self.output_values(reordered_output, "module:python:")
        second_modules = self.output_values(second_output, "module:python:")
        changed_modules = self.output_values(changed_output, "module:python:")

        expected = hashlib.sha256()
        python_entries = {
            "main.py": b'raise RuntimeError("Validation must not execute this")\n',
            "helpers/a.py": b"first",
            "helpers/z.py": b"last",
        }
        for path, content in sorted(python_entries.items()):
            encoded_path = path.encode()
            expected.update(struct.pack(">I", len(encoded_path)))
            expected.update(encoded_path)
            expected.update(struct.pack(">Q", len(content)))
            expected.update(content)

        self.assertEqual(first_output[4], "2")
        self.assertRegex(first_output[5], r"^[0-9a-f]{64}$")
        self.assertNotEqual(
            first_output[5],
            "f2ff60d61f95bb4c1587fecde7d32978e38bbd8c4e3aec2b8d0a9bcdd7e98e6b",
        )
        self.assertEqual(first_modules, [expected.hexdigest()])
        self.assertEqual(first_modules, reordered_modules)
        self.assertNotEqual(first_modules, second_modules)
        self.assertNotEqual(first_modules, changed_modules)
        evidence = set(self.output_values(first_output, "evidence:"))
        self.assertIn("access.full_process.python:null", evidence)
        self.assertIn("python.top_level.preflight:main.py", evidence)

    def test_python_fake_scanner_merges_hybrid_and_declarations(self):
        data = self.dex_fixture()
        output = self.validate(
            self.archive(
                fields={
                    **self.java_fields(data),
                    "declared_capabilities": ["network", "native_code", "opaque.future"],
                },
                entries=[("java/module.dex", data)],
                python_source=b"FAKE_NETWORK\nFAKE_SIDE_EFFECT\n",
            ),
            True,
        )
        evidence = {value.split(":", 1)[0] for value in self.output_values(output, "evidence:")}
        self.assertIn("network.raw", evidence)
        self.assertIn("python.top_level_side_effect", evidence)
        self.assertIn("declaration.unobserved.native_code", evidence)
        self.assertNotIn("declaration.missing.network", evidence)
        self.assertNotIn("declaration.missing.host_internals", evidence)
        self.assertEqual(self.output_values(output, "scanner-calls:"), ["1"])

    def test_java_only_does_not_invoke_python_scanner(self):
        data = self.dex_fixture()
        java_only = {
            key: value
            for key, value in MANIFEST.items()
            if key not in {"sdk_version", "min_sdk_minor", "entrypoint"}
        }
        java_only.update({
            "schema_version": 4,
            "java_module": self.java_fields(data)["java_module"],
        })
        output = self.validate(
            self.archive(
                raw_manifest=json.dumps(java_only),
                entries=[("java/module.dex", data)],
                include_python=False,
            ),
            True,
        )
        self.assertEqual(self.output_values(output, "scanner-calls:"), ["0"])

    def test_python_scanner_failure_and_source_limits_block(self):
        for source in (b"FAKE_ERROR\n", b"FAKE_PROTOCOL\n", b"FAKE_BLOCK\n"):
            with self.subTest(source=source):
                output = self.validate(self.archive(python_source=source), True)
                self.assertIn("BLOCK", self.output_values(output, "decision:"))

        output = self.validate(
            self.archive(entries=[("large.py", b"x" * (1024 * 1024 + 1))]),
            True,
        )
        self.assertIn("analysis.limit_exceeded", self.output_values(output, "limitation:"))
        self.assertIn("BLOCK", self.output_values(output, "decision:"))

        output = self.validate(
            self.archive(entries=[(f"sources/file{index}.py", b"pass\n") for index in range(128)]),
            True,
        )
        self.assertIn("analysis.limit_exceeded", self.output_values(output, "limitation:"))

    def test_archive_executable_and_native_payload_classification(self):
        entries = [
            ("extra.dex", b"data"),
            ("extra.jar", b"data"),
            ("extra.apk", b"data"),
            ("Extra.class", b"data"),
            ("cache.pyc", b"data"),
            ("cache.pyo", b"data"),
            ("native.bin", b"\x7fELFpayload"),
            ("library.so", b"data"),
        ]
        output = self.validate(self.archive(entries=entries), True)
        evidence = "\n".join(self.output_values(output, "evidence:"))
        self.assertIn("block.archive.undeclared_executable", evidence)
        self.assertIn("block.archive.native_payload_forbidden", evidence)
        self.assertIn("BLOCK", self.output_values(output, "decision:"))

        hidden_dex = self.dex_fixture()
        output = self.validate(self.archive(entries=[("payload.bin", hidden_dex)]), True)
        evidence = "\n".join(self.output_values(output, "evidence:"))
        self.assertIn("block.archive.undeclared_executable", evidence)

        java = self.dex_fixture()
        java_only = {
            key: value
            for key, value in MANIFEST.items()
            if key not in {"sdk_version", "min_sdk_minor", "entrypoint"}
        }
        java_only.update({"schema_version": 4, "java_module": self.java_fields(java)["java_module"]})
        output = self.validate(self.archive(
            raw_manifest=json.dumps(java_only),
            entries=[("java/module.dex", java), ("hidden.py", b"pass")],
            include_python=False,
        ), True)
        self.assertTrue(any(
            value.startswith("block.archive.undeclared_executable:")
            for value in self.output_values(output, "evidence:")
        ))

    def test_dex_direct_and_dynamic_reference_evidence(self):
        direct = self.risk_dex(
            "Lorg/telegram/messenger/MessagesController;",
            "getInstance",
        )
        output = self.validate(
            self.archive(fields=self.java_fields(direct), entries=[("java/module.dex", direct)]),
            True,
        )
        ids = {value.split(":", 1)[0] for value in self.output_values(output, "evidence:")}
        self.assertIn("host.telegram_internal_direct", ids)

        dynamic = self.risk_dex(
            "Ljava/lang/Class;",
            "forName",
            "org.telegram.messenger.MessagesController",
        )
        output = self.validate(
            self.archive(fields=self.java_fields(dynamic), entries=[("java/module.dex", dynamic)]),
            True,
        )
        ids = {value.split(":", 1)[0] for value in self.output_values(output, "evidence:")}
        self.assertIn("reflection.general", ids)
        self.assertIn("analysis.dynamic_target", ids)
        self.assertIn("host.telegram_internal_direct", ids)

    def test_dex_native_declaration_malformed_and_code_budget(self):
        native = self.risk_dex("Lexample/Module;", "nativeEntry", native=True)
        output = self.validate(
            self.archive(fields=self.java_fields(native), entries=[("java/module.dex", native)]),
            True,
        )
        self.assertTrue(any(
            value.startswith("code.native_loading:")
            for value in self.output_values(output, "evidence:")
        ))

        malformed = bytearray(self.risk_dex("Lexample/Module;", "run"))
        class_offset = struct.unpack_from("<I", malformed, 100)[0]
        struct.pack_into("<I", malformed, class_offset + 24, len(malformed) + 1)
        malformed = bytes(malformed)
        output = self.validate(
            self.archive(fields=self.java_fields(malformed), entries=[("java/module.dex", malformed)]),
            True,
        )
        self.assertIn("analysis.incomplete", self.output_values(output, "limitation:"))
        self.assertIn("BLOCK", self.output_values(output, "decision:"))

        oversized = self.risk_dex("Lexample/Module;", "run", oversized_code=True)
        output = self.validate(
            self.archive(fields=self.java_fields(oversized), entries=[("java/module.dex", oversized)]),
            True,
        )
        self.assertIn("analysis.limit_exceeded", self.output_values(output, "limitation:"))
        self.assertIn("BLOCK", self.output_values(output, "decision:"))

        excessive_count = self.count_limit_dex()
        output = self.validate(
            self.archive(
                fields=self.java_fields(excessive_count),
                entries=[("java/module.dex", excessive_count)],
            ),
            True,
        )
        self.assertIn("analysis.limit_exceeded", self.output_values(output, "limitation:"))
        self.assertIn("BLOCK", self.output_values(output, "decision:"))

    def test_dex_container_integrity_and_map_consistency(self):
        original = self.risk_dex("Lexample/Module;", "run")

        bad_checksum = bytearray(original)
        struct.pack_into("<I", bad_checksum, 8, 0)

        bad_signature = bytearray(original)
        bad_signature[12] ^= 1
        struct.pack_into("<I", bad_signature, 8, zlib.adler32(bad_signature[12:]) & 0xFFFFFFFF)

        bad_data = bytearray(original)
        struct.pack_into("<I", bad_data, 104, 1)
        bad_data = self.reseal_dex(bad_data)

        bad_map = bytearray(original)
        map_offset = struct.unpack_from("<I", bad_map, 52)[0]
        map_count = struct.unpack_from("<I", bad_map, map_offset)[0]
        for index in range(map_count):
            position = map_offset + 4 + index * 12
            kind = struct.unpack_from("<H", bad_map, position)[0]
            if kind == 0x0001:
                struct.pack_into("<I", bad_map, position + 4, 1_000)
                break
        bad_map = self.reseal_dex(bad_map)

        unknown_map = bytearray(original)
        map_offset = struct.unpack_from("<I", unknown_map, 52)[0]
        map_count = struct.unpack_from("<I", unknown_map, map_offset)[0]
        for index in range(map_count):
            position = map_offset + 4 + index * 12
            kind = struct.unpack_from("<H", unknown_map, position)[0]
            if kind == 0x2000:
                struct.pack_into("<H", unknown_map, position, 0x7777)
                break
        unknown_map = self.reseal_dex(unknown_map)

        for data in (
            bytes(bad_checksum),
            bytes(bad_signature),
            bad_data,
            bad_map,
            unknown_map,
        ):
            with self.subTest(digest=hashlib.sha256(data).hexdigest()):
                output = self.validate(
                    self.archive(fields=self.java_fields(data), entries=[("java/module.dex", data)]),
                    True,
                )
                self.assertIn("analysis.incomplete", self.output_values(output, "limitation:"))
                self.assertIn("BLOCK", self.output_values(output, "decision:"))

    def test_optional_offsets_require_matching_map_sections(self):
        data = bytearray(self.risk_dex("Lexample/Module;", "run"))
        class_offset = struct.unpack_from("<I", data, 100)[0]
        map_offset = struct.unpack_from("<I", data, 52)[0]
        struct.pack_into("<I", data, class_offset + 20, map_offset)
        struct.pack_into("<I", data, class_offset + 28, map_offset)
        data = self.reseal_dex(data)
        output = self.validate(
            self.archive(fields=self.java_fields(data), entries=[("java/module.dex", data)]),
            True,
        )
        self.assertIn("analysis.incomplete", self.output_values(output, "limitation:"))
        self.assertIn("BLOCK", self.output_values(output, "decision:"))

    def test_modern_dex_opcodes_are_conservative(self):
        for opcode in (0xFA, 0xFC, 0xFE, 0xFF):
            with self.subTest(opcode=hex(opcode)):
                data = self.modern_dex(opcode)
                output = self.validate(
                    self.archive(fields=self.java_fields(data), entries=[("java/module.dex", data)]),
                    True,
                )
                ids = {
                    value.split(":", 1)[0]
                    for value in self.output_values(output, "evidence:")
                }
                self.assertIn("analysis.dynamic_target", ids)
                self.assertIn("analysis.obfuscated_or_dynamic", ids)
                self.assertNotIn("analysis.incomplete", self.output_values(output, "limitation:"))

    def test_shared_code_item_is_scanned_once(self):
        data = self.risk_dex("Lexample/Module;", "run", shared_code=True)
        output = self.validate(
            self.archive(fields=self.java_fields(data), entries=[("java/module.dex", data)]),
            True,
        )
        self.assertNotIn("analysis.incomplete", self.output_values(output, "limitation:"))

    def test_java_module_metadata_and_hash(self):
        data = self.dex_fixture()
        fields = self.java_fields(data)
        self.validate(self.archive(fields=fields, entries=[("java/module.dex", data)]), True)
        self.validate(self.archive(fields=fields), False)
        self.validate(self.archive(fields=fields, entries=[("java/module.dex", data + b"x")]), False)
        self.validate(self.archive(fields={"schema_version": 3}), False)
        fields["schema_version"] = 2
        self.validate(self.archive(fields=fields, entries=[("java/module.dex", data)]), False)

    def test_schema_four_module_compositions(self):
        data = self.dex_fixture()
        java = self.java_fields(data)["java_module"]
        self.validate(self.archive(fields={"schema_version": 4}), True)
        self.validate(self.archive(
            fields={"schema_version": 4, "java_module": java},
            entries=[("java/module.dex", data)],
        ), True)

        java_only = {
            key: value
            for key, value in MANIFEST.items()
            if key not in {"sdk_version", "min_sdk_minor", "entrypoint"}
        }
        java_only.update({"schema_version": 4, "java_module": java})
        self.validate(self.archive(
            raw_manifest=json.dumps(java_only),
            entries=[("java/module.dex", data)],
            include_python=False,
        ), True)

        no_modules = java_only.copy()
        del no_modules["java_module"]
        self.validate(self.archive(
            raw_manifest=json.dumps(no_modules), include_python=False), False)

        for field in ("sdk_version", "min_sdk_minor", "entrypoint"):
            partial = java_only.copy()
            partial[field] = MANIFEST[field]
            self.validate(self.archive(
                raw_manifest=json.dumps(partial),
                entries=[("java/module.dex", data)],
                include_python=False,
            ), False)

    def test_schema_five_requires_matching_host_fingerprint(self):
        data = self.dex_fixture()
        fields = self.java_fields(data)
        fields["schema_version"] = 5
        fields["java_module"]["host_fingerprint"] = (
            "f2ff60d61f95bb4c1587fecde7d32978e38bbd8c4e3aec2b8d0a9bcdd7e98e6b"
        )
        self.validate(self.archive(fields=fields, entries=[("java/module.dex", data)]), True)

        del fields["java_module"]["host_fingerprint"]
        self.validate(self.archive(fields=fields, entries=[("java/module.dex", data)]), False)

        fields["java_module"]["host_fingerprint"] = "0" * 64
        self.validate(self.archive(fields=fields, entries=[("java/module.dex", data)]), False)

        fields["schema_version"] = 4
        self.validate(self.archive(fields=fields, entries=[("java/module.dex", data)]), False)

    def test_schema_six_signature_is_verified_before_compatibility(self):
        signed = self.signed_archive()
        self.assertIn("incompatible with schema 5", self.validate_error(signed))

        installed = self.validate_installed(signed)
        self.assertIn("signature:VERIFIED", installed)
        self.assertIn("plugin:test.package.fixture", installed)
        key = next(value for value in installed if value.startswith("key:"))
        payload = next(value for value in installed if value.startswith("payload:"))
        self.assertRegex(key, r"key:p256:[a-f0-9]{64}")
        self.assertRegex(payload, r"payload:[a-f0-9]{64}")

    def test_legacy_package_reports_missing_signature(self):
        output = self.validate(self.archive(), True)
        self.assertIn("signature:MISSING_SIGNATURE", output)

    def test_schema_six_requires_signature_and_future_schema_is_rejected(self):
        missing = self.archive(fields={"schema_version": 6})
        self.assertIn("requires a signature envelope", self.validate_error(missing))
        self.assertIn(
            "Unsupported schema_version",
            self.validate_error(self.archive(fields={"schema_version": 7})),
        )

    def test_legacy_schema_cannot_smuggle_signature_envelope(self):
        signed = self.signed_archive(fields={"schema_version": 4})
        self.assertIn(
            "Signature envelope requires manifest schema 6",
            self.validate_error(signed),
        )
        self.assertIn(
            "Reserved package path",
            self.validate_error(self.archive(entries=[(".fluxgram-signature/data", b"x")])),
        )
        self.assertIn(
            "Reserved package path",
            self.validate_error(self.archive(entries=[(".FLUXGRAM-SIGNATURE", b"x")])),
        )

    def test_signed_archive_detects_payload_mutations(self):
        source = b"value = 1\n"
        signed = self.signed_archive(python_source=source)
        mutations = [
            self.mutate_archive(signed, replacements={"main.py": b"value = 2\n"}),
            self.mutate_archive(signed, additions=[("assets/data.txt", b"data")]),
            self.mutate_archive(signed, removals={"main.py"}),
        ]

        with zipfile.ZipFile(io.BytesIO(signed)) as archive:
            manifest = json.loads(archive.read("manifest.json"))
        manifest["version"] = "1.0.1"
        mutations.append(self.mutate_archive(
            signed,
            replacements={"manifest.json": json.dumps(manifest).encode()},
        ))

        for changed in mutations:
            with self.subTest(digest=hashlib.sha256(changed).hexdigest()):
                self.assertIn(
                    "Plugin signature verification failed",
                    self.validate_installed(changed, accepted=False),
                )

    def test_signed_archive_ignores_zip_order_compression_and_timestamp(self):
        signed = self.signed_archive(entries=[("assets/data.txt", b"data")])
        repacked = self.repack_archive(signed)
        self.assertNotEqual(hashlib.sha256(signed).digest(), hashlib.sha256(repacked).digest())
        self.assertIn("signature:VERIFIED", self.validate_installed(repacked))

    def test_signed_archive_detects_directory_file_type_change(self):
        signed = self.signed_archive(entries=[("assets/", b"")])
        changed = self.mutate_archive(
            signed,
            additions=[("assets", b"")],
            removals={"assets/"},
        )
        self.assertIn(
            "Plugin signature verification failed",
            self.validate_installed(changed, accepted=False),
        )

    def test_signed_archive_rejects_malformed_envelope(self):
        signed = self.signed_archive()
        malformed = self.mutate_archive(
            signed,
            replacements={".fluxgram-signature": b"invalid"},
        )
        self.assertIn(
            "MALFORMED_ENVELOPE",
            self.validate_installed(malformed, accepted=False),
        )
        oversized = self.mutate_archive(
            signed,
            replacements={".fluxgram-signature": b"x" * 513},
        )
        self.assertIn("Oversized or invalid file", self.validate_error(oversized))

    def test_java_module_compatibility_and_strict_fields(self):
        data = self.dex_fixture()

        for minor in range(1):
            fields = self.java_fields(data)
            fields["java_module"]["min_api_minor"] = minor
            self.validate(self.archive(fields=fields, entries=[("java/module.dex", data)]), True)

        for key, value in (
            ("api_version", 1), ("min_api_minor", 2), ("min_api_minor", -1),
            ("path", "../module.dex"), ("path", "module.jar"),
            ("entrypoint", "example.Missing"), ("entrypoint", "bad/class"),
            ("sha256", "abc"), ("unknown", "value"),
        ):
            with self.subTest(key=key, value=value):
                fields = self.java_fields(data)
                fields["java_module"][key] = value
                self.validate(self.archive(fields=fields, entries=[("java/module.dex", data)]), False)

        for key in self.java_fields(data)["java_module"]:
            fields = self.java_fields(data)
            del fields["java_module"][key]
            self.validate(self.archive(fields=fields, entries=[("java/module.dex", data)]), False)

        manifest = {**MANIFEST, **self.java_fields(data)}
        raw = json.dumps(manifest).replace('"api_version": 2', '"api_version": 2, "api_version": 2')
        self.validate(self.archive(raw_manifest=raw, entries=[("java/module.dex", data)]), False)

    def test_dex_structure_and_host_class_rejection(self):
        malformed = [
            b"not dex",
            self.dex_fixture("Lorg/telegram/messenger/plugins/api/Copy;"),
            self.dex_fixture("Lorg/fluxgram/Copy;"),
        ]

        for offset in (32, 36, 40, 60, 68, 100, 112, 116, 120):
            data = bytearray(self.dex_fixture())
            struct.pack_into("<I", data, offset, 0x7FFFFFFF)
            malformed.append(bytes(data))

        malformed.append(self.dex_fixture()[:-1])

        for data in malformed:
            with self.subTest(size=len(data), digest=hashlib.sha256(data).hexdigest()):
                self.validate(self.archive(fields=self.java_fields(data), entries=[("java/module.dex", data)]), False)

    def png(self, width=2, height=2, animated=False):
        def chunk(kind, payload):
            return (
                struct.pack(">I", len(payload)) + kind + payload
                + struct.pack(">I", zlib.crc32(kind + payload))
            )

        header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
        pixels = zlib.compress((b"\x00" + b"\x40\x80\xff\xff" * width) * height)
        animation = chunk(b"acTL", struct.pack(">II", 1, 0)) if animated else b""
        return (
            b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + animation
            + chunk(b"IDAT", pixels) + chunk(b"IEND", b"")
        )

    def test_optional_png_icon(self):
        self.validate(self.archive(fields={"icon": "assets/icon.png"},
                                   entries=[("assets/icon.png", self.png())]), True)
        self.validate(self.archive(), True)

    def test_invalid_icon_paths_and_missing_file(self):
        for path in ("../icon.png", "/icon.png", "https://host/icon.png", "icon.jpg", "assets/", "icon.png"):
            with self.subTest(path=path):
                self.validate(self.archive(fields={"icon": path}), False)

    def test_invalid_or_animated_png(self):
        for data in (b"not an image", self.png(1025, 1), self.png(animated=True),
                     self.png()[:-4], self.png() + b"extra", b"x" * (512 * 1024 + 1)):
            with self.subTest(size=len(data)):
                self.validate(self.archive(fields={"icon": "icon.png"},
                                           entries=[("icon.png", data)]), False)

    def test_png_checksum_is_checked(self):
        data = bytearray(self.png())
        data[20] ^= 1
        self.validate(self.archive(fields={"icon": "icon.png"}, entries=[("icon.png", data)]), False)

    def test_webp_icon_and_animation_rejection(self):
        import base64

        data = base64.b64decode("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==")
        self.validate(self.archive(fields={"icon": "icon.webp"}, entries=[("icon.webp", data)]), True)
        animated = data + b"ANIM" + struct.pack("<I", 6) + bytes(6)
        animated = animated[:4] + struct.pack("<I", len(animated) - 8) + animated[8:]
        self.validate(self.archive(fields={"icon": "icon.webp"}, entries=[("icon.webp", animated)]), False)
        self.validate(self.archive(fields={"icon": "icon.png"}, entries=[("icon.png", data)]), False)

    def test_valid_archive_and_digest(self):
        data = self.archive(entries=[("assets/icon.txt", b"asset")])
        result = self.validate(data, True)
        self.assertEqual(
            result[:3], [MANIFEST["id"], hashlib.sha256(data).hexdigest(), "3"]
        )

    def test_sdk_version_matches_python(self):
        tree = ast.parse(
            (ROOT / "sdk/python/src/fluxgram/__init__.py").read_text()
        )
        values = {
            target.id: node.value.value
            for node in tree.body
            if isinstance(node, ast.Assign)
            and isinstance(node.value, ast.Constant)
            for target in node.targets
            if isinstance(target, ast.Name)
        }
        java = (
            ROOT
            / "TMessagesProj/src/main/java/org/telegram/messenger/plugins/packages/PluginManifest.java"
        ).read_text()
        self.assertEqual(
            values["API_VERSION"],
            int(re.search(r"SDK_VERSION = (\d+)", java).group(1)),
        )
        self.assertEqual(
            values["API_MINOR_VERSION"],
            int(re.search(r"SDK_MINOR_VERSION = (\d+)", java).group(1)),
        )
        self.assertEqual(values["API_VERSION"], MANIFEST["sdk_version"])
        self.assertEqual(values["API_MINOR_VERSION"], MANIFEST["min_sdk_minor"])

    def test_utf8_metadata(self):
        self.validate(
            self.archive(
                raw_manifest=json.dumps(MANIFEST, ensure_ascii=False).encode()
            ),
            True,
        )

    def test_unsafe_paths(self):
        for path in (
            "../escape.py",
            "/absolute.py",
            "a/../../b",
            "C:/file",
            "a\\b",
            "a//b",
            "./a",
            "a/./b",
            "name.",
            "fluxgram/__init__.py",
            "fluxgram.py",
        ):
            with self.subTest(path=path):
                self.validate(self.archive(entries=[(path, b"x")]), False)

    def test_duplicates_and_file_directory_conflicts(self):
        for entries in (
            [("main.py", b"x")],
            [("MAIN.py", b"x")],
            [("a", b"x"), ("a/b", b"x")],
            [("A/x", b"x"), ("a/y", b"x")],
        ):
            with self.subTest(entries=entries):
                self.validate(self.archive(entries=entries), False)

    def test_symbolic_link(self):
        link = zipfile.ZipInfo("link")
        link.create_system = 3
        link.external_attr = 0o120777 << 16
        self.validate(self.archive(entries=[(link, b"../target")]), False)

    def test_manifest_contract(self):
        for fields in (
            {"id": "fluxgram.impersonation"},
            {"id": "../escape"},
            {"sdk_version": 3},
            {"min_sdk_minor": 13},
            {"schema_version": "2"},
            {"access": "restricted"},
            {"entrypoint": "missing:PluginEntry"},
            {"entrypoint": "../main:PluginEntry"},
            {"version": "1.0"},
            {"version": "01.0.0"},
            {"permissions": ["network"]},
            {"name": " "},
            {"declared_capabilities": ["a", "a"]},
            {"declared_capabilities": [1]},
        ):
            with self.subTest(fields=fields):
                self.validate(self.archive(fields=fields), False)

        without_minor = MANIFEST.copy()
        del without_minor["min_sdk_minor"]
        self.validate(self.archive(raw_manifest=json.dumps(without_minor)), False)

        previous_minor = MANIFEST.copy()
        previous_minor["min_sdk_minor"] = 0
        self.validate(self.archive(raw_manifest=json.dumps(previous_minor)), True)

        legacy = MANIFEST.copy()
        legacy["schema_version"] = 1
        legacy["sdk_version"] = 1
        del legacy["min_sdk_minor"]
        self.validate(self.archive(raw_manifest=json.dumps(legacy)), False)

    def test_missing_manifest_field(self):
        manifest = MANIFEST.copy()
        del manifest["author"]
        self.validate(self.archive(raw_manifest=json.dumps(manifest)), False)

    def test_duplicate_json_key_and_invalid_json(self):
        valid = json.dumps(MANIFEST)
        for value in (
            valid[:-1] + ', "id": "example.other"}',
            valid + "{}",
            valid.replace('"schema_version"', "'schema_version'"),
            b"\xff",
            valid[:-1] + ",}",
        ):
            with self.subTest(value=value):
                self.validate(self.archive(raw_manifest=value), False)

    def test_oversized_manifest(self):
        self.validate(self.archive(raw_manifest=b" " * (64 * 1024 + 1)), False)

    def test_oversized_entry(self):
        self.validate(
            self.archive(entries=[("large", b"x" * (8 * 1024 * 1024 + 1))]), False
        )

    def test_total_expansion_limit(self):
        self.validate(
            self.archive(
                entries=[(f"file{i}", b"x" * (7 * 1024 * 1024)) for i in range(5)]
            ),
            False,
        )

    def test_archive_size_limit(self):
        self.validate(b"x" * (8 * 1024 * 1024 + 1), False)

    def test_entry_count_limit(self):
        self.validate(
            self.archive(entries=[(f"file{i}", b"") for i in range(511)]), False
        )

    def test_encryption_flag(self):
        data = bytearray(self.archive())
        local = data.index(b"PK\x03\x04")
        central = data.index(b"PK\x01\x02")
        struct.pack_into("<H", data, local + 6, 1)
        struct.pack_into("<H", data, central + 8, 1)
        self.validate(data, False)

    def test_bad_crc_and_truncation(self):
        original = self.archive()
        data = bytearray(original)
        central = data.index(b"PK\x01\x02")
        struct.pack_into("<I", data, central + 16, 0)
        self.validate(data, False)
        self.validate(original[:-10], False)

    def test_missing_root_manifest(self):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as z:
            z.writestr("plugin/manifest.json", json.dumps(MANIFEST))
            z.writestr("main.py", "")
        self.validate(data.getvalue(), False)


if __name__ == "__main__":
    unittest.main()


class PluginStoreTest(unittest.TestCase):
    setUpClass = classmethod(PluginPackagesTest.setUpClass.__func__)
    setUp = PluginPackagesTest.setUp
    archive = PluginPackagesTest.archive
    signed_archive = PluginPackagesTest.signed_archive

    def test_schema_six_cannot_publish_before_signer_state_is_durable(self):
        signed = self.signed_archive()
        self.assertEqual(self.store("install", signed, accepted=False), [])
        self.assertEqual(self.store("list"), [])
        self.assertEqual(list((self.root / "installed").iterdir()), [])

    def test_block_report_prevents_install_update_and_debug_reload(self):
        python_blocked = self.archive(python_source=b"FAKE_BLOCK\n")
        self.assertEqual(self.store("installhook", python_blocked, accepted=False), [])
        self.assertEqual(list((self.root / "installed").iterdir()), [])

        blocked = self.archive(entries=[("extra.dex", b"payload")])
        self.assertEqual(self.store("installhook", blocked, accepted=False), [])
        self.assertEqual(self.store("list"), [])
        self.assertEqual(list((self.root / "installed").iterdir()), [])

        malformed_dex = bytearray(PluginPackagesTest.risk_dex("Lexample/Module;", "run"))
        struct.pack_into("<I", malformed_dex, 8, 0)
        malformed_dex = bytes(malformed_dex)
        malformed_package = self.archive(
            fields=PluginPackagesTest.java_fields(malformed_dex),
            entries=[("java/module.dex", malformed_dex)],
        )
        self.store("install", malformed_package, accepted=False)
        self.assertEqual(self.store("list"), [])
        self.assertEqual(list((self.root / "installed").iterdir()), [])

        self.store("install", self.archive())
        blocked_update = self.archive(
            fields={"version": "2.0.0"},
            entries=[("extra.dex", b"payload")],
        )
        self.store(
            "update",
            blocked_update,
            accepted=False,
            extra=(MANIFEST["id"], MANIFEST["version"], "false"),
        )
        self.assertEqual(self.store("pending"), [])
        self.assertEqual(self.installed_version(), MANIFEST["version"])

        blocked_reload = self.archive(entries=[("extra.dex", b"payload")])
        self.store(
            "debugreload",
            blocked_reload,
            accepted=False,
            extra=(MANIFEST["id"], MANIFEST["version"], "true"),
        )
        self.assertEqual(self.store("pending"), [])
        self.assertEqual(self.installed_version(), MANIFEST["version"])

    def test_confirm_report_remains_installable(self):
        data = PluginPackagesTest.risk_dex(
            "Lorg/telegram/messenger/MessagesController;",
            "getInstance",
        )
        package = self.archive(
            fields=PluginPackagesTest.java_fields(data),
            entries=[("java/module.dex", data)],
        )
        self.store("install", package)
        self.assertEqual(self.store("list"), [MANIFEST["id"]])

    def test_java_only_install_export_and_update(self):
        data = PluginPackagesTest.dex_fixture()
        java_only = {
            key: value
            for key, value in MANIFEST.items()
            if key not in {"sdk_version", "min_sdk_minor", "entrypoint"}
        }
        java_only.update({
            "schema_version": 4,
            "java_module": PluginPackagesTest.java_fields(data)["java_module"],
        })
        package = self.archive(
            raw_manifest=json.dumps(java_only),
            entries=[("java/module.dex", data)],
            include_python=False,
        )
        self.store("install", package)
        self.assertEqual(self.store("listcompat"), [f'{MANIFEST["id"]}:true:-1:-1'])
        exported = self.store("export", extra=(MANIFEST["id"], str(self.root / "shares")))
        self.assertEqual(Path(exported[0]).read_bytes(), package)

        java_only["version"] = "2.0.0"
        update = self.archive(
            raw_manifest=json.dumps(java_only),
            entries=[("java/module.dex", data)],
            include_python=False,
        )
        self.store("update", update, extra=(MANIFEST["id"], "1.0.0", "true", "true"))
        self.store("apply")
        installed = self.root / "installed" / MANIFEST["id"]
        self.assertEqual(json.loads((installed / "manifest.json").read_text())["version"], "2.0.0")
        self.assertFalse((installed / "main.py").exists())

    def test_java_module_restart_update_and_rollback(self):
        data = PluginPackagesTest.dex_fixture()
        fields = PluginPackagesTest.java_fields(data)
        base = self.archive(fields=fields, entries=[("java/module.dex", data)])
        self.store("install", base)
        fields["version"] = "2.0.0"
        update = self.archive(fields=fields, entries=[("java/module.dex", data)])
        self.store("update", update, extra=(MANIFEST["id"], "1.0.0", "true", "false"))
        installed = self.root / "installed" / MANIFEST["id"]
        self.assertEqual(json.loads((installed / "manifest.json").read_text())["version"], "1.0.0")
        self.store("activate", extra=(MANIFEST["id"],))
        self.assertEqual(json.loads((installed / "manifest.json").read_text())["version"], "2.0.0")
        self.store("rollback", extra=(MANIFEST["id"],))
        self.assertEqual(json.loads((installed / "manifest.json").read_text())["version"], "1.0.0")
        self.assertEqual((installed / "java/module.dex").read_bytes(), data)

    def test_installed_incompatible_java_api_remains_visible(self):
        data = PluginPackagesTest.dex_fixture()
        fields = PluginPackagesTest.java_fields(data)
        fields["java_module"]["api_version"] = 1
        manifest = {**MANIFEST, **fields}
        installed = self.root / "installed" / MANIFEST["id"]
        (installed / "java").mkdir(parents=True)
        (installed / "manifest.json").write_text(json.dumps(manifest))
        (installed / "main.py").write_text("raise RuntimeError('not executed')")
        (installed / "java/module.dex").write_bytes(data)
        archive = self.archive(fields=fields, entries=[("java/module.dex", data)])
        (installed / ".fluxgram-source.fgplugin").write_bytes(archive)
        self.assertEqual(self.store("listcompat"), [f'{MANIFEST["id"]}:false:2:12'])
        output = self.store("export", extra=(MANIFEST["id"], str(self.root / "shares")))
        self.assertEqual(Path(output[0]).read_bytes(), archive)

    def store(self, action, data=None, expected_hash=None, accepted=True, extra=()):
        args = [
            "java",
            "-cp",
            self.classpath,
            "PluginStoreCheck",
            action,
            str(self.root / "installed"),
            str(self.cache),
        ]
        if data is not None:
            archive = self.root / "example.fgplugin"
            archive.write_bytes(data)
            args += [str(archive), expected_hash or hashlib.sha256(data).hexdigest()]
        args += list(extra)
        result = subprocess.run(args, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0 if accepted else 2, result.stderr)
        self.assertEqual(list(self.cache.iterdir()), [], "Installer cache leak")
        return result.stdout.splitlines()

    def test_install_survives_reopen_and_does_not_execute_python(self):
        data = self.archive(entries=[("helpers/__init__.py", "raise RuntimeError()")])
        self.assertEqual(self.store("install", data), [MANIFEST["id"]])
        self.assertEqual(self.store("list"), [MANIFEST["id"]])
        self.assertTrue(
            (self.root / "installed" / MANIFEST["id"] / "helpers/__init__.py").is_file()
        )
        report = self.root / "installed" / MANIFEST["id"] / ".fluxgram-risk-report"
        self.assertTrue(report.is_file())
        self.assertEqual(self.store("risk", extra=(MANIFEST["id"],)), [hashlib.sha256(data).hexdigest()])

    def test_damaged_persisted_report_does_not_hide_installation(self):
        self.store("install", self.archive())
        report = self.root / "installed" / MANIFEST["id"] / ".fluxgram-risk-report"
        report.write_bytes(b"damaged")
        self.assertEqual(self.store("list"), [MANIFEST["id"]])
        self.assertEqual(self.store("risk", extra=(MANIFEST["id"],)), ["legacy"])

    def test_foreign_valid_report_is_not_exposed(self):
        first_id = MANIFEST["id"]
        second_id = "test.foreign_fixture"
        self.store("install", self.archive())
        self.store("install", self.archive(fields={"id": second_id, "name": "Foreign"}))
        root = self.root / "installed"
        shutil.copyfile(
            root / second_id / ".fluxgram-risk-report",
            root / first_id / ".fluxgram-risk-report",
        )

        self.assertEqual(self.store("risk", extra=(first_id,)), ["legacy"])
        self.assertCountEqual(self.store("list"), [first_id, second_id])

    def test_pending_update_persists_risk_report(self):
        self.store("install", self.archive())
        update = self.archive(fields={"version": "2.0.0"})
        self.store(
            "update",
            update,
            extra=(MANIFEST["id"], MANIFEST["version"], "false"),
        )
        report = (
            self.root / "installed" / (".pending-" + MANIFEST["id"])
            / "package" / ".fluxgram-risk-report"
        )
        self.assertTrue(report.is_file())
        self.store("activate", extra=(MANIFEST["id"],))
        self.assertEqual(
            self.store("risk", extra=(MANIFEST["id"],)),
            [hashlib.sha256(update).hexdigest()],
        )

    def test_pending_update_is_rescanned_before_activation(self):
        original = self.archive()
        update = self.archive(fields={"version": "2.0.0"})
        self.store("install", original)
        self.store(
            "update",
            update,
            extra=(MANIFEST["id"], MANIFEST["version"], "false"),
        )
        root = self.root / "installed"
        pending = root / (".pending-" + MANIFEST["id"]) / "package"
        changed = self.archive(
            fields={"version": "2.0.0"},
            python_source=b"FAKE_BLOCK\n",
        )
        (pending / ".fluxgram-source.fgplugin").write_bytes(changed)

        self.store("activate", accepted=False, extra=(MANIFEST["id"],))

        self.assertEqual(self.installed_version(), MANIFEST["version"])
        self.assertTrue(pending.is_dir())
        self.assertFalse((root / (".backup-" + MANIFEST["id"])).exists())

    def test_pending_extracted_files_must_match_rescanned_archive(self):
        self.store("install", self.archive())
        update = self.archive(fields={"version": "2.0.0"})
        self.store(
            "update",
            update,
            extra=(MANIFEST["id"], MANIFEST["version"], "false"),
        )
        root = self.root / "installed"
        pending = root / (".pending-" + MANIFEST["id"]) / "package"
        (pending / "main.py").write_text("MUTATED = True\n")

        self.store("activate", accepted=False, extra=(MANIFEST["id"],))

        self.assertEqual(self.installed_version(), MANIFEST["version"])
        self.assertTrue(pending.is_dir())
        self.assertFalse((root / (".backup-" + MANIFEST["id"])).exists())

    def test_pending_extracted_files_reject_injected_file(self):
        self.store("install", self.archive())
        update = self.archive(fields={"version": "2.0.0"})
        self.store(
            "update",
            update,
            extra=(MANIFEST["id"], MANIFEST["version"], "false"),
        )
        root = self.root / "installed"
        pending = root / (".pending-" + MANIFEST["id"]) / "package"
        (pending / "injected.py").write_text("INJECTED = True\n")

        self.store("activate", accepted=False, extra=(MANIFEST["id"],))

        self.assertEqual(self.installed_version(), MANIFEST["version"])
        self.assertTrue(pending.is_dir())
        self.assertFalse((root / (".backup-" + MANIFEST["id"])).exists())

    def test_pending_extracted_files_reject_symlink(self):
        self.store("install", self.archive())
        update = self.archive(fields={"version": "2.0.0"})
        self.store(
            "update",
            update,
            extra=(MANIFEST["id"], MANIFEST["version"], "false"),
        )
        root = self.root / "installed"
        pending = root / (".pending-" + MANIFEST["id"]) / "package"
        outside = self.root / "outside.py"
        outside.write_text("OUTSIDE = True\n")
        (pending / "main.py").unlink()
        (pending / "main.py").symlink_to(outside)

        self.store("activate", accepted=False, extra=(MANIFEST["id"],))

        self.assertEqual(self.installed_version(), MANIFEST["version"])
        self.assertTrue(pending.is_dir())
        self.assertFalse((root / (".backup-" + MANIFEST["id"])).exists())

    def test_interrupted_risk_report_publish_is_recovered(self):
        package = self.archive()
        self.store("install", package)
        installed = self.root / "installed" / MANIFEST["id"]
        report = installed / ".fluxgram-risk-report"
        previous = installed / ".fluxgram-risk-report.previous"
        temporary = installed / ".fluxgram-risk-report.temporary"
        report.rename(previous)
        temporary.write_bytes(b"incomplete")

        self.assertEqual(
            self.store("risk", extra=(MANIFEST["id"],)),
            [hashlib.sha256(package).hexdigest()],
        )
        self.assertTrue(report.is_file())
        self.assertFalse(previous.exists())
        self.assertFalse(temporary.exists())

    def test_legacy_installation_remains_visible_and_exportable(self):
        legacy = MANIFEST.copy()
        legacy["schema_version"] = 1
        legacy["sdk_version"] = 1
        del legacy["min_sdk_minor"]
        data = self.archive(raw_manifest=json.dumps(legacy))
        installed = self.root / "installed" / legacy["id"]
        installed.mkdir(parents=True)
        (installed / "manifest.json").write_text(json.dumps(legacy))
        (installed / "main.py").write_text("class PluginEntry: pass\n")
        (installed / ".fluxgram-source.fgplugin").write_bytes(data)

        self.assertEqual(
            self.store("listcompat"),
            [f'{legacy["id"]}:false:1:0'],
        )
        self.assertEqual(self.store("risk", extra=(legacy["id"],)), ["legacy"])
        output = self.store(
            "export", extra=(legacy["id"], str(self.root / "shares"))
        )
        self.assertEqual(Path(output[0]).read_bytes(), data)

        self.store(
            "update",
            self.archive(fields={"version": "2.0.0"}),
            extra=(legacy["id"], legacy["version"], "false"),
        )
        self.store("apply")
        self.assertEqual(
            self.store("listcompat"),
            [f'{legacy["id"]}:true:2:12'],
        )

    def test_install_review_uses_persisted_baseline_for_updates(self):
        sheet_source = (
            ROOT
            / "TMessagesProj/src/main/java/org/telegram/ui/PluginInstallSheet.java"
        ).read_text()
        loader_source = (
            ROOT
            / "TMessagesProj/src/main/java/org/telegram/messenger/plugins/ExternalPlugins.java"
        ).read_text()

        self.assertIn(
            "boolean update = review.installedVersion != null;",
            sheet_source,
        )
        self.assertIn(
            "store.find(result.manifest.id)",
            loader_source,
        )

    def test_install_sheet_enforces_risk_decisions(self):
        source = (
            ROOT
            / "TMessagesProj/src/main/java/org/telegram/ui/PluginInstallSheet.java"
        ).read_text()

        self.assertIn(
            "review.riskPolicy.decision == PluginRiskPolicy.Decision.BLOCK",
            source,
        )
        self.assertIn(
            "review.riskPolicy.decision == PluginRiskPolicy.Decision.CONFIRM",
            source,
        )
        self.assertIn(
            "if (!active[0] || installing[0] || !canInstall || !isPresentationUsable())",
            source,
        )

    def test_remove_without_restart_and_reinstall(self):
        data = self.archive()
        self.store("install", data)
        self.assertEqual(self.store("remove", extra=(MANIFEST["id"],)), [])
        self.assertEqual(self.store("pending"), [])
        self.assertFalse((self.root / "installed" / MANIFEST["id"]).exists())
        self.assertEqual(self.store("install", data), [MANIFEST["id"]])

    def test_remove_plugin_with_damaged_manifest(self):
        self.store("install", self.archive())
        (self.root / "installed" / MANIFEST["id"] / "manifest.json").write_text("broken")
        self.assertEqual(self.store("remove", extra=(MANIFEST["id"],)), [])

    def test_remove_does_not_apply_another_pending_update(self):
        self.store("install", self.archive())
        self.store("install", self.archive(fields={"id": "example.other"}))
        self.store("update", self.archive(fields={"id": "example.other", "version": "2.0.0"}),
                   extra=("example.other", MANIFEST["version"], "false"))
        self.store("remove", extra=(MANIFEST["id"],))
        self.assertEqual(self.store("pending"), ["example.other:false:false"])
        manifest = json.loads((self.root / "installed" / "example.other" / "manifest.json").read_text())
        self.assertEqual(manifest["version"], MANIFEST["version"])

    def test_remove_rejects_pending_update(self):
        self.store("install", self.archive())
        self.store("update", self.archive(fields={"version": "2.0.0"}),
                   extra=(MANIFEST["id"], MANIFEST["version"], "false"))
        self.store("remove", accepted=False, extra=(MANIFEST["id"],))
        self.assertEqual(self.store("list"), [MANIFEST["id"]])

    def test_share_preserves_archive_without_runtime_files(self):
        data = self.archive(entries=[("assets/icon.txt", "original asset")])
        self.store("install", data)
        installed = self.root / "installed" / MANIFEST["id"]
        (installed / "private.json").write_text("private plugin settings")
        (installed / "main.py").write_text("modified by running plugin")
        output = self.store(
            "export", extra=(MANIFEST["id"], str(self.root / "shares"))
        )
        self.assertEqual(Path(output[0]).read_bytes(), data)
        self.assertTrue(output[0].endswith(".fgplugin"))

    def test_share_uses_updated_archive_after_restart(self):
        self.store("install", self.archive())
        data = self.archive(fields={"version": "2.0.0"})
        self.store("update", data, extra=(MANIFEST["id"], MANIFEST["version"], "false"))
        self.store("apply")
        output = self.store(
            "export", extra=(MANIFEST["id"], str(self.root / "shares"))
        )
        self.assertEqual(Path(output[0]).read_bytes(), data)

    def test_share_refuses_missing_or_mismatched_source(self):
        self.store("install", self.archive())
        source = self.root / "installed" / MANIFEST["id"] / ".fluxgram-source.fgplugin"
        source.unlink()
        self.store("export", accepted=False, extra=(MANIFEST["id"], str(self.root / "shares")))
        source.write_bytes(self.archive(fields={"version": "8.0.0"}))
        self.store("export", accepted=False, extra=(MANIFEST["id"], str(self.root / "shares")))
        self.assertEqual(list((self.root / "shares").iterdir()), [])

    def test_changed_archive_rejected_without_publication(self):
        self.store("install", self.archive(), expected_hash="0" * 64, accepted=False)
        self.assertEqual(self.store("list"), [])
        self.assertEqual(list((self.root / "installed").iterdir()), [])

    def test_install_persists_signer_state_for_exact_archive(self):
        archive = self.archive()
        expected_hash = hashlib.sha256(archive).hexdigest()

        self.store("install", archive)

        self.assertEqual(self.store("signer", extra=(MANIFEST["id"],)), [expected_hash])
        state = self.root / "installed" / MANIFEST["id"] / ".fluxgram-signer-state"
        self.assertTrue(state.is_file())

    def test_legacy_installation_remains_visible_and_can_be_pinned_by_update(self):
        self.store("install", self.archive())
        state = self.root / "installed" / MANIFEST["id"] / ".fluxgram-signer-state"
        state.unlink()
        update = self.archive(fields={"version": "2.0.0"})

        self.assertEqual(self.store("list"), [MANIFEST["id"]])
        self.store(
            "update",
            update,
            extra=(MANIFEST["id"], MANIFEST["version"], "false"),
        )
        self.store("apply")

        self.assertEqual(
            self.store("signer", extra=(MANIFEST["id"],)),
            [hashlib.sha256(update).hexdigest()],
        )

    def test_pending_update_moves_signer_state_with_package_and_rollback(self):
        original = self.archive()
        update = self.archive(fields={"version": "2.0.0"})
        original_hash = hashlib.sha256(original).hexdigest()
        update_hash = hashlib.sha256(update).hexdigest()
        self.store("install", original)
        self.store(
            "update",
            update,
            extra=(MANIFEST["id"], MANIFEST["version"], "false"),
        )
        root = self.root / "installed"
        pending = root / (".pending-" + MANIFEST["id"])

        self.assertTrue((pending / ".fluxgram-signer-journal").is_file())
        self.assertTrue((pending / "package/.fluxgram-signer-state").is_file())
        self.store("activate", extra=(MANIFEST["id"],))
        self.assertEqual(self.store("signer", extra=(MANIFEST["id"],)), [update_hash])

        self.store("rollbackprepare", extra=(MANIFEST["id"],))
        self.assertEqual(self.store("signer", extra=(MANIFEST["id"],)), [original_hash])
        self.store("rollbackfinish", extra=(MANIFEST["id"],))

    def test_tampered_pending_signer_state_is_rejected_before_backup(self):
        self.store("install", self.archive())
        current_state = (
            self.root / "installed" / MANIFEST["id"] / ".fluxgram-signer-state"
        ).read_bytes()
        self.update(version="2.0.0")
        root = self.root / "installed"
        pending_state = (
            root
            / (".pending-" + MANIFEST["id"])
            / "package/.fluxgram-signer-state"
        )
        pending_state.write_bytes(current_state)

        self.store("activate", accepted=False, extra=(MANIFEST["id"],))

        self.assertEqual(self.installed_version(), MANIFEST["version"])
        self.assertFalse((root / (".backup-" + MANIFEST["id"])).exists())

    def test_cross_plugin_signer_journal_is_rejected_per_operation(self):
        second_id = "test.second_fixture"
        self.store("install", self.archive())
        self.store("install", self.archive(fields={"id": second_id, "name": "Second"}))
        self.update(version="2.0.0")
        self.store(
            "update",
            self.archive(fields={"id": second_id, "name": "Second", "version": "2.0.0"}),
            extra=(second_id, MANIFEST["version"], "false"),
        )
        root = self.root / "installed"
        first = root / (".pending-" + MANIFEST["id"]) / ".fluxgram-signer-journal"
        second = root / (".pending-" + second_id) / ".fluxgram-signer-journal"
        first.write_bytes(second.read_bytes())

        errors = self.store("applyerrors")

        self.assertEqual(
            errors,
            [MANIFEST["id"] + ":Pending plugin signer journal belongs to another plugin"],
        )
        self.assertEqual(
            json.loads((root / second_id / "manifest.json").read_text())["version"],
            "2.0.0",
        )

    def test_malformed_signer_state_does_not_hide_other_plugins(self):
        second_id = "test.second_fixture"
        self.store("install", self.archive())
        self.store("install", self.archive(fields={"id": second_id, "name": "Second"}))
        state = self.root / "installed" / MANIFEST["id"] / ".fluxgram-signer-state"
        state.write_bytes(b"partial")

        self.assertCountEqual(self.store("list"), [MANIFEST["id"], second_id])

    def test_duplicate_does_not_replace_existing_files(self):
        self.store("install", self.archive())
        before = (
            self.root / "installed" / MANIFEST["id"] / "manifest.json"
        ).read_bytes()
        self.store("install", self.archive(fields={"version": "2.0.0"}), accepted=False)
        self.assertEqual(
            (self.root / "installed" / MANIFEST["id"] / "manifest.json").read_bytes(),
            before,
        )

    def test_invalid_archive_does_not_create_installation(self):
        self.store(
            "install", self.archive(entries=[("../outside.py", "x")]), accepted=False
        )
        self.assertEqual(self.store("list"), [])

    def test_interrupted_staging_is_never_loaded(self):
        staging = self.root / "installed/.install-interrupted"
        staging.mkdir(parents=True)
        (staging / "main.py").write_text("raise RuntimeError()")
        self.assertEqual(self.store("list"), [])
        self.assertFalse(staging.exists())

    def test_corrupt_installation_does_not_block_healthy_plugin(self):
        self.store("install", self.archive())
        corrupt = self.root / "installed/broken.plugin"
        corrupt.mkdir()
        (corrupt / "manifest.json").write_text("{}")
        self.assertEqual(self.store("list"), [MANIFEST["id"]])

    def update(self, version="1.1.0", enable=True, accepted=True, **kwargs):
        return self.store(
            "update",
            self.archive(fields={"version": version}),
            extra=(MANIFEST["id"], MANIFEST["version"], str(enable).lower()),
            accepted=accepted,
            **kwargs,
        )

    def installed_version(self):
        return json.loads(
            (self.root / "installed" / MANIFEST["id"] / "manifest.json").read_text()
        )["version"]

    def test_update_only_applies_on_fresh_start(self):
        self.store("install", self.archive())
        self.update()
        self.assertEqual(self.installed_version(), MANIFEST["version"])
        self.assertEqual(self.store("pending"), [MANIFEST["id"] + ":false:true"])
        self.store("apply")
        self.assertEqual(self.installed_version(), "1.1.0")
        self.assertEqual(self.store("pending"), [])
        self.store("apply")
        self.assertEqual(self.installed_version(), "1.1.0")

    def test_corrupt_pending_operation_does_not_block_valid_update(self):
        second_id = "test.second_fixture"
        self.store("install", self.archive())
        self.store("install", self.archive(fields={"id": second_id, "name": "Second"}))
        self.update(version="2.0.0")
        self.store(
            "update",
            self.archive(fields={"id": second_id, "name": "Second", "version": "2.0.0"}),
            extra=(second_id, MANIFEST["version"], "true"),
        )
        installed = self.root / "installed"
        corrupt = installed / (".pending-" + MANIFEST["id"])
        shutil.rmtree(corrupt)
        corrupt.mkdir()

        self.assertEqual(
            self.store("applyerrors"),
            [MANIFEST["id"] + ":Pending plugin signer journal is unavailable"],
        )

        self.assertEqual(
            json.loads((installed / second_id / "manifest.json").read_text())["version"],
            "2.0.0",
        )
        self.assertTrue(corrupt.is_dir())
        self.assertEqual(self.store("pending"), [])

        self.store("delete", extra=(MANIFEST["id"],))
        self.store("apply")
        self.assertFalse((installed / MANIFEST["id"]).exists())

    def test_update_keeps_backup_until_migration_finishes(self):
        self.store("install", self.archive())
        self.store(
            "update",
            self.archive(fields={"version": "2.0.0"}),
            extra=(MANIFEST["id"], MANIFEST["version"], "true", "true"),
        )
        root = self.root / "installed"

        self.store("activate", extra=(MANIFEST["id"],))

        self.assertEqual(self.installed_version(), "2.0.0")
        self.assertTrue((root / (".backup-" + MANIFEST["id"])).is_dir())
        self.assertTrue((root / (".pending-" + MANIFEST["id"])).is_dir())
        self.assertEqual(
            self.store("pendingfull"),
            [MANIFEST["id"] + ":true:true:false"],
        )

        self.store("markmigrated", extra=(MANIFEST["id"],))
        self.store("finish", extra=(MANIFEST["id"],))
        self.assertEqual(self.installed_version(), "2.0.0")
        self.assertEqual(self.store("pending"), [])

    def test_update_cannot_finish_before_migration_marker(self):
        self.store("install", self.archive())
        self.update(version="2.0.0")
        self.store("markmigrated", accepted=False, extra=(MANIFEST["id"],))
        self.store("activate", extra=(MANIFEST["id"],))

        self.store("finish", accepted=False, extra=(MANIFEST["id"],))

        self.assertEqual(self.installed_version(), "2.0.0")
        self.assertEqual(self.store("pending"), [MANIFEST["id"] + ":false:true"])

    def test_failed_update_rolls_back_package_and_marks_direction(self):
        self.store("install", self.archive())
        self.update(version="2.0.0")
        root = self.root / "installed"
        self.store("activate", extra=(MANIFEST["id"],))

        (root / (".pending-" + MANIFEST["id"]) / "rollback").write_bytes(b"")
        self.assertEqual(
            self.store("pendingfull"),
            [MANIFEST["id"] + ":true:false:true"],
        )

        self.store("rollback", extra=(MANIFEST["id"],))
        self.assertEqual(self.installed_version(), MANIFEST["version"])
        self.assertEqual(self.store("pending"), [])

    def test_interrupted_rollback_finishes_with_old_package(self):
        self.store("install", self.archive())
        self.update(version="2.0.0")
        root = self.root / "installed"
        self.store("activate", extra=(MANIFEST["id"],))
        pending = root / (".pending-" + MANIFEST["id"])
        (pending / "rollback").write_bytes(b"")
        failed = root / ".install-failed-test"
        (root / MANIFEST["id"]).rename(failed)
        (root / (".backup-" + MANIFEST["id"])).rename(root / MANIFEST["id"])

        self.store("rollback", extra=(MANIFEST["id"],))

        self.assertEqual(self.installed_version(), MANIFEST["version"])
        self.assertEqual(self.store("pending"), [])
        self.store("list")
        self.assertFalse(failed.exists())

    def test_rollback_journal_survives_until_enabled_state_is_restored(self):
        self.store("install", self.archive())
        self.update(version="2.0.0")
        self.store("activate", extra=(MANIFEST["id"],))

        self.store("rollbackprepare", extra=(MANIFEST["id"],))

        self.assertEqual(self.installed_version(), MANIFEST["version"])
        self.assertEqual(
            self.store("pendingfull"),
            [MANIFEST["id"] + ":true:false:true"],
        )
        self.store("rollbackprepare", extra=(MANIFEST["id"],))
        self.store("rollbackfinish", extra=(MANIFEST["id"],))
        self.assertEqual(self.store("pending"), [])

    def test_migrated_marker_finishes_after_backup_cleanup_crash(self):
        self.store("install", self.archive())
        self.update(version="2.0.0")
        root = self.root / "installed"
        self.store("activate", extra=(MANIFEST["id"],))
        pending = root / (".pending-" + MANIFEST["id"])
        (pending / "migrated").write_bytes(b"")
        shutil.rmtree(root / (".backup-" + MANIFEST["id"]))

        self.store("finish", extra=(MANIFEST["id"],))

        self.assertEqual(self.installed_version(), "2.0.0")
        self.assertEqual(self.store("pending"), [])

    def test_migrated_update_cleans_partially_deleted_backup(self):
        self.store("install", self.archive())
        self.update(version="2.0.0")
        root = self.root / "installed"
        self.store("activate", extra=(MANIFEST["id"],))
        self.store("markmigrated", extra=(MANIFEST["id"],))
        backup = root / (".backup-" + MANIFEST["id"])
        (backup / "manifest.json").unlink()

        self.store("finish", extra=(MANIFEST["id"],))

        self.assertEqual(self.installed_version(), "2.0.0")
        self.assertFalse(backup.exists())
        self.assertEqual(self.store("pending"), [])

    def test_delete_does_not_discard_invalid_active_update_journal(self):
        self.store("install", self.archive())
        self.update(version="2.0.0")
        self.store("activate", extra=(MANIFEST["id"],))
        root = self.root / "installed"
        pending = root / (".pending-" + MANIFEST["id"])
        journal = pending / ".fluxgram-signer-journal"
        journal.write_bytes(b"partial")

        self.store("delete", accepted=False, extra=(MANIFEST["id"],))

        self.assertTrue(pending.is_dir())
        self.assertTrue((root / (".backup-" + MANIFEST["id"])).is_dir())

    def test_update_cannot_reuse_or_downgrade_version(self):
        self.store("install", self.archive())
        self.update(version=MANIFEST["version"], accepted=False)
        self.update(version="0.9.0", accepted=False)
        self.assertEqual(self.store("pending"), [])
        self.assertEqual(self.installed_version(), MANIFEST["version"])

    def test_debug_reload_allows_same_version_and_can_be_cancelled(self):
        original = self.archive()
        replacement = self.archive(entries=[("revision.txt", "debug reload")])
        self.store("install", original)
        self.store(
            "debugreload",
            replacement,
            extra=(MANIFEST["id"], MANIFEST["version"], "true"),
        )
        self.assertEqual(
            self.store("pendingfull"),
            [MANIFEST["id"] + ":true:true:false"],
        )
        self.store("cancel", extra=(MANIFEST["id"],))
        self.assertEqual(self.store("pending"), [])
        self.assertEqual(self.installed_version(), MANIFEST["version"])
        self.assertFalse(
            (self.root / "installed" / MANIFEST["id"] / "revision.txt").exists()
        )

    def test_update_versions_compared_numerically(self):
        self.store("install", self.archive(fields={"version": "1.9.0"}))
        self.store(
            "update",
            self.archive(fields={"version": "1.10.0"}),
            extra=(MANIFEST["id"], "1.9.0", "false"),
        )
        self.assertEqual(self.store("pending"), [MANIFEST["id"] + ":false:false"])
        self.store("apply")
        self.assertEqual(self.installed_version(), "1.10.0")

    def test_changed_update_archive_is_rejected(self):
        self.store("install", self.archive())
        self.update(expected_hash="0" * 64, accepted=False)
        self.assertEqual(self.store("pending"), [])
        self.assertEqual(self.installed_version(), MANIFEST["version"])

    def test_stale_update_review_is_rejected(self):
        self.store("install", self.archive())
        self.store(
            "update",
            self.archive(fields={"version": "2.0.0"}),
            extra=(MANIFEST["id"], "0.9.0", "false"),
            accepted=False,
        )
        self.assertEqual(self.store("pending"), [])

    def test_update_different_id_is_rejected(self):
        self.store("install", self.archive())
        self.store(
            "update",
            self.archive(fields={"id": "another.plugin", "version": "2.0.0"}),
            extra=(MANIFEST["id"], MANIFEST["version"], "false"),
            accepted=False,
        )
        self.assertEqual(self.store("pending"), [])

    def test_conflicting_pending_operations_are_rejected(self):
        self.store("install", self.archive())
        self.update()
        self.update(version="2.0.0", accepted=False)
        self.store("delete", extra=(MANIFEST["id"],), accepted=False)
        self.store("apply")
        self.assertEqual(self.installed_version(), "1.1.0")

    def test_delete_waits_for_restart_and_can_be_reinstalled(self):
        self.store("install", self.archive())
        self.store("delete", extra=(MANIFEST["id"],))
        self.assertEqual(self.store("list"), [MANIFEST["id"]])
        self.assertEqual(self.store("pending"), [MANIFEST["id"] + ":true:false"])
        self.assertEqual(self.store("apply"), [])
        self.assertEqual(self.store("pending"), [])
        self.assertEqual(self.store("install", self.archive()), [MANIFEST["id"]])

    def test_delete_rejects_invalid_id_and_builtin(self):
        for name in (
            "../escape",
            "fluxgram.in_app_notifications",
            "not_installed.plugin",
        ):
            self.store("delete", extra=(name,), accepted=False)
        self.assertEqual(self.store("pending"), [])

    def test_interrupted_update_after_backup_resumes(self):
        self.store("install", self.archive())
        self.update()
        root = self.root / "installed"
        (root / MANIFEST["id"]).rename(root / (".backup-" + MANIFEST["id"]))
        self.store("apply")
        self.assertEqual(self.installed_version(), "1.1.0")
        self.assertEqual([p.name for p in root.iterdir()], [MANIFEST["id"]])

    def test_interrupted_update_after_publish_resumes(self):
        self.store("install", self.archive())
        self.update()
        root = self.root / "installed"
        (root / MANIFEST["id"]).rename(root / (".backup-" + MANIFEST["id"]))
        (root / (".pending-" + MANIFEST["id"]) / "package").rename(
            root / MANIFEST["id"]
        )
        self.store("apply")
        self.assertEqual(self.installed_version(), "1.1.0")
        self.assertEqual([p.name for p in root.iterdir()], [MANIFEST["id"]])

    def test_interrupted_delete_after_backup_resumes(self):
        self.store("install", self.archive())
        self.store("delete", extra=(MANIFEST["id"],))
        root = self.root / "installed"
        (root / MANIFEST["id"]).rename(root / (".backup-" + MANIFEST["id"]))
        self.assertEqual(self.store("apply"), [])
        self.assertEqual(list(root.iterdir()), [])

    def test_delete_never_follows_plugin_symlinks(self):
        self.store("install", self.archive())
        unrelated = self.root / "unrelated"
        unrelated.mkdir()
        (unrelated / "important.txt").write_text("keep")
        (self.root / "installed" / MANIFEST["id"] / "link").symlink_to(
            unrelated, target_is_directory=True
        )
        self.store("delete", extra=(MANIFEST["id"],))
        self.assertEqual(self.store("apply"), [])
        self.assertEqual((unrelated / "important.txt").read_text(), "keep")
