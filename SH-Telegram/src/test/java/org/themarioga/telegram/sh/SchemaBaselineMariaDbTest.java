package org.themarioga.telegram.sh;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import org.themarioga.commons.engine.models.Lang;
import org.themarioga.commons.engine.services.intf.I18NService;
import org.themarioga.commons.engine.services.intf.LanguageService;

import java.util.List;

/**
 * El mismo contrato que {@link SchemaBaselineTest} pero contra MariaDB de verdad.
 * <p>
 * Hace falta porque el baseline que se despliega es el de MariaDB y el resto de tests corren sobre
 * H2: cualquier diferencia propia de MariaDB pasaría entera hasta producción. En CAH-Telegram pasó
 * de verdad — el DDL se generó asumiendo MariaDB 10.6, donde un {@code UUID} se mapea a
 * {@code binary(16)} en vez de al tipo {@code uuid} nativo de 10.7 en adelante, y el arranque moría
 * con "wrong column type encountered in column [id]". Aquí se hereda el mismo
 * {@code SchemaGenerator} y por tanto el mismo riesgo.
 */
@Testcontainers
@EnabledIfDockerAvailable
@SpringBootTest(properties = {
        // El application.properties de test apunta a H2; aquí hay que validar el otro baseline.
        "spring.flyway.locations=classpath:db/migration/mariadb"})
class SchemaBaselineMariaDbTest {

    /**
     * Debe ser >= la versión fijada en {@code SchemaGenerator.MARIADB_VERSION} (10.7), que es la que
     * decide si los UUID se generan como {@code uuid} o como {@code binary(16)}.
     */
    private static final String IMAGE = "mariadb:11.4";

    @Container
    @ServiceConnection
    static MariaDBContainer mariadb = new MariaDBContainer(IMAGE);

    @Autowired
    private LanguageService languageService;
    @Autowired
    private I18NService i18NService;
    @Autowired
    private EntityManager entityManager;

    @Test
    void schemaMatchesTheEntityModel() {
        // Si hemos llegado aquí, Flyway migró contra MariaDB y Hibernate validó el resultado.
        Assertions.assertNotNull(languageService);
    }

    @Test
    void serverIsRecentEnoughForNativeUuid() {
        String version = (String) entityManager.createNativeQuery("SELECT VERSION()").getSingleResult();
        String[] parts = version.split("[.-]");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);

        Assertions.assertTrue(major > 10 || (major == 10 && minor >= 7), () -> "el esquema se genera para MariaDB >= 10.7 y el contenedor trae " + version);
    }

    /**
     * Fija la regresión concreta: ninguna columna debe ser {@code binary}. Es lo que salía cuando el
     * DDL se generaba asumiendo MariaDB 10.6, y en este modelo no hay ningún uso legítimo de binary.
     */
    @Test
    void noColumnFallsBackToBinary() {
        @SuppressWarnings("unchecked")
        List<String> binaryColumns = entityManager.createNativeQuery("SELECT CONCAT(table_name, '.', column_name) FROM information_schema.columns WHERE table_schema = DATABASE() AND data_type = 'binary' ORDER BY 1").getResultList();

        Assertions.assertTrue(binaryColumns.isEmpty(), () -> "columnas binary en el esquema (¿se regeneró el baseline con una versión de MariaDB anterior a la 10.7?): " + binaryColumns);
    }

    @Test
    void idColumnsUseTheNativeUuidType() {
        for (String[] column : List.of(new String[] {"users", "id"}, new String[] {"game", "creator_id"}, new String[] {"player", "user_id"}, new String[] {"telegram_room", "room_id"})) {
            String type = (String) entityManager.createNativeQuery("SELECT data_type FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = :t AND column_name = :c").setParameter("t", column[0]).setParameter("c", column[1]).getSingleResult();

            Assertions.assertEquals("uuid", type, () -> column[0] + "." + column[1] + " debería ser uuid");
        }
    }

    /**
     * Las migraciones de datos van por dialecto igual que el baseline, así que la semilla de idiomas
     * y tags también hay que comprobarla aquí y no solo en H2.
     */
    @Test
    void seedDataIsLoaded() {
        List<Lang> langs = languageService.getLangs();

        Assertions.assertEquals(2, langs.size());
        Assertions.assertEquals("es", languageService.getDefaultLanguage().getId());

        Assertions.assertEquals("← Volver", i18NService.get("GO_BACK", "es"));
        Assertions.assertNotEquals("SH_ROLE_HITLER", i18NService.get("SH_ROLE_HITLER", "es"));
        Assertions.assertNotEquals("SH_ROLE_HITLER", i18NService.get("SH_ROLE_HITLER", "en"));
    }

}
