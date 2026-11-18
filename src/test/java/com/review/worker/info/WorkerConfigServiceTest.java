package com.review.worker.info;

import com.review.worker.config.WorkerProperties;
import com.review.worker.info.dto.ModelDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WorkerConfigService} — real filesystem via JUnit 5 {@code @TempDir}.
 */
class WorkerConfigServiceTest {

    @TempDir
    Path tempDir;

    private WorkerConfigService serviceFor(Path dir) {
        WorkerProperties props = new WorkerProperties("127.0.0.1", "8081", "", "");
        props.getGateway().setUrl("https://gateway.internal");
        props.getGateway().setApiKey("a".repeat(40));
        props.getWorker().setId("worker-1");
        props.getBackend().setId("backend-1");
        props.getLlama().setUrl("http://127.0.0.1:8000");
        // Model path inside dir — service derives modelsDir from the parent of LLAMA_MODEL
        props.getLlama().setModel(dir != null ? dir.resolve("model.gguf").toString() : "model.gguf");
        return new WorkerConfigService(props);
    }

    @Test
    void listsOnlyAllowedExtensions() throws IOException {
        Files.createFile(tempDir.resolve("model.gguf"));
        Files.createFile(tempDir.resolve("model.safetensors"));
        Files.createFile(tempDir.resolve("model.bin"));
        Files.createFile(tempDir.resolve("readme.txt"));
        Files.createFile(tempDir.resolve("script.py"));

        List<ModelDescriptor> result = serviceFor(tempDir).listModels();

        assertThat(result).extracting(ModelDescriptor::name)
                .containsExactlyInAnyOrder("model.gguf", "model.safetensors", "model.bin");
    }

    @Test
    void sortedByLastModifiedDescending() throws IOException {
        Path older = tempDir.resolve("older.gguf");
        Path newer = tempDir.resolve("newer.gguf");
        Files.createFile(older);
        Files.createFile(newer);
        // Force distinct timestamps
        Files.setLastModifiedTime(older, FileTime.from(Instant.parse("2024-01-01T00:00:00Z")));
        Files.setLastModifiedTime(newer, FileTime.from(Instant.parse("2025-06-01T00:00:00Z")));

        List<ModelDescriptor> result = serviceFor(tempDir).listModels();

        assertThat(result).extracting(ModelDescriptor::name)
                .containsExactly("newer.gguf", "older.gguf");
    }

    @Test
    void nonExistentDirThrowsIOException() {
        WorkerProperties props = new WorkerProperties("127.0.0.1", "8081", "", "");
        props.getGateway().setUrl("https://gateway.internal");
        props.getGateway().setApiKey("a".repeat(40));
        props.getWorker().setId("w");
        props.getBackend().setId("b");
        props.getLlama().setUrl("http://127.0.0.1:8000");
        // Parent of this path does not exist — toRealPath() will throw
        props.getLlama().setModel("/nonexistent/path/does/not/exist/model.gguf");
        WorkerConfigService svc = new WorkerConfigService(props);

        assertThatThrownBy(svc::listModels).isInstanceOf(IOException.class);
    }

    @Test
    void noParentPathThrowsIOException() {
        // model filename with no directory part → getParent() == null → modelsDir == null
        assertThatThrownBy(() -> serviceFor(null).listModels())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no parent path");
    }

    @Test
    void modelsScanMaxLimitsResults() throws IOException {
        for (int i = 0; i < 5; i++) {
            Files.createFile(tempDir.resolve("model" + i + ".gguf"));
        }
        // Build a service with max=3 and check only 3 are returned
        WorkerProperties props = new WorkerProperties("127.0.0.1", "8081", "", "");
        props.getGateway().setUrl("https://gateway.internal");
        props.getGateway().setApiKey("a".repeat(40));
        props.getWorker().setId("w");
        props.getBackend().setId("b");
        props.getLlama().setUrl("http://127.0.0.1:8000");
        props.getLlama().setModel(tempDir.resolve("model.gguf").toString());
        props.getInfo().setModelsScanMax(3);
        WorkerConfigService svc = new WorkerConfigService(props);

        List<ModelDescriptor> result = svc.listModels();

        assertThat(result).hasSize(3);
    }

    @Test
    void formatFieldCorrectForEachExtension() throws IOException {
        Files.createFile(tempDir.resolve("a.gguf"));
        Files.createFile(tempDir.resolve("b.safetensors"));
        Files.createFile(tempDir.resolve("c.bin"));

        List<ModelDescriptor> result = serviceFor(tempDir).listModels();

        assertThat(result).anySatisfy(d -> {
            assertThat(d.name()).isEqualTo("a.gguf");
            assertThat(d.format()).isEqualTo("GGUF");
        });
        assertThat(result).anySatisfy(d -> {
            assertThat(d.name()).isEqualTo("b.safetensors");
            assertThat(d.format()).isEqualTo("safetensors");
        });
        assertThat(result).anySatisfy(d -> {
            assertThat(d.name()).isEqualTo("c.bin");
            assertThat(d.format()).isEqualTo("other");
        });
    }
}
