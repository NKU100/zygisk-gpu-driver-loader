package io.github.nku100.gpudriver.importer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeNoException;

public final class DriverPathHelperTest {
    private static final byte[] META = (
            "{\"name\":\"Mesa Turnip\",\"libraryName\":\"vulkan.ad07xx.so\",\"abi\":\"arm64-v8a\"}"
    ).getBytes(StandardCharsets.UTF_8);

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void listsOnlyDirectoriesAndZipFilesInStableOrder() throws Exception {
        Path shared = temporaryFolder.newFolder("shared").toPath();
        Files.createDirectory(shared.resolve("z-dir"));
        Files.createDirectory(shared.resolve("A dir"));
        Files.write(shared.resolve("b.ZIP"), new byte[]{1});
        Files.write(shared.resolve("a.txt"), new byte[]{2});
        Files.write(shared.resolve("a.zip"), new byte[]{3});

        List<DriverPathHelper.PathEntry> entries = DriverPathHelper.listDirectory(shared, shared);

        assertEquals(Arrays.asList("A dir/", "z-dir/", "a.zip", "b.ZIP"), display(entries));
    }

    @Test
    public void returnsNamesWithSpacesAndUnicode() throws Exception {
        Path shared = temporaryFolder.newFolder("共享 空间").toPath();
        Files.createDirectory(shared.resolve("驱动 包"));
        Files.write(shared.resolve("Turnip 驱动.zip"), new byte[]{1});

        List<DriverPathHelper.PathEntry> entries = DriverPathHelper.listDirectory(shared, shared);

        assertEquals(Arrays.asList("驱动 包/", "Turnip 驱动.zip"), display(entries));
    }

    @Test
    public void rejectsPathsOutsideStorageRoot() throws Exception {
        Path shared = temporaryFolder.newFolder("shared").toPath();
        Path outside = temporaryFolder.newFolder("outside").toPath();

        assertFailure("INVALID_PATH", () -> DriverPathHelper.listDirectory(outside, shared));
    }

    @Test
    public void rejectsSymlinkedSource() throws Exception {
        Path shared = temporaryFolder.newFolder("shared").toPath();
        Path outsideZip = temporaryFolder.getRoot().toPath().resolve("outside.zip");
        writeZip(outsideZip, validEntries());
        Path link = shared.resolve("linked.zip");
        try {
            Files.createSymbolicLink(link, outsideZip);
        } catch (UnsupportedOperationException | IOException error) {
            assumeNoException(error);
        }

        Path drivers = temporaryFolder.newFolder("drivers").toPath();
        assertFailure("INVALID_PATH", () -> DriverPathHelper.prepareArchive(link, drivers, "a1", shared));
        assertDirectoryEmpty(drivers);
    }

    @Test
    public void preservesValidPackageBytesAndHashes() throws Exception {
        Path shared = temporaryFolder.newFolder("shared").toPath();
        Path zip = shared.resolve("Turnip package.zip");
        byte[] mainLibrary = arm64Elf("vulkan-driver");
        byte[] dependency = arm64Elf("dependency");
        writeZip(zip, Arrays.asList(
                RawEntry.file("meta.json", META),
                RawEntry.file("vulkan.ad07xx.so", mainLibrary),
                RawEntry.file("libutils.so", dependency),
                RawEntry.directory("docs/"),
                RawEntry.file("docs/readme.txt", "ignored".getBytes(StandardCharsets.UTF_8))
        ));
        Path drivers = temporaryFolder.newFolder("drivers").toPath();
        byte[] archiveBytes = Files.readAllBytes(zip);

        DriverPathHelper.PreparedImport prepared =
                DriverPathHelper.prepareArchive(zip, drivers, "a2", shared);

        assertEquals(sha256(archiveBytes), prepared.archiveSha256);
        assertArrayEquals(META, prepared.metaJson);
        assertEquals(Arrays.asList("meta.json", "vulkan.ad07xx.so", "libutils.so"), fileNames(prepared.files));
        assertEquals(5, prepared.entries.size());
        assertTrue(prepared.entries.get(3).directory);
        assertEquals(sha256(mainLibrary), fileHash(prepared.files, "vulkan.ad07xx.so"));
        assertEquals(sha256(dependency), fileHash(prepared.files, "libutils.so"));
        Path stage = drivers.resolve(prepared.stageName);
        assertArrayEquals(META, Files.readAllBytes(stage.resolve("meta.json")));
        assertArrayEquals(mainLibrary, Files.readAllBytes(stage.resolve("vulkan.ad07xx.so")));
        assertArrayEquals(dependency, Files.readAllBytes(stage.resolve("libutils.so")));
        assertFalse(Files.exists(stage.resolve("docs")));
    }

