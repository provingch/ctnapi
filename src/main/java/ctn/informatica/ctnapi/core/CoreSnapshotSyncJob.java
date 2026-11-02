package ctn.informatica.ctnapi.core;

import java.net.URI;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@ConditionalOnProperty(name = "ctnapi.core-sync.enabled", havingValue = "true")
public class CoreSnapshotSyncJob {

    private static final Logger log = LoggerFactory.getLogger(CoreSnapshotSyncJob.class);

    private final String sourceUrl;
    private final String apiKey;
    private final RestClient restClient;
    private final CoreSnapshotImporter importer;

    public CoreSnapshotSyncJob(
            @Value("${ctnapi.core-sync.source-url}") String sourceUrl,
            @Value("${ctnapi.core-sync.api-key}") String apiKey,
            CoreSnapshotImporter importer) {
        URI source = URI.create(sourceUrl);
        if ((!"http".equalsIgnoreCase(source.getScheme()) && !"https".equalsIgnoreCase(source.getScheme()))
                || source.getHost() == null) {
            throw new IllegalArgumentException("CORE_SYNC_SOURCE_URL debe ser una URL HTTP/HTTPS completa");
        }
        if (apiKey == null || apiKey.length() < 32) {
            throw new IllegalArgumentException("CORE_SYNC_API_KEY debe tener al menos 32 caracteres");
        }
        this.sourceUrl = sourceUrl;
        this.apiKey = apiKey;
        this.importer = importer;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(5));
        factory.setReadTimeout(java.time.Duration.ofSeconds(60));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Scheduled(
            fixedDelayString = "${ctnapi.core-sync.interval-ms:300000}",
            initialDelayString = "${ctnapi.core-sync.initial-delay-ms:15000}")
    public void sincronizar() {
        try {
            CoreSnapshot snapshot = restClient.get()
                    .uri(sourceUrl)
                    .header("X-Core-Sync-Key", apiKey)
                    .retrieve()
                    .body(CoreSnapshot.class);
            if (snapshot == null) {
                throw new IllegalArgumentException("SCA devolvió un snapshot vacío");
            }
            CoreSnapshotImporter.ImportResult result = importer.importar(snapshot);
            log.info("Sincronización del Core desde SCA completada: {} alumnos, {} perfiles, {} cursos, "
                            + "{} materias y {} asignaciones",
                    result.alumnos(), result.perfiles(), result.cursos(), result.materias(), result.asignaciones());
        } catch (RestClientException | SQLException | IllegalArgumentException ex) {
            log.error("No se pudo sincronizar el Core escolar desde SCA; se conserva el último snapshot aplicado", ex);
        }
    }
}
