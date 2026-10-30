package org.telegram.messenger.plugins.risk;

import org.telegram.messenger.plugins.packages.PluginManifest;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.Adler32;

public final class PluginRiskAnalyzer {
    public static final int ANALYZER_VERSION = 1;
    public static final String ANALYSIS_CONTEXT_FINGERPRINT =
            "211b71417406e1bd92de9082d77b2cca33dade32e5394a7bb388c7e0c3077010";

    private static final int MAX_DEX_STRINGS = 65536;
    private static final int MAX_DEX_TYPES = 32768;
    private static final int MAX_DEX_FIELDS = 32768;
    private static final int MAX_DEX_METHODS = 32768;
    private static final int MAX_DEX_CLASSES = 8192;
    private static final int MAX_DEX_STRING_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DEX_CODE_UNITS = 262144;
    private static final long MAX_ANALYSIS_NANOS = 2_000_000_000L;

    private static final Pattern ENDPOINT = Pattern.compile(
            "(?i)(?:https?://[^\\s]+|(?:[a-z0-9-]+\\.)+[a-z]{2,}(?::[0-9]{1,5})?(?:/[^\\s]*)?)");
    private static final Pattern CLASS_NAME = Pattern.compile(
            "(?:[A-Za-z_$][A-Za-z0-9_$]*[./])+[A-Za-z_$][A-Za-z0-9_$]*");

    private PluginRiskAnalyzer() {
    }

    public static PluginRiskReport analyze(
            File archive,
            PluginManifest manifest,
            String archiveSha256,
            byte[] declaredDex) throws IOException {
        Collector collector = new Collector();
        ArrayList<PluginRiskReport.ModuleDigest> modules = new ArrayList<>();

        if (manifest.hasPythonModule()) {
            collector.add(
                    "access.full_process.python",
                    null,
                    PluginRiskEvidence.Source.MANIFEST,
                    PluginRiskEvidence.Confidence.EXACT);
            collector.add(
                    "python.top_level.preflight",
                    manifest.entryFile(),
                    PluginRiskEvidence.Source.MANIFEST,
                    PluginRiskEvidence.Confidence.EXACT);
        }

        if (manifest.hasJavaModule()) {
            collector.add(
                    "access.full_process.dex",
                    manifest.javaModule.path,
                    PluginRiskEvidence.Source.MANIFEST,
                    PluginRiskEvidence.Confidence.EXACT);
            modules.add(new PluginRiskReport.ModuleDigest("java", manifest.javaModule.sha256));
        }

        String pythonDigest = classifyArchive(archive, manifest, collector);

        if (pythonDigest != null) {
            modules.add(new PluginRiskReport.ModuleDigest("python", pythonDigest));
        }

        if (declaredDex != null) {
            try {
                new DexAnalyzer(declaredDex, collector).analyze();
            } catch (LimitException error) {
                collector.limit("analysis.limit_exceeded");
            } catch (FormatException | IndexOutOfBoundsException error) {
                collector.limit("analysis.incomplete");
            }
        }

        return new PluginRiskReport(
                archiveSha256,
                manifest.id,
                manifest.version,
                manifest.schemaVersion,
                ANALYZER_VERSION,
                ANALYSIS_CONTEXT_FINGERPRINT,
                System.currentTimeMillis(),
                modules,
                collector.evidence(),
                collector.limitations());
    }

    private static String classifyArchive(
            File archive,
            PluginManifest manifest,
            Collector collector) throws IOException {
        ArrayList<ZipEntry> entries = new ArrayList<>();

        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> iterator = zip.entries();

            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();

                if (!entry.isDirectory()) {
                    entries.add(entry);
                }
            }

            entries.sort(Comparator.comparing(ZipEntry::getName));

            MessageDigest python = digest();
            boolean hasPython = false;

            for (ZipEntry entry : entries) {
                String path = entry.getName();
                String lower = path.toLowerCase(Locale.ROOT);
                boolean declaredJava = manifest.hasJavaModule()
                        && path.equals(manifest.javaModule.path);
                boolean pythonSource = lower.endsWith(".py");
                boolean undeclaredExecutable = lower.endsWith(".dex")
                        || lower.endsWith(".jar")
                        || lower.endsWith(".apk")
                        || lower.endsWith(".class")
                        || lower.endsWith(".pyc")
                        || lower.endsWith(".pyo")
                        || pythonSource && !manifest.hasPythonModule();

                if (undeclaredExecutable && !declaredJava) {
                    collector.add(
                            "block.archive.undeclared_executable",
                            bounded(path),
                            PluginRiskEvidence.Source.ARCHIVE,
                            PluginRiskEvidence.Confidence.EXACT);
                }

                byte[] prefix = new byte[4];
                int prefixLength = 0;

                try (InputStream input = zip.getInputStream(entry)) {
                    while (prefixLength < prefix.length) {
                        int count = input.read(prefix, prefixLength, prefix.length - prefixLength);

                        if (count == -1) {
                            break;
                        }

                        prefixLength += count;
                    }

                    if (pythonSource && manifest.hasPythonModule()) {
                        byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
                        updateInt(python, pathBytes.length);
                        python.update(pathBytes);
                        updateLong(python, entry.getSize());
                        python.update(prefix, 0, prefixLength);
                        copyDigest(input, python);
                        hasPython = true;
                    }
                }

                boolean elf = prefixLength == 4
                        && prefix[0] == 0x7f
                        && prefix[1] == 'E'
                        && prefix[2] == 'L'
                        && prefix[3] == 'F';
                boolean dexFile = prefixLength == 4
                        && prefix[0] == 'd'
                        && prefix[1] == 'e'
                        && prefix[2] == 'x'
                        && prefix[3] == '\n';

                if (dexFile && !declaredJava) {
                    collector.add(
                            "block.archive.undeclared_executable",
                            bounded(path),
                            PluginRiskEvidence.Source.ARCHIVE,
                            PluginRiskEvidence.Confidence.EXACT);
                }

                if (elf || lower.endsWith(".so")) {
                    collector.add(
                            "block.archive.native_payload_forbidden",
                            bounded(path),
                            PluginRiskEvidence.Source.ARCHIVE,
                            PluginRiskEvidence.Confidence.EXACT);
                }
            }