    @Test
    public void acceptsDownloadedZipOnlyFromNonceOwnedPrivatePath() throws Exception {
        Path shared = temporaryFolder.newFolder("shared").toPath();
        Path drivers = temporaryFolder.newFolder("drivers").toPath();
        Path downloaded = drivers.resolve(".download-a1.zip");
        byte[] archive = writeZip(downloaded, validEntries());

        DriverPathHelper.PreparedImport prepared =
                DriverPathHelper.prepareDownloadedArchive(downloaded, drivers, "a1");

        assertEquals(sha256(archive), prepared.archiveSha256);
        assertArrayEquals(META, prepared.metaJson);
        assertArrayEquals(arm64Elf("main"), Files.readAllBytes(drivers.resolve(prepared.stageName).resolve("vulkan.ad07xx.so")));
        assertTrue(Files.exists(downloaded));
        assertFailure("INVALID_PATH", () -> DriverPathHelper.prepareDownloadedArchive(
                shared.resolve(".download-a1.zip"), drivers, "a1"));
    }

    @Test
    public void rejectsTraversalDuplicateSymlinkAndBadCrc() throws Exception {
        Path shared = temporaryFolder.newFolder("shared").toPath();
        Path drivers = temporaryFolder.newFolder("drivers").toPath();

        assertRejectedArchive(shared, drivers, "b1", Arrays.asList(
                RawEntry.file("meta.json", META),
                RawEntry.file("vulkan.ad07xx.so", arm64Elf("main")),
                RawEntry.file("../escape.so", arm64Elf("escape"))
        ), "INVALID_ARCHIVE");

        assertRejectedArchive(shared, drivers, "b2", Arrays.asList(
                RawEntry.file("meta.json", META),
                RawEntry.file("meta.json", META),
                RawEntry.file("vulkan.ad07xx.so", arm64Elf("main"))
        ), "INVALID_ARCHIVE");

        assertRejectedArchive(shared, drivers, "b3", Arrays.asList(
                RawEntry.file("meta.json", META),
                RawEntry.file("vulkan.ad07xx.so", arm64Elf("main")),
                RawEntry.symlink("link.so", "vulkan.ad07xx.so")
        ), "UNSUPPORTED_ARCHIVE");

        assertRejectedArchive(shared, drivers, "b4", Arrays.asList(
                RawEntry.file("meta.json", META),
                RawEntry.withBadCrc("vulkan.ad07xx.so", arm64Elf("main"))
        ), "INVALID_ARCHIVE");
    }

    @Test
    public void rejectsMalformedCentralDirectoryAndUnsupportedFeatures() throws Exception {
        Path shared = temporaryFolder.newFolder("shared").toPath();
        Path drivers = temporaryFolder.newFolder("drivers").toPath();
        Path malformed = shared.resolve("malformed.zip");
        byte[] bytes = writeZip(malformed, validEntries());
        bytes[bytes.length - 22] = 0;
        Files.write(malformed, bytes);
        assertFailure("INVALID_ARCHIVE", () -> DriverPathHelper.prepareArchive(malformed, drivers, "c1", shared));
        assertDirectoryEmpty(drivers);

        Path encrypted = shared.resolve("encrypted.zip");
        writeZip(encrypted, Arrays.asList(
                RawEntry.file("meta.json", META),
                RawEntry.file("vulkan.ad07xx.so", arm64Elf("main")).withFlags(1)
        ));
        assertFailure("UNSUPPORTED_ARCHIVE", () -> DriverPathHelper.prepareArchive(encrypted, drivers, "c2", shared));
        assertDirectoryEmpty(drivers);

        Path zip64 = shared.resolve("zip64.zip");
        byte[] zip64Bytes = writeZip(zip64, validEntries());
        int eocd = zip64Bytes.length - 22;
        zip64Bytes[eocd + 10] = (byte) 0xff;
        zip64Bytes[eocd + 11] = (byte) 0xff;
        Files.write(zip64, zip64Bytes);
        assertFailure("UNSUPPORTED_ARCHIVE", () -> DriverPathHelper.prepareArchive(zip64, drivers, "c3", shared));
        assertDirectoryEmpty(drivers);
    }

