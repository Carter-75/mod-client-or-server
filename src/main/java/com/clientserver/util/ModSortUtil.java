package com.clientserver.util;

import com.clientserver.ModClientOrServer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Groups installed mods by environment and exports each category as a zip archive.
 */
public final class ModSortUtil {

    private ModSortUtil() {
    }

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault());

    public enum SideCategory {
        CLIENT_ONLY("client"),
        SERVER_ONLY("server"),
        UNIVERSAL("both");

        private final String fileSegment;

        SideCategory(String fileSegment) {
            this.fileSegment = fileSegment;
        }

        public String fileSegment() {
            return fileSegment;
        }
    }

    public record ModDescriptor(String id, String name, String version, Path sourcePath, SideCategory category) {
    }

    public record SortResult(Map<SideCategory, List<ModDescriptor>> modsByCategory,
                             Map<SideCategory, Path> zipPaths,
                             Path outputDirectory,
                             Instant timestamp) {
        public int totalMods() {
            return modsByCategory.values().stream().mapToInt(List::size).sum();
        }

        public int count(SideCategory category) {
            return modsByCategory.getOrDefault(category, List.of()).size();
        }
    }

    public static SortResult sortModsIntoZips() throws IOException {
        FabricLoader loader = FabricLoader.getInstance();
        Path modsDir = loader.getGameDir().resolve("mods").normalize();
        if (!Files.exists(modsDir)) {
            throw new IOException("Mods directory not found: " + modsDir);
        }

        List<ModDescriptor> descriptors = collectModDescriptors(loader, modsDir);
        Map<SideCategory, List<ModDescriptor>> grouped = groupByCategory(descriptors);
        Path outputDir = Files.createDirectories(modsDir.resolve("mod-client-or-server"));
        Instant now = Instant.now();

        Map<SideCategory, Path> zipPaths = new EnumMap<>(SideCategory.class);
        for (SideCategory category : SideCategory.values()) {
            Path base = outputDir.resolve(category.fileSegment() + "-mods-" + FILE_TIMESTAMP.format(now) + ".zip");
            Path target = ensureUnique(base);
            writeZip(target, grouped.getOrDefault(category, List.of()));
            zipPaths.put(category, target);
        }

        Map<SideCategory, List<ModDescriptor>> immutableGrouped = new EnumMap<>(SideCategory.class);
        for (Map.Entry<SideCategory, List<ModDescriptor>> entry : grouped.entrySet()) {
            immutableGrouped.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }

        return new SortResult(Collections.unmodifiableMap(immutableGrouped), Collections.unmodifiableMap(zipPaths), outputDir, now);
    }

    private static List<ModDescriptor> collectModDescriptors(FabricLoader loader, Path modsDir) {
        List<ModDescriptor> descriptors = new ArrayList<>();
        for (ModContainer container : loader.getAllMods()) {
            ModMetadata metadata = container.getMetadata();
            if (ModClientOrServer.MOD_ID.equals(metadata.getId())) {
                continue; // Skip this mod
            }

            Optional<Path> origin = container.getOrigin().getPaths().stream()
                .map(Path::normalize)
                .filter(path -> path.startsWith(modsDir))
                .findFirst();

            if (origin.isEmpty()) {
                continue; // Built-in or outside mods directory
            }

            Path sourcePath = origin.get();
            if (!Files.exists(sourcePath)) {
                continue;
            }

            SideCategory category = mapEnvironment(metadata.getEnvironment());
            String name = metadata.getName();
            if (name == null || name.isBlank()) {
                name = metadata.getId();
            }

            descriptors.add(new ModDescriptor(
                metadata.getId(),
                name,
                metadata.getVersion().getFriendlyString(),
                sourcePath,
                category
            ));
        }

        descriptors.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        return descriptors;
    }

    private static SideCategory mapEnvironment(ModEnvironment environment) {
        if (environment == null) {
            return SideCategory.UNIVERSAL;
        }
        if (environment == ModEnvironment.CLIENT) {
            return SideCategory.CLIENT_ONLY;
        }
        if (environment == ModEnvironment.SERVER) {
            return SideCategory.SERVER_ONLY;
        }
        return SideCategory.UNIVERSAL;
    }

    private static Map<SideCategory, List<ModDescriptor>> groupByCategory(List<ModDescriptor> descriptors) {
        Map<SideCategory, List<ModDescriptor>> map = new EnumMap<>(SideCategory.class);
        for (SideCategory category : SideCategory.values()) {
            map.put(category, new ArrayList<>());
        }
        for (ModDescriptor descriptor : descriptors) {
            map.get(descriptor.category()).add(descriptor);
        }
        return map;
    }

    private static Path ensureUnique(Path base) throws IOException {
        Path candidate = base;
        int index = 1;
        while (Files.exists(candidate)) {
            String fileName = base.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            String prefix = dot >= 0 ? fileName.substring(0, dot) : fileName;
            String suffix = dot >= 0 ? fileName.substring(dot) : "";
            candidate = base.getParent().resolve(prefix + "-" + index + suffix);
            index++;
        }
        return candidate;
    }

    private static void writeZip(Path zipPath, List<ModDescriptor> descriptors) throws IOException {
        try (ZipOutputStream zipOutput = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            byte[] buffer = new byte[8192];
            for (ModDescriptor descriptor : descriptors) {
                addPathToZip(descriptor.sourcePath(), descriptor.sourcePath().getFileName().toString(), zipOutput, buffer);
            }
        }
    }

    private static void addPathToZip(Path inputPath, String baseEntryName, ZipOutputStream zipOutput, byte[] buffer) throws IOException {
        if (Files.isDirectory(inputPath)) {
            try (Stream<Path> stream = Files.walk(inputPath)) {
                stream.filter(Files::isRegularFile).forEach(path -> {
                    Path relative = inputPath.relativize(path);
                    String entryName = baseEntryName + "/" + relative.toString().replace('\\', '/');
                    try {
                        zipOutput.putNextEntry(new ZipEntry(entryName));
                        copyFile(path, zipOutput, buffer);
                        zipOutput.closeEntry();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        } else {
            zipOutput.putNextEntry(new ZipEntry(baseEntryName));
            copyFile(inputPath, zipOutput, buffer);
            zipOutput.closeEntry();
        }
    }

    private static void copyFile(Path source, ZipOutputStream zipOutput, byte[] buffer) throws IOException {
        try (InputStream in = Files.newInputStream(source)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                zipOutput.write(buffer, 0, read);
            }
        }
    }
}
