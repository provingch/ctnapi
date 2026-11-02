package ctn.informatica.ctnapi.core;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreSnapshotImporterTest {

    private final CoreSnapshotImporter importer = new CoreSnapshotImporter();

    @Test
    void rechazaVersionesDesconocidasAntesDeAbrirLaBase() {
        CoreSnapshot snapshot = new CoreSnapshot(
                2, LocalDateTime.now(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());

        assertThrows(IllegalArgumentException.class, () -> importer.importar(snapshot));
    }

    @Test
    void noPermiteBorrarTodoElCoreConUnSnapshotVacio() {
        CoreSnapshot snapshot = new CoreSnapshot(
                1, LocalDateTime.now(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());

        assertThrows(IllegalArgumentException.class, () -> importer.importar(snapshot));
    }
}