    @Test
    public void preservesNonceCollisionsNotOwnedByThisImport() throws Exception {
        Path shared = temporaryFolder.newFolder("shared").toPath();
        Path zip = shared.resolve("valid.zip");
        writeZip(zip, validEntries());
        Path drivers = temporaryFolder.newFolder("drivers").toPath();

        Path existingSnapshot = drivers.resolve(".snapshot-e1.zip");
        byte[] snapshotMarker = "keep snapshot".getBytes(StandardCharsets.UTF_8);
        Files.write(existingSnapshot, snapshotMarker);
        assertFailure("ACCESS_DENIED", () -> DriverPathHelper.prepareArchive(zip, drivers, "e1", shared));
        assertArrayEquals(snapshotMarker, Files.readAllBytes(existingSnapshot));

        Path existingStage = Files.createDirectory(drivers.resolve(".stage-e2"));
        byte[] stageMarker = "keep stage".getBytes(StandardCharsets.UTF_8);
        Files.write(existingStage.resolve("marker"), stageMarker);
        assertFailure("STORAGE_ERROR", () -> DriverPathHelper.prepareArchive(zip, drivers, "e2", shared));
        assertArrayEquals(stageMarker, Files.readAllBytes(existingStage.resolve("marker")));
        try (java.util.stream.Stream<Path> children = Files.list(drivers)) {
            assertEquals(2, children.count());
        }
    }

    @Test
    public void rejectsExpandedSizeLimitAndCleansStage() throws Exception {
        Path shared = temporaryFolder.newFolder("shared").toPath();
        Path zip = shared.resolve("expanded.zip");
        writeZip(zip, Arrays.asList(
                RawEntry.file("meta.json", META),
                RawEntry.file("vulkan.ad07xx.so", arm64Elf("main")),
                RawEntry.file("large.txt", new byte[128])
        ));
        Path drivers = temporaryFolder.newFolder("drivers").toPath();

        assertFailure("LIMIT_EXCEEDED", () -> DriverPathHelper.prepareArchive(
                zip, drivers, "d1", shared, new DriverPathHelper.Limits(1024 * 1024, 4096, 16, 64 * 1024, 128, 64)
        ));
        assertDirectoryEmpty(drivers);
    }

    private static List<RawEntry> validEntries() {
        return Arrays.asList(
                RawEntry.file("meta.json", META),
                RawEntry.file("vulkan.ad07xx.so", arm64Elf("main"))
        );
    }

    private void assertRejectedArchive(Path shared, Path drivers, String nonce,
                                       List<RawEntry> entries, String expectedCode) throws Exception {
        Path zip = shared.resolve(nonce + ".zip");
        writeZip(zip, entries);
        assertFailure(expectedCode, () -> DriverPathHelper.prepareArchive(zip, drivers, nonce, shared));
        assertDirectoryEmpty(drivers);
    }

    private static void assertFailure(String expectedCode, ThrowingRunnable action) throws Exception {
        try {
            action.run();
            fail("Expected helper failure " + expectedCode);
        } catch (DriverPathHelper.HelperException error) {
            assertEquals(expectedCode, error.code);
        }
    }

