package io.github.roberbarras.racha.admin.adapter.files;

import io.github.roberbarras.racha.admin.domain.AdminPorts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** The dumps of the backup service (`racha-*.dump`), read-only: names, sizes and dates, never their contents. */
public final class FileBackups implements AdminPorts.Backups {
    private final Path dir;

    public FileBackups(String dir) {
        this.dir = dir == null || dir.isBlank() ? null : Path.of(dir);
    }

    @Override
    public String location() {
        return dir == null ? null : dir.toString();
    }

    @Override
    public List<AdminPorts.BackupFile> list() {
        if (dir == null || !Files.isDirectory(dir)) return List.of();
        try (var files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().matches("racha-\\d{8}-\\d{6}\\.dump"))
                    .map(f -> {
                        try {
                            return new AdminPorts.BackupFile(f.getFileName().toString(), Files.size(f), Files.getLastModifiedTime(f).toInstant());
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(AdminPorts.BackupFile::name).reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
