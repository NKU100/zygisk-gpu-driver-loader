package io.github.nku100.gpudriver.importer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public final class DriverPathHelper {
    private static final String PROTOCOL = "DRIVER_IMPORT_V1";
    private static final Path SHARED_ROOT = Paths.get("/storage/emulated/0");
    private static final Limits DEFAULT_LIMITS = new Limits(
            512L * 1024 * 1024, 4L * 1024 * 1024, 4096, 64 * 1024, 128, 512L * 1024 * 1024);
    private static final int UTF8_FLAG = 1 << 11;
    private static final int DATA_DESCRIPTOR_FLAG = 1 << 3;
    private static final Charset CP437 = Charset.forName("Cp437");

    private DriverPathHelper() {}

    public static void main(String[] args) {
        PrintStream output = System.out;
        try {
            if (args.length == 2 && "list".equals(args[0])) {
                List<PathEntry> entries = listDirectory(Paths.get(args[1]), SHARED_ROOT);
                output.println(PROTOCOL + "\tOK\tLIST");
                for (PathEntry entry : entries) {
                    output.println((entry.directory ? "DIR" : "ZIP") + "\t" + encode(entry.name));
                }
                return;
            }
            if (args.length == 4 && "prepare".equals(args[0])) {
                PreparedImport prepared = prepareArchive(
                        Paths.get(args[1]), Paths.get(args[2]), args[3], SHARED_ROOT);
                output.println(PROTOCOL + "\tOK\tPREPARE");
                output.println("ARCHIVE\t" + prepared.archiveSha256);
                if (prepared.metaJson != null) output.println("META\t" + Base64.getEncoder().encodeToString(prepared.metaJson));
                for (ArchiveEntry entry : prepared.entries) {
                    output.println("ENTRY\t" + encode(entry.path) + "\t" + (entry.regular ? "1" : "0") + "\t0");
                }
                for (PreparedFile file : prepared.files) {
                    output.println("FILE\t" + encode(file.name) + "\t" + file.size + "\t" + file.sha256);
                }
                output.println("STAGE\t" + prepared.stageName);
                return;
            }
            throw new HelperException("INVALID_ARGUMENT");
        } catch (HelperException error) {
            output.println(PROTOCOL + "\tERROR\t" + error.code);
            System.exit(2);
        } catch (Throwable error) {
            output.println(PROTOCOL + "\tERROR\tSTORAGE_ERROR");
            System.exit(2);
        }
    }

    static List<PathEntry> listDirectory(Path requestedPath, Path storageRoot) throws HelperException {
        Path root = realPath(storageRoot, "ACCESS_DENIED");
        Path requested = requestedPath.toAbsolutePath().normalize();
        Path lexicalRoot = storageRoot.toAbsolutePath().normalize();
        if (!requested.startsWith(lexicalRoot)) throw new HelperException("INVALID_PATH");
        Path directory = realPath(requested, "ACCESS_DENIED");
        if (!directory.startsWith(root)) throw new HelperException("INVALID_PATH");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new HelperException("INVALID_PATH");

        List<PathEntry> entries = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (!isDisplayable(name)) continue;
                if (Files.isSymbolicLink(child)) {
                    Path target;
                    try {
                        target = child.toRealPath();
                    } catch (IOException error) {
                        continue;
                    }
                    if (!target.startsWith(root)) continue;
                    if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) entries.add(new PathEntry(name, true));
                    continue;
                }
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    entries.add(new PathEntry(name, true));
                } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) &&
                        name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    entries.add(new PathEntry(name, false));
                }
            }
        } catch (SecurityException error) {
            throw new HelperException("ACCESS_DENIED", error);
        } catch (IOException error) {
            throw new HelperException("ACCESS_DENIED", error);
        }
        entries.sort(Comparator
                .comparing((PathEntry entry) -> !entry.directory)
                .thenComparing(entry -> entry.name.toLowerCase(Locale.ROOT))
                .thenComparing(entry -> entry.name));
        return Collections.unmodifiableList(entries);
    }

    static PreparedImport prepareArchive(Path requestedZip, Path driversRoot, String nonce,
                                         Path storageRoot) throws HelperException {
        return prepareArchive(requestedZip, driversRoot, nonce, storageRoot, DEFAULT_LIMITS);
    }

    static PreparedImport prepareArchive(Path requestedZip, Path driversRoot, String nonce,
                                         Path storageRoot, Limits limits) throws HelperException {
        if (nonce == null || !nonce.matches("[0-9a-f]{1,64}")) throw new HelperException("INVALID_ARGUMENT");
        Path shared = realPath(storageRoot, "ACCESS_DENIED");
        Path lexicalRoot = storageRoot.toAbsolutePath().normalize();
        Path lexicalZip = requestedZip.toAbsolutePath().normalize();
        if (!lexicalZip.startsWith(lexicalRoot) || Files.isSymbolicLink(lexicalZip)) {
            throw new HelperException("INVALID_PATH");
        }
        Path zip;
        try {
            Path realParent = lexicalZip.getParent().toRealPath();
            zip = realParent.resolve(lexicalZip.getFileName()).normalize();
        } catch (IOException error) {
            throw new HelperException("ACCESS_DENIED", error);
        }
        if (!zip.startsWith(shared)) throw new HelperException("INVALID_PATH");
        BasicFileAttributes sourceAttributes;
        try {
            sourceAttributes = Files.readAttributes(zip, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException error) {
            throw new HelperException("ACCESS_DENIED", error);
        }
        if (!sourceAttributes.isRegularFile() || sourceAttributes.isSymbolicLink()) {
            throw new HelperException("INVALID_PATH");
        }
        if (sourceAttributes.size() <= 0) throw new HelperException("INVALID_ARCHIVE");
        if (sourceAttributes.size() > limits.maxArchiveBytes) throw new HelperException("LIMIT_EXCEEDED");

        Path root;
        try {
            Files.createDirectories(driversRoot);
            root = driversRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException error) {
            throw new HelperException("STORAGE_ERROR", error);
        }
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new HelperException("STORAGE_ERROR");
        }

        Path snapshot = root.resolve(".snapshot-" + nonce + ".zip");
        Path stage = root.resolve(".stage-" + nonce);
        boolean snapshotOwned = false;
        boolean stageOwned = false;
        boolean succeeded = false;
        try {
            copySnapshot(zip, snapshot, limits.maxArchiveBytes);
            snapshotOwned = true;
            Archive archive;
            try {
                archive = readArchive(snapshot, limits);
            } catch (HelperException error) {
                throw error;
            } catch (IOException error) {
                throw new HelperException("INVALID_ARCHIVE", error);
            }
            try {
                Files.createDirectory(stage);
                stageOwned = true;
            } catch (IOException error) {
                throw new HelperException("STORAGE_ERROR", error);
            }
            PreparedImport result = extractArchive(snapshot, archive, stage, limits);
            succeeded = true;
            return result;
        } catch (HelperException error) {
            throw error;
        } catch (IOException error) {
            throw new HelperException("STORAGE_ERROR", error);
        } finally {
            if (snapshotOwned) deleteIfExists(snapshot);
            if (stageOwned && !succeeded) deleteTree(stage);
        }
    }

    private static void copySnapshot(Path source, Path snapshot, long maximumBytes) throws HelperException {
        long total = 0;
        boolean snapshotCreated = false;
        boolean completed = false;
        try (SeekableByteChannel input = Files.newByteChannel(
                source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            try (SeekableByteChannel output = Files.newByteChannel(snapshot,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                snapshotCreated = true;
                ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
                while (true) {
                    buffer.clear();
                    int count = input.read(buffer);
                    if (count < 0) break;
                    if (count == 0) continue;
                    total += count;
                    if (total > maximumBytes) throw new HelperException("LIMIT_EXCEEDED");
                    buffer.flip();
                    while (buffer.hasRemaining()) output.write(buffer);
                }
                if (total == 0) throw new HelperException("INVALID_ARCHIVE");
            }
            completed = true;
        } catch (HelperException error) {
            throw error;
        } catch (IOException error) {
            throw new HelperException("ACCESS_DENIED", error);
        } finally {
            if (snapshotCreated && !completed) deleteIfExists(snapshot);
        }
    }

    private static Archive readArchive(Path zipPath, Limits limits) throws IOException, HelperException {
        try (RandomAccessFile zip = new RandomAccessFile(zipPath.toFile(), "r")) {
            long fileLength = zip.length();
            if (fileLength < 22) throw new HelperException("INVALID_ARCHIVE");
            int tailLength = (int) Math.min(fileLength, 22L + 65535L);
            byte[] tail = new byte[tailLength];
            zip.seek(fileLength - tailLength);
            zip.readFully(tail);
            int endRecord = findEndRecord(tail);
            if (endRecord < 0) throw new HelperException("INVALID_ARCHIVE");
            long endOffset = fileLength - tailLength + endRecord;
            int diskNumber = u16(tail, endRecord + 4);
            int centralDisk = u16(tail, endRecord + 6);
            int diskEntries = u16(tail, endRecord + 8);
            int entryCount = u16(tail, endRecord + 10);
            long centralSize = u32(tail, endRecord + 12);
            long centralOffset = u32(tail, endRecord + 16);
            int commentLength = u16(tail, endRecord + 20);
            if (endRecord + 22 + commentLength != tailLength) throw new HelperException("INVALID_ARCHIVE");
            if (diskNumber != 0 || centralDisk != 0 || diskEntries != entryCount) {
                throw new HelperException("UNSUPPORTED_ARCHIVE");
            }
            if (entryCount == 0xffff || centralSize == 0xffffffffL || centralOffset == 0xffffffffL) {
                throw new HelperException("UNSUPPORTED_ARCHIVE");
            }
            if (entryCount > limits.maxEntries || centralSize > limits.maxCentralDirectoryBytes) {
                throw new HelperException("LIMIT_EXCEEDED");
            }
            if (centralOffset + centralSize != endOffset || centralOffset > fileLength) {
                throw new HelperException("INVALID_ARCHIVE");
            }

            List<ArchiveEntry> entries = new ArrayList<>(entryCount);
            Set<String> paths = new HashSet<>();
            long declaredExpanded = 0;
            zip.seek(centralOffset);
            for (int index = 0; index < entryCount; index++) {
                byte[] header = new byte[46];
                zip.readFully(header);
                if (u32(header, 0) != 0x02014b50L) throw new HelperException("INVALID_ARCHIVE");
                int madeBy = u16(header, 4);
                int requiredVersion = u16(header, 6);
                int flags = u16(header, 8);
                int method = u16(header, 10);
                long crc = u32(header, 16);
                long compressedSize = u32(header, 20);
                long expandedSize = u32(header, 24);
                int nameLength = u16(header, 28);
                int extraLength = u16(header, 30);
                int entryCommentLength = u16(header, 32);
                int diskStart = u16(header, 34);
                long externalAttributes = u32(header, 38);
                long localOffset = u32(header, 42);
                if (requiredVersion > 20 || diskStart != 0 || compressedSize == 0xffffffffL ||
                        expandedSize == 0xffffffffL || localOffset == 0xffffffffL) {
                    throw new HelperException("UNSUPPORTED_ARCHIVE");
                }
                checkCompression(flags, method);
                if (expandedSize > limits.maxExpandedBytes - declaredExpanded) {
                    throw new HelperException("LIMIT_EXCEEDED");
                }
                declaredExpanded += expandedSize;
                byte[] nameBytes = new byte[nameLength];
                byte[] extra = new byte[extraLength];
                zip.readFully(nameBytes);
                zip.readFully(extra);
                skipFully(zip, entryCommentLength);
                rejectZip64Extra(extra);
                String path = decodeName(nameBytes, flags);
                boolean trailingSlash = path.endsWith("/");
                String normalized = normalizeArchivePath(path);
                int operatingSystem = madeBy >>> 8;
                int mode = (int) (externalAttributes >>> 16);
                int fileType = mode & 0170000;
                boolean symbolicLink = (operatingSystem == 3 || operatingSystem == 19) && fileType == 0120000;
                if (symbolicLink) throw new HelperException("UNSUPPORTED_ARCHIVE");
                boolean directory;
                if (fileType == 0040000) directory = true;
                else if (fileType == 0100000 || fileType == 0) directory = trailingSlash;
                else throw new HelperException("UNSUPPORTED_ARCHIVE");
                if (directory != trailingSlash || (directory && (compressedSize != 0 || expandedSize != 0))) {
                    throw new HelperException("INVALID_ARCHIVE");
                }
                if (!paths.add(normalized)) throw new HelperException("INVALID_ARCHIVE");
                ArchiveEntry entry = new ArchiveEntry(path, normalized, !directory, directory,
                        flags, method, crc, compressedSize, expandedSize, localOffset, nameBytes);
                long nextCentralEntry = zip.getFilePointer();
                readLocalHeader(zip, entry, centralOffset);
                zip.seek(nextCentralEntry);
                entries.add(entry);
            }
            if (zip.getFilePointer() != centralOffset + centralSize) throw new HelperException("INVALID_ARCHIVE");
            validateEntryTree(entries);
            validateEntryRanges(entries, centralOffset, zip);
            return new Archive(entries, centralOffset);
        }
    }

    private static PreparedImport extractArchive(Path zipPath, Archive archive, Path stage,
                                                  Limits limits) throws IOException, HelperException {
        List<PreparedFile> files = new ArrayList<>();
        byte[] metadata = null;
        int libraryCount = 0;
        long[] totalExpanded = {0};
        String archiveHash = sha256(zipPath);
        try (RandomAccessFile zip = new RandomAccessFile(zipPath.toFile(), "r")) {
            for (ArchiveEntry entry : archive.entries) {
                boolean isMetadata = entry.regular && "meta.json".equals(entry.normalizedPath);
                boolean isRootLibrary = entry.regular && entry.normalizedPath.indexOf('/') < 0 &&
                        entry.normalizedPath.endsWith(".so");
                if (isRootLibrary && ++libraryCount > limits.maxLibraries) throw new HelperException("LIMIT_EXCEEDED");
                boolean extract = isMetadata || isRootLibrary;
                Path outputPath = extract ? stage.resolve(entry.normalizedPath) : null;
                if (outputPath != null) {
                    if (!outputPath.getParent().equals(stage)) throw new HelperException("INVALID_ARCHIVE");
                    if (isMetadata && entry.uncompressedSize > limits.maxMetadataBytes) {
                        throw new HelperException("LIMIT_EXCEEDED");
                    }
                }
                MessageDigest digest = extract ? newSha256() : null;
                ByteArrayOutputStream metadataBuffer = isMetadata ? new ByteArrayOutputStream() : null;
                OutputStream output = null;
                try {
                    if (extract) output = Files.newOutputStream(outputPath,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    long actualSize = streamEntry(zip, entry, output, metadataBuffer, digest, totalExpanded, limits);
                    if (extract) {
                        String hash = hex(digest.digest());
                        files.add(new PreparedFile(entry.normalizedPath, actualSize, hash));
                        if (isMetadata) metadata = metadataBuffer.toByteArray();
                        if (isRootLibrary && !isArm64Elf(outputPath)) {
                            throw new HelperException("INVALID_DRIVER_LIBRARY");
                        }
                    }
                } finally {
                    if (output != null) output.close();
                }
            }
        } catch (HelperException error) {
            throw error;
        } catch (IOException error) {
            throw new HelperException("STORAGE_ERROR", error);
        }
        return new PreparedImport(archiveHash, metadata, archive.entries, files, stage.getFileName().toString());
    }

    private static long streamEntry(RandomAccessFile zip, ArchiveEntry entry, OutputStream output,
                                    ByteArrayOutputStream metadata, MessageDigest digest,
                                    long[] totalExpanded, Limits limits) throws IOException, HelperException {
        CRC32 crc = new CRC32();
        long[] actualSize = {0};
        zip.seek(entry.dataOffset);
        if (entry.method == 0) {
            long remaining = entry.compressedSize;
            byte[] buffer = new byte[32 * 1024];
            while (remaining > 0) {
                int count = (int) Math.min(buffer.length, remaining);
                zip.readFully(buffer, 0, count);
                emit(buffer, count, output, metadata, digest, crc, actualSize, totalExpanded, limits);
                remaining -= count;
            }
        } else if (entry.method == 8) {
            Inflater inflater = new Inflater(true);
            try {
                long unreadCompressed = entry.compressedSize;
                long suppliedCompressed = 0;
                byte[] compressed = new byte[32 * 1024];
                byte[] expanded = new byte[32 * 1024];
                while (!inflater.finished()) {
                    if (inflater.needsInput()) {
                        if (unreadCompressed == 0) throw new HelperException("INVALID_ARCHIVE");
                        int count = (int) Math.min(compressed.length, unreadCompressed);
                        zip.readFully(compressed, 0, count);
                        inflater.setInput(compressed, 0, count);
                        unreadCompressed -= count;
                        suppliedCompressed += count;
                    }
                    int count;
                    try {
                        count = inflater.inflate(expanded);
                    } catch (DataFormatException error) {
                        throw new HelperException("INVALID_ARCHIVE", error);
                    }
                    if (count > 0) {
                        emit(expanded, count, output, metadata, digest, crc, actualSize, totalExpanded, limits);
                    } else if (inflater.needsDictionary() || (!inflater.needsInput() && !inflater.finished())) {
                        throw new HelperException("INVALID_ARCHIVE");
                    }
                }
                if (unreadCompressed != 0 || inflater.getRemaining() != 0 ||
                        suppliedCompressed != entry.compressedSize) throw new HelperException("INVALID_ARCHIVE");
            } finally {
                inflater.end();
            }
        } else {
            throw new HelperException("UNSUPPORTED_ARCHIVE");
        }
        if (actualSize[0] != entry.uncompressedSize || crc.getValue() != entry.crc) {
            throw new HelperException("INVALID_ARCHIVE");
        }
        if (entry.directory && actualSize[0] != 0) throw new HelperException("INVALID_ARCHIVE");
        return actualSize[0];
    }

    private static void emit(byte[] bytes, int count, OutputStream output, ByteArrayOutputStream metadata,
                             MessageDigest digest, CRC32 crc, long[] actualSize, long[] totalExpanded,
                             Limits limits) throws IOException, HelperException {
        if (actualSize[0] > limits.maxExpandedBytes - count ||
                totalExpanded[0] > limits.maxExpandedBytes - count) {
            throw new HelperException("LIMIT_EXCEEDED");
        }
        actualSize[0] += count;
        totalExpanded[0] += count;
        if (metadata != null && actualSize[0] > limits.maxMetadataBytes) throw new HelperException("LIMIT_EXCEEDED");
        if (digest != null) digest.update(bytes, 0, count);
        crc.update(bytes, 0, count);
        if (output != null) output.write(bytes, 0, count);
        if (metadata != null) metadata.write(bytes, 0, count);
    }

    private static void readLocalHeader(RandomAccessFile zip, ArchiveEntry entry, long centralOffset)
            throws IOException, HelperException {
        if (entry.localOffset < 0 || entry.localOffset + 30 > centralOffset) {
            throw new HelperException("INVALID_ARCHIVE");
        }
        zip.seek(entry.localOffset);
        byte[] header = new byte[30];
        zip.readFully(header);
        if (u32(header, 0) != 0x04034b50L) throw new HelperException("INVALID_ARCHIVE");
        int version = u16(header, 4);
        int flags = u16(header, 6);
        int method = u16(header, 8);
        long crc = u32(header, 14);
        long compressedSize = u32(header, 18);
        long expandedSize = u32(header, 22);
        int nameLength = u16(header, 26);
        int extraLength = u16(header, 28);
        if (version > 20 || flags != entry.flags || method != entry.method ||
                entry.localOffset + 30L + nameLength + extraLength > centralOffset) {
            throw new HelperException("INVALID_ARCHIVE");
        }
        byte[] name = new byte[nameLength];
        byte[] extra = new byte[extraLength];
        zip.readFully(name);
        zip.readFully(extra);
        rejectZip64Extra(extra);
        if (!Arrays.equals(name, entry.nameBytes)) throw new HelperException("INVALID_ARCHIVE");
        if ((flags & DATA_DESCRIPTOR_FLAG) == 0) {
            if (crc != entry.crc || compressedSize != entry.compressedSize || expandedSize != entry.uncompressedSize) {
                throw new HelperException("INVALID_ARCHIVE");
            }
        } else if ((crc != 0 && crc != entry.crc) ||
                (compressedSize != 0 && compressedSize != entry.compressedSize) ||
                (expandedSize != 0 && expandedSize != entry.uncompressedSize) ||
                compressedSize == 0xffffffffL || expandedSize == 0xffffffffL) {
            throw new HelperException("INVALID_ARCHIVE");
        }
        entry.dataOffset = entry.localOffset + 30L + nameLength + extraLength;
    }

    private static void validateEntryRanges(List<ArchiveEntry> entries, long centralOffset, RandomAccessFile zip)
            throws IOException, HelperException {
        List<ArchiveEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingLong(entry -> entry.localOffset));
        for (int index = 0; index < sorted.size(); index++) {
            ArchiveEntry entry = sorted.get(index);
            long boundary = index + 1 < sorted.size() ? sorted.get(index + 1).localOffset : centralOffset;
            long dataEnd = entry.dataOffset + entry.compressedSize;
            if (entry.dataOffset < entry.localOffset || dataEnd > boundary || boundary > centralOffset) {
                throw new HelperException("INVALID_ARCHIVE");
            }
            if (entry.localOffset == boundary) throw new HelperException("INVALID_ARCHIVE");
            if ((entry.flags & DATA_DESCRIPTOR_FLAG) != 0) validateDataDescriptor(zip, entry, dataEnd, boundary);
        }
    }

    private static void validateDataDescriptor(RandomAccessFile zip, ArchiveEntry entry, long offset, long boundary)
            throws IOException, HelperException {
        if (offset + 12 > boundary) throw new HelperException("INVALID_ARCHIVE");
        zip.seek(offset);
        byte[] descriptor = new byte[(int) Math.min(16, boundary - offset)];
        zip.readFully(descriptor);
        int dataAt = u32(descriptor, 0) == 0x08074b50L ? 4 : 0;
        if (dataAt + 12 > descriptor.length || u32(descriptor, dataAt) != entry.crc ||
                u32(descriptor, dataAt + 4) != entry.compressedSize ||
                u32(descriptor, dataAt + 8) != entry.uncompressedSize) {
            throw new HelperException("INVALID_ARCHIVE");
        }
    }

    private static void validateEntryTree(List<ArchiveEntry> entries) throws HelperException {
        Set<String> regularFiles = new HashSet<>();
        for (ArchiveEntry entry : entries) if (entry.regular) regularFiles.add(entry.normalizedPath);
        for (ArchiveEntry entry : entries) {
            String path = entry.normalizedPath;
            int separator = path.indexOf('/');
            while (separator >= 0) {
                String parent = path.substring(0, separator);
                if (regularFiles.contains(parent)) throw new HelperException("INVALID_ARCHIVE");
                separator = path.indexOf('/', separator + 1);
            }
        }
    }

    private static int findEndRecord(byte[] tail) {
        for (int index = tail.length - 22; index >= 0; index--) {
            if (u32(tail, index) == 0x06054b50L && index + 22 + u16(tail, index + 20) == tail.length) return index;
        }
        return -1;
    }

    private static void checkCompression(int flags, int method) throws HelperException {
        int unsupportedFlags = flags & ~(UTF8_FLAG | DATA_DESCRIPTOR_FLAG | 0x0006);
        if (unsupportedFlags != 0) throw new HelperException("UNSUPPORTED_ARCHIVE");
        if (method != 0 && method != 8) throw new HelperException("UNSUPPORTED_ARCHIVE");
        if (method == 0 && (flags & 0x0006) != 0) throw new HelperException("UNSUPPORTED_ARCHIVE");
    }

    private static void rejectZip64Extra(byte[] extra) throws HelperException {
        int offset = 0;
        while (offset < extra.length) {
            if (offset + 4 > extra.length) throw new HelperException("INVALID_ARCHIVE");
            int id = u16(extra, offset);
            int size = u16(extra, offset + 2);
            offset += 4;
            if (offset + size > extra.length) throw new HelperException("INVALID_ARCHIVE");
            if (id == 0x0001) throw new HelperException("UNSUPPORTED_ARCHIVE");
            offset += size;
        }
    }

    private static String decodeName(byte[] bytes, int flags) throws HelperException {
        CharsetDecoder decoder = ((flags & UTF8_FLAG) != 0 ? StandardCharsets.UTF_8 : CP437)
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new HelperException("INVALID_ARCHIVE", error);
        }
    }

    private static String normalizeArchivePath(String path) throws HelperException {
        if (path.isEmpty() || path.startsWith("/") || path.startsWith("\\") ||
                path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0 || containsControl(path)) {
            throw new HelperException("INVALID_ARCHIVE");
        }
        if (path.length() >= 3 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':' && path.charAt(2) == '/') {
            throw new HelperException("INVALID_ARCHIVE");
        }
        String normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (normalized.isEmpty()) throw new HelperException("INVALID_ARCHIVE");
        String[] components = normalized.split("/", -1);
        for (String component : components) {
            if (component.isEmpty() || ".".equals(component) || "..".equals(component)) {
                throw new HelperException("INVALID_ARCHIVE");
            }
        }
        return normalized;
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) return true;
        }
        return false;
    }

    private static boolean isDisplayable(String name) {
        return !name.isEmpty() && !".".equals(name) && !"..".equals(name) && !containsControl(name);
    }

    private static boolean isArm64Elf(Path file) throws IOException {
        byte[] header = new byte[64];
        int count = 0;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            while (count < header.length) {
                int read = input.read(header, count, header.length - count);
                if (read < 0) break;
                count += read;
            }
        }
        return count == header.length && header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' &&
                header[3] == 'F' && header[4] == 2 && header[5] == 1 && header[6] == 1 &&
                u16(header, 16) == 3 && u16(header, 18) == 183 && u32(header, 20) == 1 &&
                u16(header, 52) == 64;
    }

    private static Path realPath(Path path, String errorCode) throws HelperException {
        try {
            return path.toRealPath();
        } catch (IOException | SecurityException error) {
            throw new HelperException(errorCode, error);
        }
    }

    private static void skipFully(RandomAccessFile file, int count) throws IOException, HelperException {
        long target = file.getFilePointer() + count;
        if (target > file.length()) throw new HelperException("INVALID_ARCHIVE");
        file.seek(target);
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            int unsigned = value & 0xff;
            result.append(Character.forDigit(unsigned >>> 4, 16));
            result.append(Character.forDigit(unsigned & 0xf, 16));
        }
        return result.toString();
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static long u32(byte[] bytes, int offset) {
        return (u16(bytes, offset) & 0xffffL) | ((u16(bytes, offset + 2) & 0xffffL) << 16);
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup is constrained to nonce-owned paths.
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(DriverPathHelper::deleteIfExists);
        } catch (IOException ignored) {
            // Best-effort cleanup is constrained to nonce-owned paths.
        }
    }

    static final class PathEntry {
        final String name;
        final boolean directory;

        PathEntry(String name, boolean directory) {
            this.name = name;
            this.directory = directory;
        }
    }

    static final class PreparedImport {
        final String archiveSha256;
        final byte[] metaJson;
        final List<ArchiveEntry> entries;
        final List<PreparedFile> files;
        final String stageName;

        PreparedImport(String archiveSha256, byte[] metaJson, List<ArchiveEntry> entries,
                       List<PreparedFile> files, String stageName) {
            this.archiveSha256 = archiveSha256;
            this.metaJson = metaJson;
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.files = Collections.unmodifiableList(new ArrayList<>(files));
            this.stageName = stageName;
        }
    }

    static final class PreparedFile {
        final String name;
        final long size;
        final String sha256;

        PreparedFile(String name, long size, String sha256) {
            this.name = name;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    static final class ArchiveEntry {
        final String path;
        final String normalizedPath;
        final boolean regular;
        final boolean directory;
        final int flags;
        final int method;
        final long crc;
        final long compressedSize;
        final long uncompressedSize;
        final long localOffset;
        final byte[] nameBytes;
        long dataOffset;

        ArchiveEntry(String path, String normalizedPath, boolean regular, boolean directory,
                     int flags, int method, long crc, long compressedSize,
                     long uncompressedSize, long localOffset, byte[] nameBytes) {
            this.path = path;
            this.normalizedPath = normalizedPath;
            this.regular = regular;
            this.directory = directory;
            this.flags = flags;
            this.method = method;
            this.crc = crc;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.localOffset = localOffset;
            this.nameBytes = nameBytes;
        }
    }

    static final class HelperException extends IOException {
        final String code;

        HelperException(String code) {
            super(code);
            this.code = code;
        }

        HelperException(String code, Throwable cause) {
            super(code, cause);
            this.code = code;
        }
    }

    static final class Limits {
        final long maxArchiveBytes;
        final long maxCentralDirectoryBytes;
        final int maxEntries;
        final int maxMetadataBytes;
        final int maxLibraries;
        final long maxExpandedBytes;

        Limits(long maxArchiveBytes, long maxCentralDirectoryBytes, int maxEntries,
               int maxMetadataBytes, int maxLibraries, long maxExpandedBytes) {
            this.maxArchiveBytes = maxArchiveBytes;
            this.maxCentralDirectoryBytes = maxCentralDirectoryBytes;
            this.maxEntries = maxEntries;
            this.maxMetadataBytes = maxMetadataBytes;
            this.maxLibraries = maxLibraries;
            this.maxExpandedBytes = maxExpandedBytes;
        }
    }

    private static final class Archive {
        final List<ArchiveEntry> entries;
        final long centralOffset;

        Archive(List<ArchiveEntry> entries, long centralOffset) {
            this.entries = entries;
            this.centralOffset = centralOffset;
        }
    }
}