    private static void assertDirectoryEmpty(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> children = Files.list(directory)) {
            assertEquals(0, children.count());
        }
    }

    private static List<String> display(List<DriverPathHelper.PathEntry> entries) {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for (DriverPathHelper.PathEntry entry : entries) {
            names.add(entry.name + (entry.directory ? "/" : ""));
        }
        return names;
    }

    private static List<String> fileNames(List<DriverPathHelper.PreparedFile> files) {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for (DriverPathHelper.PreparedFile file : files) names.add(file.name);
        return names;
    }

    private static String fileHash(List<DriverPathHelper.PreparedFile> files, String name) {
        for (DriverPathHelper.PreparedFile file : files) if (file.name.equals(name)) return file.sha256;
        return null;
    }

    private static byte[] arm64Elf(String label) {
        byte[] bytes = new byte[128];
        bytes[0] = 0x7f;
        bytes[1] = 'E';
        bytes[2] = 'L';
        bytes[3] = 'F';
        bytes[4] = 2;
        bytes[5] = 1;
        bytes[6] = 1;
        bytes[16] = 3;
        bytes[18] = (byte) 183;
        bytes[19] = 0;
        bytes[20] = 1;
        bytes[52] = 64;
        byte[] labelBytes = label.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(labelBytes, 0, bytes, 64, labelBytes.length);
        return bytes;
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder value = new StringBuilder(hash.length * 2);
        for (byte b : hash) value.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return value.toString();
    }

    private static byte[] writeZip(Path path, List<RawEntry> entries) throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        ByteArrayOutputStream central = new ByteArrayOutputStream();
        DataOutputStream localOut = new DataOutputStream(archive);
        DataOutputStream centralOut = new DataOutputStream(central);
        for (RawEntry entry : entries) {
            byte[] name = entry.name.getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(entry.bytes);
            long expectedCrc = entry.badCrc ? crc.getValue() ^ 1 : crc.getValue();
            long localOffset = archive.size();
            le32(localOut, 0x04034b50L);
            le16(localOut, 20);
            le16(localOut, entry.flags | 0x800);
            le16(localOut, 0);
            le16(localOut, 0);
            le16(localOut, 0);
            le32(localOut, expectedCrc);
            le32(localOut, entry.bytes.length);
            le32(localOut, entry.bytes.length);
            le16(localOut, name.length);
            le16(localOut, 0);
            localOut.write(name);
            localOut.write(entry.bytes);

            le32(centralOut, 0x02014b50L);
            le16(centralOut, (3 << 8) | 20);
            le16(centralOut, 20);
            le16(centralOut, entry.flags | 0x800);
            le16(centralOut, 0);
            le16(centralOut, 0);
            le16(centralOut, 0);
            le32(centralOut, expectedCrc);
            le32(centralOut, entry.bytes.length);
            le32(centralOut, entry.bytes.length);
            le16(centralOut, name.length);
            le16(centralOut, 0);
            le16(centralOut, 0);
            le16(centralOut, 0);
            le16(centralOut, 0);
            le32(centralOut, ((long) entry.mode) << 16);
            le32(centralOut, localOffset);
            centralOut.write(name);
        }
        int centralOffset = archive.size();
        archive.write(central.toByteArray());
        int centralSize = central.size();
        DataOutputStream end = new DataOutputStream(archive);
        le32(end, 0x06054b50L);
        le16(end, 0);
        le16(end, 0);
        le16(end, entries.size());
        le16(end, entries.size());
        le32(end, centralSize);
        le32(end, centralOffset);
        le16(end, 0);
        byte[] result = archive.toByteArray();
        Files.write(path, result);
        return result;
    }

    private static void le16(DataOutputStream output, int value) throws IOException {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }

    private static void le32(DataOutputStream output, long value) throws IOException {
        le16(output, (int) value);
        le16(output, (int) (value >>> 16));
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class RawEntry {
        final String name;
        final byte[] bytes;
        final int mode;
        final int flags;
        final boolean badCrc;

        private RawEntry(String name, byte[] bytes, int mode, int flags, boolean badCrc) {
            this.name = name;
            this.bytes = bytes;
            this.mode = mode;
            this.flags = flags;
            this.badCrc = badCrc;
        }

        static RawEntry file(String name, byte[] bytes) {
            return new RawEntry(name, bytes, 0100644, 0, false);
        }

        static RawEntry symlink(String name, String target) {
            return new RawEntry(name, target.getBytes(StandardCharsets.UTF_8), 0120777, 0, false);
        }

        static RawEntry directory(String name) {
            return new RawEntry(name, new byte[0], 0040755, 0, false);
        }

        static RawEntry withBadCrc(String name, byte[] bytes) {
            return new RawEntry(name, bytes, 0100644, 0, true);
        }

        RawEntry withFlags(int flags) {
            return new RawEntry(name, bytes, mode, flags, badCrc);
        }
    }
}
