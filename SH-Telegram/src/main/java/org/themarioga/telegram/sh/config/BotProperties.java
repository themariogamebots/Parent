package org.themarioga.telegram.sh.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Datos de presentación del bot: lo que sale en /help y en los mensajes de cabecera.
 * <p>
 * Son configuración de despliegue, no datos: su sitio es el fichero de propiedades.
 */
@Configuration
@ConfigurationProperties(prefix = "sh.telegram")
public class BotProperties {

    private final Bot game = new Bot();

    public Bot getGame() {
        return game;
    }

    public static class Bot {

        private String displayName;
        private String alias;
        private String version;
        private String ownerAlias;
        private String helpUrl;

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getAlias() {
            return alias;
        }

        public void setAlias(String alias) {
            this.alias = alias;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getOwnerAlias() {
            return ownerAlias;
        }

        public void setOwnerAlias(String ownerAlias) {
            this.ownerAlias = ownerAlias;
        }

        public String getHelpUrl() {
            return helpUrl;
        }

        public void setHelpUrl(String helpUrl) {
            this.helpUrl = helpUrl;
        }

    }

}
