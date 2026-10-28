package io.dbx;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import static java.nio.file.StandardOpenOption.*;

final class ConfigStore {
    private final Path path;
    ConfigStore(Path path) {
        if (path == null || path.toString().isBlank()) throw Failure.usage("Config path cannot be empty; omit --config to use the persistent default");
        this.path = path.toAbsolutePath().normalize();
        if (Files.isDirectory(this.path)) throw Failure.usage("Config path must be a JSON file, not a directory");
    }
    Map<String, Profile> read() throws IOException {
        if (!Files.exists(path)) return new TreeMap<>();
        if (Files.isSymbolicLink(path)) throw Failure.usage("Config must not be a symbolic link");
        if (Files.size(path) > 1048576) throw Failure.usage("Config is too large");
        try {
            Map<String, Profile> result = Main.JSON.fromJson(Files.readString(path), new TypeToken<TreeMap<String, Profile>>() {}.getType());
            if (result == null) throw Failure.usage("Config must be a JSON object");
            result.forEach((name, p) -> { validateName(name); if (p == null) throw Failure.usage("Null profile"); p.validate(); });
            return result;
        } catch (JsonParseException e) { throw Failure.usage("Invalid config JSON"); }
    }
    Profile get(String name) throws IOException {
        Profile profile = read().get(name);
        if (profile == null) throw new Failure(2, "CONNECTION_NOT_FOUND", "Connection name does not exist");
        return profile;
    }
    void update(Consumer<Map<String, Profile>> change) throws IOException {
        Path dir = path.getParent();
        if (!Files.exists(dir)) Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Path lockPath = dir.resolve(path.getFileName() + ".lock");
        if (Files.isSymbolicLink(lockPath)) throw Failure.usage("Config lock must not be a symbolic link");
        try (var channel = FileChannel.open(lockPath, CREATE, WRITE, LinkOption.NOFOLLOW_LINKS); var lock = channel.lock()) {
            Files.setPosixFilePermissions(lockPath, PosixFilePermissions.fromString("rw-------"));
            var data = read(); change.accept(data);
            Path tmp = Files.createTempFile(dir, ".dbx-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                Files.writeString(tmp, Main.JSON.toJson(data));
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(tmp); }
        }
    }
    static void validateName(String name) {
        if (name == null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}")) throw Failure.usage("Invalid connection name");
    }
}
