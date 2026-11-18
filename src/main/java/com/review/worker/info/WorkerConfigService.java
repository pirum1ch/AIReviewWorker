package com.review.worker.info;

import com.review.worker.config.WorkerProperties;
import com.review.worker.info.dto.ModelDescriptor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Scans the configured models directory and returns a list of {@link ModelDescriptor} entries,
 * sorted by last-modified descending.
 *
 * <p>Security constraints enforced:
 * <ul>
 *   <li>Symlinks are never followed ({@link LinkOption#NOFOLLOW_LINKS} everywhere).</li>
 *   <li>Each entry's canonical path is checked to start with the directory's canonical path (symlink
 *       escape check).</li>
 *   <li>Only {@code .gguf}, {@code .bin}, {@code .safetensors} files are returned.</li>
 *   <li>Scan stops at {@link #MAX_FILES} entries to prevent runaway iteration on a misconfigured path.</li>
 * </ul>
 */
@Service
public class WorkerConfigService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".gguf", ".bin", ".safetensors");

    private final Path modelsDir;
    private final int maxFiles;

    public WorkerConfigService(WorkerProperties props) {
        String model = props.getLlama().getModel();
        Path parent = model != null ? Path.of(model).getParent() : null;
        this.modelsDir = parent;
        this.maxFiles = props.getInfo().getModelsScanMax();
    }

    /**
     * Returns model files found in the configured models directory.
     *
     * @throws IOException if the directory is not configured, does not exist, or cannot be read.
     */
    public List<ModelDescriptor> listModels() throws IOException {
        if (modelsDir == null) {
            throw new IOException("models directory not configured (LLAMA_MODEL has no parent path)");
        }
        Path canonical = modelsDir.toRealPath();
        List<ModelDescriptor> result = new ArrayList<>();
        int count = 0;
        try (var stream = Files.list(canonical)) {
            for (var iter = stream.iterator(); iter.hasNext() && count < maxFiles; ) {
                Path entry = iter.next();
                count++;
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                // Symlink escape check: real path must stay inside the scanned directory.
                Path entryCanonical = entry.toRealPath();
                if (!entryCanonical.startsWith(canonical)) {
                    continue;
                }
                String name = entry.getFileName().toString();
                String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
                if (!ALLOWED_EXTENSIONS.contains(ext.toLowerCase())) {
                    continue;
                }
                BasicFileAttributes attrs = Files.readAttributes(
                        entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                String format = switch (ext.toLowerCase()) {
                    case ".gguf" -> "GGUF";
                    case ".safetensors" -> "safetensors";
                    default -> "other";
                };
                result.add(new ModelDescriptor(
                        name,
                        entry.toAbsolutePath().toString(),
                        attrs.size(),
                        format,
                        attrs.lastModifiedTime().toInstant().toString()
                ));
            }
        }
        result.sort(Comparator.comparing(ModelDescriptor::lastModified).reversed());
        return result;
    }
}