            return hasPython ? hex(python.digest()) : null;
        }
    }

    private static void copyDigest(InputStream input, MessageDigest digest) throws IOException {
        byte[] buffer = new byte[8192];
        int count;

        while ((count = input.read(buffer)) != -1) {
            digest.update(buffer, 0, count);
        }
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void updateLong(MessageDigest digest, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            digest.update((byte) (value >>> shift));
        }
    }

    private static MessageDigest digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }

    private static String hex(byte[] data) {
        StringBuilder result = new StringBuilder(data.length * 2);

        for (byte value : data) {
            result.append(Character.forDigit((value & 255) >>> 4, 16));
            result.append(Character.forDigit(value & 15, 16));
        }

        return result.toString();
    }

    private static String bounded(String value) {
        if (value == null) {
            return null;
        }

        StringBuilder result = new StringBuilder();
        int encodedBytes = 0;

        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);

            if (!Character.isISOControl(codePoint)) {
                int bytes = codePoint <= 0x7f
                        ? 1
                        : codePoint <= 0x7ff
                                ? 2
                                : codePoint <= 0xffff ? 3 : 4;

                if (encodedBytes + bytes > 240) {
                    break;
                }

                result.appendCodePoint(codePoint);
                encodedBytes += bytes;
            }

            offset += Character.charCount(codePoint);
        }

        return result.length() == 0 ? null : result.toString();
    }

    private static final class Collector {
        private final Map<String, PluginRiskEvidence> evidence = new LinkedHashMap<>();
        private final Set<String> limitations = new HashSet<>();

        void add(
                String id,
                String detail,
                PluginRiskEvidence.Source source,
                PluginRiskEvidence.Confidence confidence) {
            if (!evidence.containsKey(id)) {
                evidence.put(id, new PluginRiskEvidence(id, bounded(detail), source, confidence));
            }
        }

        void limit(String id) {
            limitations.add(id);
        }

        List<PluginRiskEvidence> evidence() {
            return new ArrayList<>(evidence.values());
        }

        List<String> limitations() {
            ArrayList<String> result = new ArrayList<>(limitations);
            Collections.sort(result);
            return result;
        }
    }

    private static final class DexAnalyzer {
        private final byte[] data;
        private final ByteBuffer dex;
        private final Collector collector;
        private final long deadline;
        private final Set<Integer> scannedCode = new HashSet<>();
        private final Set<String> referencedStrings = new HashSet<>();
        private final Map<Integer, MapItem> mapItems = new LinkedHashMap<>();

        private String[] strings;
        private String[] types;
        private int[] fieldOwners;
        private int[] fieldTypes;
        private int[] fieldNames;
        private int[] protoReturns;
        private int[] methodOwners;
        private int[] methodProtos;
        private int[] methodNames;
        private boolean dynamicLookup;
        private int codeUnits;
        private int dataOffset;
        private int dataEnd;
        private int callSiteCount;
        private int methodHandleCount;
        private int methodHandleOffset;

        DexAnalyzer(byte[] data, Collector collector) {
            this.data = data;
            this.dex = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            this.collector = collector;
            this.deadline = System.nanoTime() + MAX_ANALYSIS_NANOS;
        }

        void analyze() throws FormatException, LimitException {
            validateContainer();
            int stringCount = positive(56, MAX_DEX_STRINGS);
            int stringOffset = offset(60);
            int typeCount = positive(64, MAX_DEX_TYPES);
            int typeOffset = offset(68);
            int protoCount = count(72, MAX_DEX_METHODS);
            int protoOffset = offsetOrZero(76, protoCount);
            int fieldCount = count(80, MAX_DEX_FIELDS);
            int fieldOffset = offsetOrZero(84, fieldCount);
            int methodCount = count(88, MAX_DEX_METHODS);
            int methodOffset = offsetOrZero(92, methodCount);
            int classCount = positive(96, MAX_DEX_CLASSES);
            int classOffset = offset(100);

            table(stringOffset, stringCount, 4);
            table(typeOffset, typeCount, 4);
            table(protoOffset, protoCount, 12);
            table(fieldOffset, fieldCount, 8);
            table(methodOffset, methodCount, 8);
            table(classOffset, classCount, 32);
            validateCoreMap(0x0001, stringCount, stringOffset);
            validateCoreMap(0x0002, typeCount, typeOffset);
            validateCoreMap(0x0003, protoCount, protoOffset);
            validateCoreMap(0x0004, fieldCount, fieldOffset);
            validateCoreMap(0x0005, methodCount, methodOffset);
            validateCoreMap(0x0006, classCount, classOffset);

            readStrings(stringCount, stringOffset);
            readTypes(typeCount, typeOffset);
            readProtos(protoCount, protoOffset);
            readFields(fieldCount, fieldOffset);
            readMethods(methodCount, methodOffset);
            readCallSites();
            readMethodHandles();
            readClasses(classCount, classOffset);
            classifyDynamicStrings();
        }

        private void validateContainer() throws FormatException, LimitException {
            require(data.length >= 112);
            String magic = new String(data, 0, 8, StandardCharsets.US_ASCII);
            require(magic.matches("dex\\n0(35|37|38|39)\\u0000"));
            require(dex.getInt(32) == data.length);
            require(dex.getInt(36) == 112);
            require(dex.getInt(40) == 0x12345678);

            int linkSize = dex.getInt(44);
            int linkOffset = dex.getInt(48);
            require(linkSize == 0 && linkOffset == 0);

            dataOffset = dex.getInt(108);
            long dataSize = Integer.toUnsignedLong(dex.getInt(104));
            require(dataOffset >= 112 && dataOffset < data.length && (dataOffset & 3) == 0);
            require(dataSize == data.length - dataOffset);
            dataEnd = data.length;

            byte[] expectedSignature = digest("SHA-1", data, 32, data.length - 32);
            require(Arrays.equals(expectedSignature, Arrays.copyOfRange(data, 12, 32)));
            Adler32 checksum = new Adler32();
            checksum.update(data, 12, data.length - 12);
            require(Integer.toUnsignedLong(dex.getInt(8)) == checksum.getValue());

            int mapOffset = dex.getInt(52);
            requireDataOffset(mapOffset);
            requireRange(mapOffset, 4);
            long mapCountValue = Integer.toUnsignedLong(dex.getInt(mapOffset));

            if (mapCountValue > 64) {
                throw new LimitException();
            }

            require(mapCountValue > 0);
            int mapCount = (int) mapCountValue;
            require((long) mapOffset + 4L + mapCount * 12L <= data.length);
            int previousOffset = -1;

            for (int i = 0; i < mapCount; i++) {
                int position = mapOffset + 4 + i * 12;
                int type = ushort(position);
                int unused = ushort(position + 2);
                long count = Integer.toUnsignedLong(dex.getInt(position + 4));
                int offset = dex.getInt(position + 8);
                require(isKnownMapType(type));
                require(unused == 0 && count > 0 && count <= Integer.MAX_VALUE);

                if (count > mapItemLimit(type)) {
                    throw new LimitException();
                }

                require(offset >= 0 && offset < data.length
                        && (previousOffset < 0 || offset > previousOffset));
                require(!mapItems.containsKey(type));
                mapItems.put(type, new MapItem(type, (int) count, offset));
                previousOffset = offset;
            }

            requireMap(0x0000, 1, 0);
            requireMap(0x1000, 1, mapOffset);
            MapItem callSites = mapItems.get(0x0007);
            MapItem methodHandles = mapItems.get(0x0008);
            callSiteCount = callSites == null ? 0 : callSites.count;
            methodHandleCount = methodHandles == null ? 0 : methodHandles.count;
            methodHandleOffset = methodHandles == null ? 0 : methodHandles.offset;

            if (callSites != null) {
                table(callSites.offset, callSites.count, 4);
            }
            if (methodHandles != null) {
                table(methodHandles.offset, methodHandles.count, 8);
            }
        }

        private byte[] digest(String algorithm, byte[] value, int offset, int length)
                throws FormatException {
            try {
                MessageDigest digest = MessageDigest.getInstance(algorithm);
                digest.update(value, offset, length);
                return digest.digest();
            } catch (NoSuchAlgorithmException error) {
                throw new FormatException();
            }
        }

        private boolean isKnownMapType(int type) {
            return type >= 0x0000 && type <= 0x0008
                    || type >= 0x1000 && type <= 0x1003
                    || type >= 0x2000 && type <= 0x2006
                    || type == 0xf000;
        }

        private int mapItemLimit(int type) {
            if (type == 0x0001 || type == 0x2002) {
                return MAX_DEX_STRINGS;
            }
            if (type == 0x0002 || type == 0x1001) {
                return MAX_DEX_TYPES;
            }
            if (type == 0x0004) {
                return MAX_DEX_FIELDS;
            }
            if (type == 0x0006) {
                return MAX_DEX_CLASSES;
            }
            if (type == 0x1000) {
                return 1;
            }

            return MAX_DEX_METHODS;
        }

        private void validateCoreMap(int type, int count, int offset) throws FormatException {
            MapItem item = mapItems.get(type);

            if (count == 0) {
                require(item == null);
            } else {
                require(item != null && item.count == count && item.offset == offset);
            }
        }

        private void requireMap(int type, int count, int offset) throws FormatException {
            MapItem item = mapItems.get(type);
            require(item != null && item.count == count && item.offset == offset);
        }

        private void readStrings(int count, int tableOffset)
                throws FormatException, LimitException {
            strings = new String[count];
            int totalBytes = 0;

            for (int i = 0; i < count; i++) {
                checkDeadline(i);
                int start = uint(dex.getInt(tableOffset + i * 4));
                requireMappedOffset(0x2002, start);
                Cursor cursor = new Cursor(start);
                int utf16Length = cursor.uleb();
                int valueStart = cursor.position;

                while (cursor.position < data.length && data[cursor.position] != 0) {
                    cursor.position++;
                    totalBytes++;

                    if (cursor.position - valueStart > 4096
                            || totalBytes > MAX_DEX_STRING_BYTES) {
                        throw new LimitException();
                    }
                }

                require(cursor.position < data.length);
                strings[i] = decodeMutf8(valueStart, cursor.position, utf16Length);
            }
        }

        private void readTypes(int count, int tableOffset) throws FormatException, LimitException {
            types = new String[count];

            for (int i = 0; i < count; i++) {
                checkDeadline(i);
                int stringIndex = dex.getInt(tableOffset + i * 4);
                requireIndex(stringIndex, strings.length);
                types[i] = strings[stringIndex];
                require(isTypeDescriptor(types[i]));
                classifyType(types[i]);
            }
        }

        private void readProtos(int count, int tableOffset) throws FormatException, LimitException {
            protoReturns = new int[count];

            for (int i = 0; i < count; i++) {
                checkDeadline(i);
                int position = tableOffset + i * 12;
                int shorty = dex.getInt(position);
                protoReturns[i] = dex.getInt(position + 4);
                int parameters = dex.getInt(position + 8);
                requireIndex(shorty, strings.length);
                require(strings[shorty].matches("[VZBSCIJFDL]+"));
                requireIndex(protoReturns[i], types.length);
                classifyType(types[protoReturns[i]]);

                if (parameters != 0) {
                    readTypeList(parameters);
                }
            }
        }

        private void readFields(int count, int tableOffset) throws FormatException, LimitException {
            fieldOwners = new int[count];
            fieldTypes = new int[count];
            fieldNames = new int[count];

            for (int i = 0; i < count; i++) {
                checkDeadline(i);
                int position = tableOffset + i * 8;
                fieldOwners[i] = ushort(position);
                fieldTypes[i] = ushort(position + 2);
                fieldNames[i] = dex.getInt(position + 4);
                requireIndex(fieldOwners[i], types.length);
                requireIndex(fieldTypes[i], types.length);
                requireIndex(fieldNames[i], strings.length);
                classifyType(types[fieldOwners[i]]);
                classifyType(types[fieldTypes[i]]);
            }
        }

        private void readMethods(int count, int tableOffset) throws FormatException, LimitException {
            methodOwners = new int[count];
            methodProtos = new int[count];
            methodNames = new int[count];

            for (int i = 0; i < count; i++) {
                checkDeadline(i);
                int position = tableOffset + i * 8;
                methodOwners[i] = ushort(position);
                methodProtos[i] = ushort(position + 2);
                methodNames[i] = dex.getInt(position + 4);
                requireIndex(methodOwners[i], types.length);
                requireIndex(methodProtos[i], protoReturns.length);
                requireIndex(methodNames[i], strings.length);
                classifyType(types[methodOwners[i]]);
            }
        }

        private void readMethodHandles() throws FormatException, LimitException {
            for (int i = 0; i < methodHandleCount; i++) {
                checkDeadline(i);
                int position = methodHandleOffset + i * 8;
                int type = ushort(position);
                int firstUnused = ushort(position + 2);
                int target = ushort(position + 4);
                int secondUnused = ushort(position + 6);
                require(type >= 0 && type <= 8 && firstUnused == 0 && secondUnused == 0);

                if (type <= 3) {
                    classifyField(target);
                } else {
                    classifyMethod(target);
                }
            }
        }

        private void readCallSites() throws FormatException, LimitException {
            MapItem callSites = mapItems.get(0x0007);

            if (callSites == null) {
                return;
            }

            for (int i = 0; i < callSites.count; i++) {
                checkDeadline(i);
                int encodedArray = dex.getInt(callSites.offset + i * 4);
                requireMappedOffset(0x2005, encodedArray);
            }
        }

        private void readClasses(int count, int tableOffset)
                throws FormatException, LimitException {
            for (int i = 0; i < count; i++) {
                checkDeadline(i);
                int position = tableOffset + i * 32;
                int classIndex = dex.getInt(position);
                requireIndex(classIndex, types.length);
                classifyType(types[classIndex]);

                int superIndex = dex.getInt(position + 8);

                if (superIndex != -1) {
                    requireIndex(superIndex, types.length);
                    classifyType(types[superIndex]);
                }

                int interfacesOffset = dex.getInt(position + 12);

                if (interfacesOffset != 0) {
                    readTypeList(interfacesOffset);
                }

                int sourceFile = dex.getInt(position + 16);

                if (sourceFile != -1) {
                    requireIndex(sourceFile, strings.length);
                }

                int annotationsOffset = dex.getInt(position + 20);

                if (annotationsOffset != 0) {
                    requireMappedOffset(0x2006, annotationsOffset);
                    requireRange(annotationsOffset, 16);
                }

                int classDataOffset = dex.getInt(position + 24);

                if (classDataOffset != 0) {
                    readClassData(classDataOffset);
                }

                int staticValuesOffset = dex.getInt(position + 28);

                if (staticValuesOffset != 0) {
                    requireMappedOffset(0x2005, staticValuesOffset);
                    new Cursor(staticValuesOffset).ulebLimited(MAX_DEX_FIELDS);
                }
            }
        }

        private void readTypeList(int offset) throws FormatException, LimitException {
            requireMappedOffset(0x1001, offset);
            requireRange(offset, 4);
            long count = Integer.toUnsignedLong(dex.getInt(offset));

            if (count > MAX_DEX_TYPES) {
                throw new LimitException();
            }

            require((long) offset + 4L + count * 2L <= data.length);

            for (int i = 0; i < count; i++) {
                checkDeadline(i);
                int type = ushort(offset + 4 + i * 2);
                requireIndex(type, types.length);
                classifyType(types[type]);
            }
        }

        private void readClassData(int offset) throws FormatException, LimitException {
            requireMappedOffset(0x2000, offset);
            Cursor cursor = new Cursor(offset);
            int staticFields = cursor.ulebLimited(MAX_DEX_FIELDS);
            int instanceFields = cursor.ulebLimited(MAX_DEX_FIELDS);
            int directMethods = cursor.ulebLimited(MAX_DEX_METHODS);
            int virtualMethods = cursor.ulebLimited(MAX_DEX_METHODS);
            require((long) staticFields + instanceFields <= MAX_DEX_FIELDS);
            require((long) directMethods + virtualMethods <= MAX_DEX_METHODS);
            readEncodedFields(cursor, staticFields);
            readEncodedFields(cursor, instanceFields);
            readEncodedMethods(cursor, directMethods);
            readEncodedMethods(cursor, virtualMethods);
        }

        private void readEncodedFields(Cursor cursor, int count) throws FormatException, LimitException {
            int index = 0;

            for (int i = 0; i < count; i++) {
                checkDeadline(i);
                int difference = cursor.uleb();
                require(i == 0 || difference > 0);
                index = addIndex(index, difference, fieldOwners.length);
                cursor.uleb();
                classifyField(index);
            }
        }

        private void readEncodedMethods(Cursor cursor, int count)
                throws FormatException, LimitException {
            int index = 0;

            for (int i = 0; i < count; i++) {
                checkDeadline(i);
                int difference = cursor.uleb();
                require(i == 0 || difference > 0);
                index = addIndex(index, difference, methodOwners.length);
                int access = cursor.uleb();
                int codeOffset = cursor.uleb();
                classifyMethod(index);

                boolean hasNoCode = (access & (0x100 | 0x400)) != 0;
                require(hasNoCode == (codeOffset == 0));

                if ((access & 0x100) != 0) {
                    collector.add(
                            "code.native_loading",
                            methodDetail(index),
                            PluginRiskEvidence.Source.DEX,
                            PluginRiskEvidence.Confidence.EXACT);
                }

                if (codeOffset != 0) {
                    if (scannedCode.add(codeOffset)) {
                        scanCode(codeOffset);
                    }
                }
            }
        }

        private void scanCode(int offset) throws FormatException, LimitException {
            requireMappedOffset(0x2001, offset);
            requireRange(offset, 16);
            long instructionCount = Integer.toUnsignedLong(dex.getInt(offset + 12));

            if (instructionCount > MAX_DEX_CODE_UNITS
                    || codeUnits + instructionCount > MAX_DEX_CODE_UNITS) {
                throw new LimitException();
            }

            int start = offset + 16;
            require((long) start + instructionCount * 2 <= data.length);
            int end = start + (int) instructionCount * 2;
            int position = start;
            codeUnits += (int) instructionCount;

            while (position < end) {
                checkDeadline((position - start) / 2);
                int first = ushort(position);
                int opcode = first & 255;

                if (opcode == 0 && first >>> 8 != 0) {
                    require(((position - start) / 2 & 1) == 0);
                }

                int width = instructionWidth(opcode, first, position);
                require(width > 0 && position + width * 2 <= end);

                if (opcode == 0x1a) {
                    classifyString(ushort(position + 2));
                } else if (opcode == 0x1b) {
                    classifyString(dex.getInt(position + 2));
                } else if (isTypeReference(opcode)) {
                    classifyTypeIndex(ushort(position + 2));
                } else if (opcode >= 0x52 && opcode <= 0x6d) {
                    classifyField(ushort(position + 2));
                } else if (opcode >= 0x6e && opcode <= 0x72
                        || opcode >= 0x74 && opcode <= 0x78
                        || opcode == 0xfa || opcode == 0xfb) {
                    classifyMethod(ushort(position + 2));
                }

                if (opcode == 0xfa || opcode == 0xfb) {
                    requireIndex(ushort(position + 6), protoReturns.length);
                    markModernDynamic();
                } else if (opcode == 0xfc || opcode == 0xfd) {
                    requireIndex(ushort(position + 2), callSiteCount);
                    markModernDynamic();
                } else if (opcode == 0xfe) {
                    requireIndex(ushort(position + 2), methodHandleCount);
                    markModernDynamic();
                } else if (opcode == 0xff) {
                    requireIndex(ushort(position + 2), protoReturns.length);
                    markModernDynamic();
                }

                position += width * 2;
            }

            require(position == end);
            validateCodeTail(offset, start, (int) instructionCount);
        }

        private void validateCodeTail(int codeOffset, int instructionStart, int instructionCount)
                throws FormatException, LimitException {
            int tries = ushort(codeOffset + 6);
            int debugInfo = dex.getInt(codeOffset + 8);

            if (debugInfo != 0) {
                requireMappedOffset(0x2003, debugInfo);
                Cursor debug = new Cursor(debugInfo);
                debug.uleb();
                int parameters = debug.ulebLimited(MAX_DEX_TYPES);

                for (int i = 0; i < parameters; i++) {
                    debug.uleb();
                }
            }
            if (tries == 0) {
                return;
            }

            long triesOffsetLong = (long) instructionStart + instructionCount * 2L;

            if ((instructionCount & 1) != 0) {
                require(ushort((int) triesOffsetLong) == 0);
                triesOffsetLong += 2;
            }

            require(triesOffsetLong <= Integer.MAX_VALUE);
            int triesOffset = (int) triesOffsetLong;
            require((long) triesOffset + (long) tries * 8L <= data.length);
            int handlersOffset = triesOffset + tries * 8;
            Cursor handlers = new Cursor(handlersOffset);
            int handlerCount = handlers.ulebLimited(MAX_DEX_METHODS);
            require(handlerCount > 0);
            Set<Integer> handlerStarts = new HashSet<>();

            for (int i = 0; i < handlerCount; i++) {
                checkDeadline(i);
                handlerStarts.add(handlers.position - handlersOffset);
                int size = handlers.sleb();
                require(size != Integer.MIN_VALUE);
                int pairs = Math.abs(size);

                if (pairs > MAX_DEX_TYPES) {
                    throw new LimitException();
                }

                for (int pair = 0; pair < pairs; pair++) {
                    int type = handlers.uleb();
                    requireIndex(type, types.length);
                    classifyType(types[type]);
                    handlers.uleb();
                }

                if (size <= 0) {
                    handlers.uleb();
                }
            }

            for (int i = 0; i < tries; i++) {
                int position = triesOffset + i * 8;
                long startAddress = Integer.toUnsignedLong(dex.getInt(position));
                int count = ushort(position + 4);
                int handler = ushort(position + 6);
                require(startAddress + count <= instructionCount);
                require(handlerStarts.contains(handler));
            }
        }

        private int instructionWidth(int opcode, int first, int position)
                throws FormatException, LimitException {
            if (opcode == 0) {
                int identifier = first >>> 8;

                if (identifier == 0) {
                    return 1;
                }

                requireRange(position, 4);
                int size = ushort(position + 2);

                if (identifier == 1) {
                    return checkedWidth(4L + size * 2L);
                }
                if (identifier == 2) {
                    return checkedWidth(2L + size * 4L);
                }
                if (identifier == 3) {
                    requireRange(position, 8);
                    int elementWidth = ushort(position + 2);
                    long elements = Integer.toUnsignedLong(dex.getInt(position + 4));
                    return checkedWidth(4L + (elementWidth * elements + 1L) / 2L);
                }

                throw new FormatException();
            }

            if (opcode == 0x18) {
                return 5;
            }
            if (opcode >= 0x3e && opcode <= 0x43
                    || opcode == 0x73
                    || opcode == 0x79
                    || opcode == 0x7a
                    || opcode >= 0xe3 && opcode <= 0xf9) {
                throw new FormatException();
            }
            if (opcode == 0xfa || opcode == 0xfb) {
                return 4;
            }
            if (opcode == 0x03 || opcode == 0x06 || opcode == 0x09
                    || opcode == 0x14 || opcode == 0x17 || opcode == 0x1b
                    || opcode == 0x24 || opcode == 0x25 || opcode == 0x26
                    || opcode == 0x2a || opcode == 0x2b || opcode == 0x2c
                    || opcode >= 0x6e && opcode <= 0x72
                    || opcode >= 0x74 && opcode <= 0x78
                    || opcode == 0xfc || opcode == 0xfd) {
                return 3;
            }
            if (opcode == 0x02 || opcode == 0x05 || opcode == 0x08
                    || opcode == 0x13 || opcode == 0x15 || opcode == 0x16
                    || opcode == 0x19 || opcode == 0x1a || opcode == 0x1c
                    || opcode == 0x1f || opcode == 0x20 || opcode == 0x22
                    || opcode == 0x23 || opcode == 0x29
                    || opcode >= 0x2d && opcode <= 0x3d
                    || opcode >= 0x44 && opcode <= 0x6d
                    || opcode >= 0x90 && opcode <= 0xaf
                    || opcode >= 0xd0 && opcode <= 0xe2
                    || opcode == 0xfe || opcode == 0xff) {
                return 2;
            }

            require(opcode <= 0xe2);
            return 1;
        }

        private int checkedWidth(long width) throws LimitException {
            if (width <= 0 || width > MAX_DEX_CODE_UNITS) {
                throw new LimitException();
            }

            return (int) width;
        }

        private boolean isTypeReference(int opcode) {
            return opcode == 0x1c
                    || opcode == 0x1f
                    || opcode == 0x20
                    || opcode == 0x22
                    || opcode == 0x23
                    || opcode == 0x24
                    || opcode == 0x25;
        }

        private void classifyString(int index) throws FormatException {
            requireIndex(index, strings.length);
            String value = strings[index];
            referencedStrings.add(value);

            if (ENDPOINT.matcher(value).find()) {
                collector.add(
                        "network.endpoint_literal",
                        null,
                        PluginRiskEvidence.Source.DEX,
                        PluginRiskEvidence.Confidence.EXACT);
            }
        }

        private void markModernDynamic() {
            add(
                    "analysis.dynamic_target",
                    null,
                    PluginRiskEvidence.Confidence.UNKNOWN);
            add(
                    "analysis.obfuscated_or_dynamic",
                    null,
                    PluginRiskEvidence.Confidence.HEURISTIC);
        }

        private void classifyTypeIndex(int index) throws FormatException {
            requireIndex(index, types.length);
            classifyType(types[index]);
        }

        private void classifyField(int index) throws FormatException {
            requireIndex(index, fieldOwners.length);
            classifyType(types[fieldOwners[index]]);
            classifyType(types[fieldTypes[index]]);
        }

        private void classifyMethod(int index) throws FormatException {
            requireIndex(index, methodOwners.length);
            String owner = types[methodOwners[index]];
            String name = strings[methodNames[index]];
            String detail = semantic(owner) + "." + name;
            classifyType(owner);
            String lowerOwner = owner.toLowerCase(Locale.ROOT);
            String lowerName = name.toLowerCase(Locale.ROOT);

            if (owner.equals("Lorg/fluxgram/JavaPluginContext;")
                    && name.equals("registerMethodHook")) {
                add("hook.managed_method", detail, PluginRiskEvidence.Confidence.EXACT);
            }
            if (lowerOwner.contains("xposed")) {
                add("hook.direct_xposed", detail, PluginRiskEvidence.Confidence.EXACT);
            }
            if (lowerOwner.contains("aliuhook") || lowerOwner.contains("lsplant")
                    || lowerName.contains("aliuhook") || lowerName.contains("lsplant")) {
                add("hook.direct_aliuhook", detail, PluginRiskEvidence.Confidence.EXACT);
            }
            if (owner.startsWith("Ljava/lang/reflect/")
                    || owner.equals("Ljava/lang/Class;") && (name.startsWith("getDeclared")
                            || name.equals("forName") || name.equals("newInstance"))) {
                add("reflection.general", detail, PluginRiskEvidence.Confidence.EXACT);
                dynamicLookup = true;
            }
            if (owner.equals("Ljava/lang/reflect/AccessibleObject;")
                    && (name.equals("setAccessible") || name.equals("trySetAccessible"))) {
                add("reflection.access_override", detail, PluginRiskEvidence.Confidence.EXACT);
            }
            if (owner.startsWith("Ldalvik/system/") && owner.contains("ClassLoader")
                    || owner.equals("Ljava/lang/ClassLoader;") && name.equals("loadClass")) {
                add("code.dynamic_class_loading", detail, PluginRiskEvidence.Confidence.EXACT);
                dynamicLookup = true;
            }
            if ((owner.equals("Ljava/lang/System;") || owner.equals("Ljava/lang/Runtime;"))
                    && (name.equals("load") || name.equals("loadLibrary"))) {
                add("code.native_loading", detail, PluginRiskEvidence.Confidence.EXACT);
            }
            if (owner.equals("Ljava/lang/Runtime;") && name.equals("exec")
                    || owner.equals("Ljava/lang/ProcessBuilder;")) {
                add("process.shell_execution", detail, PluginRiskEvidence.Confidence.EXACT);
            }
            if (isThreadType(owner)) {
                add("thread.unmanaged", detail, PluginRiskEvidence.Confidence.EXACT);
            }
        }

        private void classifyType(String descriptor) {
            String detail = semantic(descriptor);
            String lower = descriptor.toLowerCase(Locale.ROOT);

            if (descriptor.startsWith("Lorg/telegram/")) {
                add("host.telegram_internal_direct", detail, PluginRiskEvidence.Confidence.EXACT);
            }
            if (descriptor.startsWith("Lorg/telegram/messenger/plugins/")) {
                add("host.plugin_internal_direct", detail, PluginRiskEvidence.Confidence.EXACT);
            }
            if (lower.contains("xposed")) {
                add("hook.direct_xposed", detail, PluginRiskEvidence.Confidence.EXACT);
            }
            if (lower.contains("aliuhook") || lower.contains("lsplant")) {
                add("hook.direct_aliuhook", detail, PluginRiskEvidence.Confidence.EXACT);
            }
            if (descriptor.startsWith("Ljava/lang/reflect/")) {
                add("reflection.general", detail, PluginRiskEvidence.Confidence.EXACT);
            }
            if (descriptor.startsWith("Ldalvik/system/") && descriptor.contains("ClassLoader")) {
                add("code.dynamic_class_loading", detail, PluginRiskEvidence.Confidence.EXACT);
                dynamicLookup = true;
            }
            if (isThreadType(descriptor)) {
                add("thread.unmanaged", detail, PluginRiskEvidence.Confidence.EXACT);
            }
            if (descriptor.startsWith("Ljava/net/")
                    || descriptor.startsWith("Ljavax/net/")
                    || descriptor.startsWith("Lokhttp3/")
                    || descriptor.startsWith("Lorg/apache/http/")) {
                add("network.raw", detail, PluginRiskEvidence.Confidence.EXACT);
            }
            if (descriptor.equals("Ljava/io/File;")
                    || descriptor.startsWith("Ljava/io/FileInputStream;")
                    || descriptor.startsWith("Ljava/io/FileOutputStream;")
                    || descriptor.startsWith("Ljava/io/RandomAccessFile;")
                    || descriptor.startsWith("Ljava/nio/file/")) {
                add("filesystem.unmanaged", detail, PluginRiskEvidence.Confidence.EXACT);
            }
            if (descriptor.startsWith("Landroid/view/")
                    || descriptor.startsWith("Landroid/widget/")
                    || descriptor.startsWith("Landroid/app/")
                    || descriptor.startsWith("Landroid/graphics/")) {
                add("ui.direct_android", detail, PluginRiskEvidence.Confidence.EXACT);
            } else if (descriptor.startsWith("Landroid/")) {
                add("android.sensitive_api", detail, PluginRiskEvidence.Confidence.EXACT);
            }
        }

        private void classifyDynamicStrings() {
            if (!dynamicLookup) {
                return;
            }

            String target = null;
            boolean obfuscated = false;

            for (String value : referencedStrings) {
                if (CLASS_NAME.matcher(value).matches()) {
                    if (target == null) {
                        target = value;
                    }

                    String[] parts = value.split("[./]");
                    int shortParts = 0;

                    for (String part : parts) {
                        if (part.length() <= 2) {
                            shortParts++;
                        }
                    }

                    obfuscated |= parts.length >= 3 && shortParts == parts.length;

                    String normalized = value.replace('.', '/');

                    if (normalized.startsWith("org/telegram/")) {
                        add(
                                "host.telegram_internal_direct",
                                value,
                                PluginRiskEvidence.Confidence.HEURISTIC);
                    }
                    if (normalized.startsWith("org/telegram/messenger/plugins/")) {
                        add(
                                "host.plugin_internal_direct",
                                value,
                                PluginRiskEvidence.Confidence.HEURISTIC);
                    }
                }
            }

            add(
                    "analysis.dynamic_target",
                    target,
                    target == null
                            ? PluginRiskEvidence.Confidence.UNKNOWN
                            : PluginRiskEvidence.Confidence.HEURISTIC);

            if (obfuscated || target == null) {
                add(
                        "analysis.obfuscated_or_dynamic",
                        target,
                        PluginRiskEvidence.Confidence.HEURISTIC);
            }
        }

        private boolean isThreadType(String descriptor) {
            return descriptor.equals("Ljava/lang/Thread;")
                    || descriptor.equals("Ljava/util/Timer;")
                    || descriptor.startsWith("Ljava/util/concurrent/Executor")
                    || descriptor.startsWith("Ljava/util/concurrent/ThreadPool")
                    || descriptor.startsWith("Ljava/util/concurrent/ForkJoin")
                    || descriptor.equals("Landroid/os/HandlerThread;");
        }

        private String methodDetail(int index) {
            return semantic(types[methodOwners[index]]) + "." + strings[methodNames[index]];
        }

        private String semantic(String descriptor) {
            if (descriptor == null) {
                return null;
            }

            int arrays = 0;

            while (arrays < descriptor.length() && descriptor.charAt(arrays) == '[') {
                arrays++;
            }

            String value = descriptor.substring(arrays);

            if (value.startsWith("L") && value.endsWith(";")) {
                value = value.substring(1, value.length() - 1).replace('/', '.');
            }

            return bounded(value);
        }

        private void add(String id, String detail, PluginRiskEvidence.Confidence confidence) {
            collector.add(id, detail, PluginRiskEvidence.Source.DEX, confidence);
        }

        private int positive(int position, int maximum) throws FormatException, LimitException {
            int value = count(position, maximum);
            require(value > 0);
            return value;
        }

        private int count(int position, int maximum) throws FormatException, LimitException {
            requireRange(position, 4);
            long value = Integer.toUnsignedLong(dex.getInt(position));

            if (value > maximum) {
                throw new LimitException();
            }

            return (int) value;
        }

        private int offset(int position) throws FormatException {
            requireRange(position, 4);
            int value = dex.getInt(position);
            require(value >= 112 && value < data.length);
            return value;
        }

        private int offsetOrZero(int position, int count) throws FormatException {
            requireRange(position, 4);
            int value = dex.getInt(position);

            if (count == 0) {
                require(value == 0);
                return 0;
            }

            require(value >= 112 && value < data.length);
            return value;
        }

        private void table(int offset, int count, int width) throws FormatException {
            if (count == 0) {
                require(offset == 0);
                return;
            }

            require(offset >= 112 && (long) offset + (long) count * width <= data.length);
        }

        private void requireMappedOffset(int type, int offset) throws FormatException {
            MapItem item = mapItems.get(type);
            require(item != null && offset >= item.offset && offset < mapSectionEnd(item));
        }

        private int mapSectionEnd(MapItem item) {
            int result = data.length;

            for (MapItem candidate : mapItems.values()) {
                if (candidate.offset > item.offset && candidate.offset < result) {
                    result = candidate.offset;
                }
            }

            return result;
        }

        private void requireDataOffset(int offset) throws FormatException {
            require(offset >= dataOffset && offset < dataEnd);
        }

        private int ushort(int position) throws FormatException {
            requireRange(position, 2);
            return Short.toUnsignedInt(dex.getShort(position));
        }

        private int uint(int value) throws FormatException {
            require(value >= 112 && value < data.length);
            return value;
        }

        private int addIndex(int previous, int difference, int maximum) throws FormatException {
            long value = (long) previous + difference;
            require(value >= 0 && value < maximum);
            return (int) value;
        }

        private String decodeMutf8(int start, int end, int expectedLength)
                throws FormatException, LimitException {
            if (expectedLength > 4096) {
                throw new LimitException();
            }

            StringBuilder result = new StringBuilder(expectedLength);
            int position = start;

            while (position < end) {
                int first = data[position++] & 255;

                if (first != 0 && first < 0x80) {
                    result.append((char) first);
                    continue;
                }

                if ((first & 0xe0) == 0xc0) {
                    require(position < end);
                    int second = data[position++] & 255;
                    require((second & 0xc0) == 0x80);
                    int value = (first & 0x1f) << 6 | second & 0x3f;
                    require(value >= 0x80 || first == 0xc0 && second == 0x80);
                    result.append((char) value);
                    continue;
                }

                if ((first & 0xf0) == 0xe0) {
                    require(position + 1 < end);
                    int second = data[position++] & 255;
                    int third = data[position++] & 255;
                    require((second & 0xc0) == 0x80 && (third & 0xc0) == 0x80);
                    int value = (first & 0x0f) << 12 | (second & 0x3f) << 6 | third & 0x3f;
                    require(value >= 0x800);
                    result.append((char) value);
                    continue;
                }

                throw new FormatException();
            }

            require(result.length() == expectedLength);
            return result.toString();
        }

        private boolean isTypeDescriptor(String descriptor) {
            int position = 0;

            while (position < descriptor.length() && descriptor.charAt(position) == '[') {
                position++;
            }

            if (position > 255 || position >= descriptor.length()) {
                return false;
            }

            String value = descriptor.substring(position);

            if (value.length() == 1 && "VZBSCIJFD".indexOf(value.charAt(0)) >= 0) {
                return position == 0 || !value.equals("V");
            }

            if (!value.startsWith("L") || !value.endsWith(";") || value.length() < 3) {
                return false;
            }

            for (int i = 1; i < value.length() - 1; i++) {
                char current = value.charAt(i);

                if (current == '.' || current == '[' || current == ';'
                        || Character.isISOControl(current)) {
                    return false;
                }
            }

            return true;
        }

        private void requireRange(int offset, int length) throws FormatException {
            require(offset >= 0 && length >= 0 && (long) offset + length <= data.length);
        }

        private void requireIndex(int index, int count) throws FormatException {
            require(index >= 0 && index < count);
        }

        private void require(boolean condition) throws FormatException {
            if (!condition) {
                throw new FormatException();
            }
        }

        private void checkDeadline(int iteration) throws LimitException {
            if ((iteration & 255) == 0 && System.nanoTime() - deadline > 0) {
                throw new LimitException();
            }
        }

        private final class Cursor {
            int position;

            Cursor(int position) throws FormatException {
                require(position >= 112 && position < data.length);
                this.position = position;
            }

            int uleb() throws FormatException {
                int result = 0;

                for (int shift = 0; shift < 35; shift += 7) {
                    require(position < data.length);
                    int value = data[position++] & 255;
                    result |= (value & 127) << shift;

                    if ((value & 128) == 0) {
                        return result;
                    }
                }

                throw new FormatException();
            }

            int sleb() throws FormatException {
                int result = 0;
                int value = 0;
                int shift = 0;

                do {
                    require(position < data.length && shift < 35);
                    value = data[position++] & 255;
                    result |= (value & 127) << shift;
                    shift += 7;
                } while ((value & 128) != 0);

                if (shift < 32 && (value & 64) != 0) {
                    result |= -1 << shift;
                }

                return result;
            }

            int ulebLimited(int maximum) throws FormatException, LimitException {
                long value = Integer.toUnsignedLong(uleb());

                if (value > maximum) {
                    throw new LimitException();
                }

                return (int) value;
            }
        }
    }

    private static final class MapItem {
        final int type;
        final int count;
        final int offset;

        MapItem(int type, int count, int offset) {
            this.type = type;
            this.count = count;
            this.offset = offset;
        }
    }

    private static final class FormatException extends Exception {
    }

    private static final class LimitException extends Exception {
    }
}
